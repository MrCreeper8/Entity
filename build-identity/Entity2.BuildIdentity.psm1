Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:IdentityResourceName = 'entity2-build-identity.properties'

function Invoke-Entity2IdentityGitText {
    param(
        [Parameter(Mandatory)][string]$RepositoryRoot,
        [Parameter(Mandatory)][string[]]$Arguments
    )

    # Codex worktrees may be created by the isolated runner account and then
    # built by the desktop account. Scope trust to this exact repository for
    # this invocation; never require a persistent global safe.directory edit.
    $text = (& git -c "safe.directory=$RepositoryRoot" -C $RepositoryRoot @Arguments | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) {
        throw "Git failed with exit code ${LASTEXITCODE}: git $($Arguments -join ' ')"
    }
    return $text
}

function Get-Entity2IdentitySha256Text {
    param([Parameter(Mandatory)][string]$Value)

    $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
    return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
}

function Get-Entity2Version {
    param([Parameter(Mandatory)][string]$VersionSource)

    $path = [IO.Path]::GetFullPath($VersionSource)
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Entity2 version source is missing: $path"
    }
    $line = Get-Content -LiteralPath $path |
        Where-Object { $_ -match '^version=' } | Select-Object -First 1
    if (-not $line) { throw "Entity2 version source has no version entry: $path" }
    $version = ($line -split '=', 2)[1].Trim()
    if ($version -notmatch '^\d+\.\d+\.\d+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?$') {
        throw "Invalid Entity2 semantic version in ${path}: $version"
    }
    return $version
}

function Get-Entity2SourceState {
    param([Parameter(Mandatory)][string]$RepositoryRoot)

    $root = [IO.Path]::GetFullPath($RepositoryRoot)
    $status = Invoke-Entity2IdentityGitText -RepositoryRoot $root `
        -Arguments @('status', '--porcelain=v1', '--untracked-files=all')
    if ([string]::IsNullOrWhiteSpace($status)) { return 'clean' }

    $diff = Invoke-Entity2IdentityGitText -RepositoryRoot $root `
        -Arguments @('diff', '--no-ext-diff', '--binary', 'HEAD', '--', '.')
    $untrackedText = Invoke-Entity2IdentityGitText -RepositoryRoot $root `
        -Arguments @('ls-files', '--others', '--exclude-standard')
    $untracked = @($untrackedText -split "`r?`n" |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Sort-Object)
    $untrackedFacts = foreach ($relative in $untracked) {
        $path = Join-Path $root $relative
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
            "$relative=$hash"
        } else {
            "$relative=<non-file>"
        }
    }
    $fingerprint = Get-Entity2IdentitySha256Text -Value (
        $status + "`n--tracked-diff--`n" + $diff +
        "`n--untracked-content--`n" + ($untrackedFacts -join "`n"))
    return "dirty-$fingerprint"
}

function ConvertFrom-Entity2BuildIdentityText {
    param(
        [Parameter(Mandatory)][string]$Text,
        [Parameter(Mandatory)][string]$Label
    )

    $values = @{}
    foreach ($line in ($Text -split "`r?`n")) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith('#')) { continue }
        $parts = $trimmed -split '=', 2
        if ($parts.Count -ne 2 -or [string]::IsNullOrWhiteSpace($parts[0])) {
            throw "$Label contains an invalid property line: $line"
        }
        $values[$parts[0].Trim()] = $parts[1].Trim()
    }
    foreach ($required in @(
            'schemaVersion', 'version', 'sourceCommit', 'buildId',
            'sourceState', 'generatedAtUtc')) {
        if (-not $values.ContainsKey($required) -or
                [string]::IsNullOrWhiteSpace([string]$values[$required])) {
            throw "$Label is missing required property $required"
        }
    }
    $identity = [pscustomobject][ordered]@{
        schemaVersion = [int]$values.schemaVersion
        version = [string]$values.version
        sourceCommit = ([string]$values.sourceCommit).ToLowerInvariant()
        buildId = [string]$values.buildId
        sourceState = ([string]$values.sourceState).ToLowerInvariant()
        generatedAtUtc = [string]$values.generatedAtUtc
    }
    Assert-Entity2BuildIdentity -Identity $identity -Label $Label
    return $identity
}

