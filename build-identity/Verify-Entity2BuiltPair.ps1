[CmdletBinding()]
param(
    [string]$OutputPath = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Import-Module (Join-Path $PSScriptRoot 'Entity2.BuildIdentity.psm1') -Force
$identityPath = Join-Path $repositoryRoot 'build\identity\entity2-build-identity.properties'
$expected = Read-Entity2BuildIdentity -Path $identityPath

$sourceCommit = (& git -c "safe.directory=$repositoryRoot" -C $repositoryRoot rev-parse HEAD |
    Out-String).Trim().ToLowerInvariant()
if ($LASTEXITCODE -ne 0 -or $sourceCommit -cne $expected.sourceCommit) {
    throw "Built-pair source mismatch: identity=$($expected.sourceCommit) HEAD=$sourceCommit"
}
$sourceStatus = (& git -c "safe.directory=$repositoryRoot" -C $repositoryRoot `
    status --porcelain=v1 --untracked-files=normal | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or -not [string]::IsNullOrWhiteSpace($sourceStatus)) {
    throw 'Refusing to verify a release pair from a dirty source worktree.'
}
if ($expected.sourceState -cne 'clean') {
    throw "Refusing non-clean embedded source state $($expected.sourceState)"
}

$clientPath = Join-Path $repositoryRoot `
    "entity-client\build\libs\entity2-client-$($expected.version).jar"
$serverPath = Join-Path $repositoryRoot `
    "server-plugin\build\libs\EntityBridge-v2-$($expected.version).jar"
$client = Read-Entity2JarBuildIdentity -Path $clientPath
$server = Read-Entity2JarBuildIdentity -Path $serverPath
Assert-Entity2BuildIdentityMatch -Expected $expected -Actual $client -ActualLabel 'client jar'
Assert-Entity2BuildIdentityMatch -Expected $expected -Actual $server -ActualLabel 'server jar'
Assert-Entity2BuildIdentityMatch -Expected $client -Actual $server -ActualLabel 'paired jars'
Assert-Entity2JarResource -Path $serverPath -EntryName 'entity-companion-owner.txt' `
    -SourcePath (Join-Path $repositoryRoot 'server-plugin/src/main/resources/entity-companion-owner.txt')

if (-not $OutputPath) {
    $OutputPath = Join-Path $repositoryRoot 'build\identity\built-pair.json'
}
$output = [IO.Path]::GetFullPath($OutputPath)
$expectedParent = [IO.Path]::GetFullPath((Join-Path $repositoryRoot 'build\identity'))
if (-not $output.StartsWith(
        $expectedParent + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase)) {
    throw "Built-pair evidence must stay under ${expectedParent}: $output"
}
[IO.Directory]::CreateDirectory((Split-Path -Parent $output)) | Out-Null
$evidence = [ordered]@{
    schemaVersion = 1
    sourceCommit = $sourceCommit
    version = $expected.version
    buildIdentity = [ordered]@{
        schemaVersion = $expected.schemaVersion
        version = $expected.version
        sourceCommit = $expected.sourceCommit
        buildId = $expected.buildId
        sourceState = $expected.sourceState
        generatedAtUtc = $expected.generatedAtUtc
    }
    client = [ordered]@{
        path = [IO.Path]::GetRelativePath($repositoryRoot, $clientPath).Replace('\', '/')
        sha256 = (Get-FileHash -LiteralPath $clientPath -Algorithm SHA256).Hash.ToLowerInvariant()
        bytes = (Get-Item -LiteralPath $clientPath).Length
    }
    server = [ordered]@{
        path = [IO.Path]::GetRelativePath($repositoryRoot, $serverPath).Replace('\', '/')
        sha256 = (Get-FileHash -LiteralPath $serverPath -Algorithm SHA256).Hash.ToLowerInvariant()
        bytes = (Get-Item -LiteralPath $serverPath).Length
    }
    verifiedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
}
[IO.File]::WriteAllText(
    $output,
    (($evidence | ConvertTo-Json -Depth 8) + [Environment]::NewLine),
    [Text.UTF8Encoding]::new($false))
Write-Host "PASS: exact Entity2 client/server pair verified at $sourceCommit"
Write-Host "Built-pair evidence: $output"
