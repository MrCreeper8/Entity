[CmdletBinding()]
param(
    [string]$RepositoryRoot = '',
    [string]$VersionSource = '',
    [string]$OutputPath = '',
    [string]$BuildId = '',
    [switch]$Force,
    [switch]$RequireClean
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if (-not $RepositoryRoot) {
    $RepositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
}
Import-Module (Join-Path $PSScriptRoot 'Entity2.BuildIdentity.psm1') -Force
$parameters = @{
    RepositoryRoot = $RepositoryRoot
    BuildId = $BuildId
    Force = $Force
    RequireClean = $RequireClean
}
if ($VersionSource) { $parameters.VersionSource = $VersionSource }
if ($OutputPath) { $parameters.OutputPath = $OutputPath }
$identity = New-Entity2BuildIdentity @parameters
$identity | ConvertTo-Json -Depth 4