function Assert-Entity2BuildIdentity {
    param(
        [Parameter(Mandatory)]$Identity,
        [Parameter(Mandatory)][string]$Label
    )

    if ([int]$Identity.schemaVersion -ne 1) {
        throw "$Label uses unsupported schema $($Identity.schemaVersion)"
    }
    if ([string]$Identity.version -notmatch '^\d+\.\d+\.\d+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?$') {
        throw "$Label has an invalid semantic version: $($Identity.version)"
    }
    if ([string]$Identity.sourceCommit -notmatch '^[0-9a-f]{40,64}$') {
        throw "$Label must contain the full Git commit"
    }
    if ([string]$Identity.buildId -notmatch '^[0-9A-Za-z][0-9A-Za-z._-]{7,127}$') {
        throw "$Label has an invalid build ID: $($Identity.buildId)"
    }
    if ([string]$Identity.sourceState -notmatch '^(?:clean|dirty-[0-9a-f]{64})$') {
        throw "$Label has an invalid source state: $($Identity.sourceState)"
    }
    try {
        [void][DateTimeOffset]::ParseExact(
            [string]$Identity.generatedAtUtc,
            'o',
            [Globalization.CultureInfo]::InvariantCulture,
            [Globalization.DateTimeStyles]::RoundtripKind)
    } catch {
        throw "$Label has an invalid UTC generation timestamp: $($Identity.generatedAtUtc)"
    }
}

function Read-Entity2BuildIdentity {
    param([Parameter(Mandatory)][string]$Path)

    $fullPath = [IO.Path]::GetFullPath($Path)
    if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
        throw "Entity2 build identity is missing: $fullPath"
    }
    return ConvertFrom-Entity2BuildIdentityText `
        -Text ([IO.File]::ReadAllText($fullPath, [Text.Encoding]::UTF8)) `
        -Label "Entity2 build identity $fullPath"
}

