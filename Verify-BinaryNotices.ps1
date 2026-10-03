[CmdletBinding()]
param(
    [string]$NoticeDirectory = (Join-Path $PSScriptRoot 'licenses'),
    [string]$ProjectAssetsPath,
    [string]$PublishedDirectory,
    [string]$ApplicationName = 'Entity',
    [switch]$AsJson
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$directory = [IO.Path]::GetFullPath($NoticeDirectory)
$inventory = Get-Content -Raw -LiteralPath (Join-Path $directory 'inventory.json') | ConvertFrom-Json
if ($inventory.schema -ne 1 -or $inventory.platform -ne 'win-x64' -or $inventory.runtimeVersion -ne '8.0.31') {
    throw 'Binary notice inventory is not the reviewed win-x64 / .NET 8.0.31 set.'
}
$sums = @(Get-Content -LiteralPath (Join-Path $directory 'SHA256SUMS'))
$seen = @{}
foreach ($line in $sums) {
    if ($line -notmatch '^([a-f0-9]{64})  ([A-Za-z0-9_.+-]+)$') { throw 'Malformed license checksum entry.' }
    $expected = $Matches[1]; $name = $Matches[2]
    if ($seen.ContainsKey($name)) { throw "Duplicate license checksum entry: $name" }
    $seen[$name] = $true
    $path = Join-Path $directory $name
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing third-party notice: $name" }
    if ((Get-Item -LiteralPath $path).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Notice may not be a link: $name" }
    if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) {
        throw "Third-party notice changed: $name"
    }
}
$actual = @(Get-ChildItem -LiteralPath $directory -File | Where-Object Name -ne 'SHA256SUMS')
if ($actual.Count -ne $seen.Count -or @($actual | Where-Object { -not $seen.ContainsKey($_.Name) }).Count -ne 0) {
    throw 'The license folder contains an unreviewed or missing file.'
}
$required = @($inventory.runtimeLicenseFiles) + @($inventory.nugetPackages | ForEach-Object licenseFiles) +
    @($inventory.downloadOnly | ForEach-Object licenseFiles) + @('Prefab-MIT.txt')
foreach ($file in $required) {
    if (-not $seen.ContainsKey($file)) { throw "Inventory notice is not checksummed: $file" }
}

$resolvedChecked = $false
if ($ProjectAssetsPath) {
    $assets = Get-Content -Raw -LiteralPath $ProjectAssetsPath | ConvertFrom-Json
    $expected = @{}
    foreach ($package in $inventory.nugetPackages) { $expected[$package.id + '/' + $package.version] = $package }
    $found = @{}
    foreach ($library in $assets.libraries.PSObject.Properties) {
        if ($library.Value.type -ne 'package') { continue }
        if ($expected.ContainsKey($library.Name)) {
            if ($library.Value.sha512 -ne $expected[$library.Name].nugetContentHash) {
                throw "Resolved package content identity differs: $($library.Name)"
            }
            $found[$library.Name] = $true
        } elseif ($library.Name -match '^Microsoft\.(NETCore\.App\.(Runtime|Host|Ref)(\.win-x64)?|WindowsDesktop\.App\.(Runtime|Ref)(\.win-x64)?)/8\.0\.31$') {
            # Explicit self-contained framework/host packs, covered by the full runtime notices.
        } else {
            throw "Unreviewed resolved desktop package: $($library.Name)"
        }
    }
    if ($found.Count -ne $expected.Count) { throw 'A reviewed NuGet dependency is absent from the resolved graph.' }
    $resolvedChecked = $true
}

$publishedChecked = $false
if ($PublishedDirectory) {
    $root = [IO.Path]::GetFullPath($PublishedDirectory)
    $config = Get-Content -Raw -LiteralPath (Join-Path $root "$ApplicationName.runtimeconfig.json") | ConvertFrom-Json
    $options = $config.runtimeOptions
    if (-not ($options.PSObject.Properties.Name -contains 'includedFrameworks')) {
        throw 'Final Windows package must have an explicitly pinned self-contained runtime.'
    }
    $frameworks = @($options.includedFrameworks)
    foreach ($framework in $frameworks) {
        if ($framework.version -ne $inventory.runtimeVersion) { throw "Unreviewed runtime version: $($framework.name) $($framework.version)" }
    }
    if (-not ($frameworks.name -contains 'Microsoft.NETCore.App')) { throw 'Self-contained core runtime is missing.' }
    $bundledDownloads = @(Get-ChildItem -LiteralPath $root -Recurse -File | Where-Object {
        $_.Name -match '^(?i)(baritone(?:-.+)?|fabric-api(?:-.+)?|paper(?:-.+)?)\.jar$' -or
        $_.Name -match '^(?i).+\.gguf$'
    })
    if ($bundledDownloads.Count) { throw 'Publisher-only mod/server/model bytes were added to the release package.' }
    $publishedChecked = $true
}

$result = [ordered]@{
    passed = $true
    noticeFiles = $seen.Count
    nugetPackages = $inventory.nugetPackages.Count
    runtimeVersion = $inventory.runtimeVersion
    resolvedGraphChecked = $resolvedChecked
    publishedRuntimeChecked = $publishedChecked
}
if ($AsJson) { $result | ConvertTo-Json -Compress } else { Write-Output "Binary notices verified: $($seen.Count) retained files, $($inventory.nugetPackages.Count) pinned NuGet packages, .NET $($inventory.runtimeVersion)." }
