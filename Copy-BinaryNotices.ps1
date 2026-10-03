[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$DestinationDirectory,
    [string]$ProjectAssetsPath,
    [string]$PublishedDirectory
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$destination = [IO.Path]::GetFullPath($DestinationDirectory)
if (-not [IO.Path]::IsPathRooted($DestinationDirectory)) { throw 'Choose an absolute package destination.' }
$cursor = $destination
while ($cursor) {
    if ((Test-Path -LiteralPath $cursor) -and
        ((Get-Item -LiteralPath $cursor).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw 'Notice output may not traverse a junction or symbolic link.'
    }
    $parent = Split-Path -Parent $cursor
    if ($parent -eq $cursor) { break }
    $cursor = $parent
}
$arguments = @{ NoticeDirectory = (Join-Path $PSScriptRoot 'licenses') }
if ($ProjectAssetsPath) { $arguments.ProjectAssetsPath = $ProjectAssetsPath }
if ($PublishedDirectory) { $arguments.PublishedDirectory = $PublishedDirectory }
& (Join-Path $PSScriptRoot 'Verify-BinaryNotices.ps1') @arguments | Out-Null
New-Item -ItemType Directory -Path $destination -Force | Out-Null
$noticeOutput = Join-Path $destination 'licenses'
if ((Test-Path -LiteralPath $noticeOutput) -and
    ((Get-Item -LiteralPath $noticeOutput).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
    throw 'License output may not be a junction or symbolic link.'
}
New-Item -ItemType Directory -Path $noticeOutput -Force | Out-Null
foreach ($file in Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'licenses') -File) {
    $target = Join-Path $noticeOutput $file.Name
    if ((Test-Path -LiteralPath $target) -and
        ((Get-Item -LiteralPath $target).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw 'License file output may not be a symbolic link.'
    }
    Copy-Item -LiteralPath $file.FullName -Destination $target -Force
}
$document = Join-Path $destination 'BINARY-NOTICES.md'
if ((Test-Path -LiteralPath $document) -and
    ((Get-Item -LiteralPath $document).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
    throw 'Notice document output may not be a symbolic link.'
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'BINARY-NOTICES.md') -Destination $document -Force
& (Join-Path $PSScriptRoot 'Verify-BinaryNotices.ps1') -NoticeDirectory $noticeOutput | Out-Null
Write-Output 'Complete binary notices copied and checksum-verified.'