function Read-Entity2JarBuildIdentity {
    param([Parameter(Mandatory)][string]$Path)

    $archive = [IO.Path]::GetFullPath($Path)
    if (-not (Test-Path -LiteralPath $archive -PathType Leaf)) {
        throw "Entity2 artifact is missing: $archive"
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($archive)
    try {
        $entry = $zip.GetEntry($script:IdentityResourceName)
        if ($null -eq $entry) {
            throw "Entity2 artifact has no embedded build identity: $archive"
        }
        $reader = [IO.StreamReader]::new($entry.Open(), [Text.Encoding]::UTF8, $true)
        try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }
    } finally {
        $zip.Dispose()
    }
    return ConvertFrom-Entity2BuildIdentityText `
        -Text $text -Label "embedded build identity in $archive"
}

function Assert-Entity2JarResource {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$EntryName,
        [Parameter(Mandatory)][string]$SourcePath
    )
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead([IO.Path]::GetFullPath($Path))
    try {
        $entry = $zip.GetEntry($EntryName)
        if ($null -eq $entry) { throw "Required jar resource is missing: $EntryName" }
        $stream = $entry.Open()
        $sha = [Security.Cryptography.SHA256]::Create()
        try { $actual = [Convert]::ToHexString($sha.ComputeHash($stream)) }
        finally { $stream.Dispose(); $sha.Dispose() }
        $expected = (Get-FileHash -LiteralPath $SourcePath -Algorithm SHA256).Hash
        if ($actual -cne $expected) { throw "Jar resource differs from source: $EntryName" }
    } finally { $zip.Dispose() }
}

function Assert-Entity2BuildIdentityMatch {
    param(
        [Parameter(Mandatory)]$Expected,
        [Parameter(Mandatory)]$Actual,
        [Parameter(Mandatory)][string]$ActualLabel
    )

    foreach ($field in @(
            'schemaVersion', 'version', 'sourceCommit', 'buildId',
            'sourceState', 'generatedAtUtc')) {
        if ([string]$Expected.$field -cne [string]$Actual.$field) {
            throw "Exact build identity mismatch for ${ActualLabel}: $field expected=$($Expected.$field) actual=$($Actual.$field)"
        }
    }
}

function New-Entity2BuildIdentity {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$RepositoryRoot,
        [string]$VersionSource = '',
        [string]$OutputPath = '',
        [string]$BuildId = '',
        [switch]$Force,
        [switch]$RequireClean
    )

    $root = [IO.Path]::GetFullPath($RepositoryRoot).TrimEnd('\', '/')
    if (-not $VersionSource) {
        $VersionSource = Join-Path $root 'build-identity\version.properties'
    }
    if (-not $OutputPath) {
        $OutputPath = Join-Path $root "build\identity\$script:IdentityResourceName"
    }
    $output = [IO.Path]::GetFullPath($OutputPath)
    $version = Get-Entity2Version -VersionSource $VersionSource
    $commit = (Invoke-Entity2IdentityGitText -RepositoryRoot $root `
        -Arguments @('rev-parse', 'HEAD')).ToLowerInvariant()
    if ($commit -notmatch '^[0-9a-f]{40,64}$') {
        throw "Git returned a non-full source commit: $commit"
    }
    $sourceState = Get-Entity2SourceState -RepositoryRoot $root
    if ($RequireClean -and $sourceState -ne 'clean') {
        throw "Refusing a release build identity from non-clean source state $sourceState"
    }

    $parent = Split-Path -Parent $output
    [IO.Directory]::CreateDirectory($parent) | Out-Null
    $lockPath = "$output.lock"
    $lock = $null
    $lockDeadline = [DateTime]::UtcNow.AddSeconds(30)
    while ($null -eq $lock -and [DateTime]::UtcNow -lt $lockDeadline) {
        try {
            $lock = [IO.File]::Open(
                $lockPath,
                [IO.FileMode]::OpenOrCreate,
                [IO.FileAccess]::ReadWrite,
                [IO.FileShare]::None)
        } catch [IO.IOException] {
            Start-Sleep -Milliseconds 50
        }
    }
    if ($null -eq $lock) {
        throw "Timed out waiting for paired-build identity lock: $lockPath"
    }

    try {
        $requestedBuildId = $BuildId.Trim()
        if (-not $requestedBuildId -and $env:ENTITY2_BUILD_ID) {
            $requestedBuildId = $env:ENTITY2_BUILD_ID.Trim()
        }
        if (-not $Force -and (Test-Path -LiteralPath $output -PathType Leaf)) {
            $existing = Read-Entity2BuildIdentity -Path $output
            if ($existing.version -ceq $version -and
                    $existing.sourceCommit -ceq $commit -and
                    $existing.sourceState -ceq $sourceState -and
                    (-not $requestedBuildId -or $existing.buildId -ceq $requestedBuildId)) {
                return $existing
            }
        }

        if (-not $requestedBuildId) {
            $stateToken = if ($sourceState -eq 'clean') {
                'clean'
            } else {
                $sourceState.Substring('dirty-'.Length, 12)
            }
            $requestedBuildId = 'local-{0}-{1}-{2}-{3}' -f `
                $commit.Substring(0, 12), $stateToken,
                ([DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')),
                ([guid]::NewGuid().ToString('N').Substring(0, 8))
        }
        $generatedAt = [DateTimeOffset]::UtcNow.ToString('o')
        $candidate = [pscustomobject][ordered]@{
            schemaVersion = 1
            version = $version
            sourceCommit = $commit
            buildId = $requestedBuildId
            sourceState = $sourceState
            generatedAtUtc = $generatedAt
        }
        Assert-Entity2BuildIdentity -Identity $candidate -Label 'generated Entity2 build identity'

        $temporary = "$output.$PID.tmp"
        $lines = @(
            'schemaVersion=1',
            "version=$version",
            "sourceCommit=$commit",
            "buildId=$requestedBuildId",
            "sourceState=$sourceState",
            "generatedAtUtc=$generatedAt"
        )
        [IO.File]::WriteAllLines($temporary, $lines, [Text.UTF8Encoding]::new($false))
        Move-Item -LiteralPath $temporary -Destination $output -Force
        return Read-Entity2BuildIdentity -Path $output
    } finally {
        $lock.Dispose()
    }
}

Export-ModuleMember -Function @(
    'Get-Entity2Version',
    'Get-Entity2SourceState',
    'New-Entity2BuildIdentity',
    'Read-Entity2BuildIdentity',
    'Read-Entity2JarBuildIdentity',
    'Assert-Entity2BuildIdentity',
    'Assert-Entity2BuildIdentityMatch',
    'Assert-Entity2JarResource'
)
