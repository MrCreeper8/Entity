[CmdletBinding()]
param(
    [string]$Destination = (Join-Path $PSScriptRoot 'build'),
    [string]$ConfigPath,
    [switch]$ReplaceConfiguration
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$manifest = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'pinned-runtime.json') -Raw | ConvertFrom-Json
$fileNameProperty = $manifest.model.PSObject.Properties['fileName']
if ($null -eq $fileNameProperty -or $fileNameProperty.Value -isnot [string] -or
    $fileNameProperty.Value -cnotmatch '\A[A-Za-z0-9][A-Za-z0-9._-]*\.gguf\z') {
    throw 'Pinned model filename must be a plain .gguf filename.'
}
$reasoningProperty = $manifest.model.PSObject.Properties['reasoningTokens']
if ($null -eq $reasoningProperty -or
    ($reasoningProperty.Value -isnot [int] -and $reasoningProperty.Value -isnot [long]) -or
    $reasoningProperty.Value -lt 0 -or $reasoningProperty.Value -gt 256) {
    throw 'Pinned model reasoningTokens must be an integer from 0 to 256.'
}
$destinationPath = [IO.Path]::GetFullPath($Destination)
New-Item -ItemType Directory -Force -Path $destinationPath | Out-Null
if (-not $ConfigPath) { $ConfigPath = Join-Path $destinationPath 'local-ai.json' }
$configFullPath = [IO.Path]::GetFullPath($ConfigPath)
if ((Test-Path -LiteralPath $configFullPath) -and -not $ReplaceConfiguration) {
    throw 'Configuration already exists. Use -ReplaceConfiguration only when intentionally replacing this exact AI configuration.'
}
function Get-PinnedAsset($Asset, [string]$Name) {
    $file = Join-Path $destinationPath $Name
    if (Test-Path -LiteralPath $file) {
        if ((Get-Item -LiteralPath $file).Length -ne $Asset.bytes -or
            (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $Asset.sha256) {
            throw "Existing asset does not match its publisher pin: $file"
        }
        return $file
    }
    $partial = "$file.partial"
    Write-Host "Downloading $Name ($([math]::Round($Asset.bytes / 1MB)) MiB)..."
    & curl.exe --fail --location --retry 3 --connect-timeout 20 --max-time 1800 --continue-at - --output $partial $Asset.url
    if ($LASTEXITCODE -ne 0) { throw "Download failed: $Name (partial retained for resume)" }
    if ((Get-Item -LiteralPath $partial).Length -ne $Asset.bytes -or
        (Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash -ne $Asset.sha256) {
        throw "Publisher hash/length mismatch: $Name. Unverified asset was not used."
    }
    Move-Item -LiteralPath $partial -Destination $file
    return $file
}
$runtimeZip = Get-PinnedAsset $manifest.runtime 'llama-b11146-win-cuda12.4.zip'
$cudaZip = Get-PinnedAsset $manifest.cuda 'cudart-b11146-win-cuda12.4.zip'
$model = Get-PinnedAsset $manifest.model $fileNameProperty.Value
$runtimeDir = Join-Path $destinationPath 'llama-b11146'
New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
foreach ($archive in @($runtimeZip, $cudaZip)) {
    $zip = [IO.Compression.ZipFile]::OpenRead($archive)
    try {
        foreach ($entry in $zip.Entries) {
            # Only the server and its native dependencies are needed; retain licenses.
            if (-not ($entry.Name -match '^(llama-server\.exe|.+\.dll|LICENSE.*|COPYING.*)$')) { continue }
            $target = Join-Path $runtimeDir $entry.Name
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
        }
    } finally { $zip.Dispose() }
}
$exe = Join-Path $runtimeDir 'llama-server.exe'
if (-not (Test-Path -LiteralPath $exe)) { throw 'Pinned archive did not contain llama-server.exe.' }
$runtimeFiles = [ordered]@{}
Get-ChildItem -LiteralPath $runtimeDir -File -Filter '*.dll' | Sort-Object Name | ForEach-Object {
    $runtimeFiles[$_.Name] = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
}
$config = [ordered]@{
    enabled = $true
    executable = $exe
    executableSha256 = (Get-FileHash -LiteralPath $exe -Algorithm SHA256).Hash.ToLowerInvariant()
    runtimeFiles = $runtimeFiles
    model = $model
    modelSha256 = $manifest.model.sha256
    modelName = $manifest.model.name
    gpuLayers = 99
    contextSize = 4096
    reasoningTokens = $reasoningProperty.Value
    threads = 4
    timeoutSeconds = 45
    startupSeconds = 90
}
$parent = Split-Path -Parent $configFullPath
New-Item -ItemType Directory -Force -Path $parent | Out-Null
[IO.File]::WriteAllText($configFullPath, ($config | ConvertTo-Json -Depth 5), [Text.UTF8Encoding]::new($false))
Write-Host "Verified local runtime configured: $configFullPath"
Write-Host 'No model process was launched. No production configuration was changed unless explicitly chosen with -ConfigPath.'
