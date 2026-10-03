[CmdletBinding()]
param(
    [string]$DotnetExecutable = 'dotnet',
    [string]$GradleExecutable = 'gradle',
    [Parameter(Mandatory)][string]$OutputDirectory,
    [switch]$SkipJavaBuild
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($PSVersionTable.PSVersion.Major -lt 7 -or -not $IsWindows) { throw 'Windows x64 and PowerShell 7 are required.' }
$root = [IO.Path]::GetFullPath($PSScriptRoot).TrimEnd('\','/')
$runtimeVersion = '8.0.31'

function Assert-NoReparseAncestor([string]$Path) {
    $cursor = $Path
    while ($cursor) {
        if ((Test-Path -LiteralPath $cursor) -and ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "Packaging paths may not traverse a junction/symlink: $cursor"
        }
        $parent = Split-Path -Parent $cursor
        if ($parent -eq $cursor) { break }
        $cursor = $parent
    }
}
function Write-Json([string]$Path, $Value) {
    [IO.File]::WriteAllText($Path, ($Value | ConvertTo-Json -Depth 12), [Text.UTF8Encoding]::new($false))
}
function Get-Hash([string]$Path) { return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Get-RelativeFiles([string]$Directory) {
    return @(Get-ChildItem -LiteralPath $Directory -File -Recurse | ForEach-Object {
        Assert-NoReparseAncestor $_.FullName
        [ordered]@{ path = [IO.Path]::GetRelativePath($Directory, $_.FullName).Replace('\','/'); sha256 = Get-Hash $_.FullName }
    } | Sort-Object { $_.path })
}
function Assert-LicenseInputs {
    foreach ($required in @('LICENSE','THIRD-PARTY-NOTICES.md','BINARY-NOTICES.md','Copy-BinaryNotices.ps1','Verify-BinaryNotices.ps1','licenses/inventory.json','licenses/SHA256SUMS',
            'licenses/runtime-pack-provenance.json','licenses/DotnetRuntime-8.0.31-LICENSE.txt',
            'licenses/DotnetRuntime-8.0.31-THIRD-PARTY-NOTICES.txt','licenses/WindowsForms-8.0.31-LICENSE.txt',
            'licenses/WindowsForms-8.0.31-THIRD-PARTY-NOTICES.txt','licenses/WPF-8.0.31-LICENSE.txt',
            'licenses/WPF-8.0.31-THIRD-PARTY-NOTICES.txt')) {
        $path = Join-Path $root $required
        Assert-NoReparseAncestor $path
        if (-not (Test-Path -LiteralPath $path -PathType Leaf) -or (Get-Item -LiteralPath $path).Length -eq 0) {
            throw "Required binary license input missing: $required. Integrate/export the full notices; placeholders are not accepted."
        }
    }
    $pins = @{}
    foreach ($line in Get-Content -LiteralPath (Join-Path $root 'licenses/SHA256SUMS')) {
        if ($line -notmatch '^([0-9a-fA-F]{64})  ([A-Za-z0-9_.+/-]+)$') { throw 'Invalid licenses/SHA256SUMS entry.' }
        $name = $Matches[2]
        if ($name -match '(^|/)\.\.?(/|$)' -or $pins.ContainsKey($name)) { throw 'Unsafe/duplicate license pin.' }
        $pins[$name] = $Matches[1]
    }
    foreach ($file in Get-ChildItem -LiteralPath (Join-Path $root 'licenses') -File -Recurse) {
        Assert-NoReparseAncestor $file.FullName
        $name = [IO.Path]::GetRelativePath((Join-Path $root 'licenses'), $file.FullName).Replace('\','/')
        if ($name -eq 'SHA256SUMS') { continue }
        if (-not $pins.ContainsKey($name) -or (Get-Hash $file.FullName) -ine $pins[$name]) { throw "Unpinned/mismatched license input: $name" }
    }
    foreach ($name in $pins.Keys) {
        if (-not (Test-Path -LiteralPath (Join-Path $root "licenses/$name") -PathType Leaf)) { throw "Pinned license input missing: $name" }
    }
    $inventory = Get-Content -LiteralPath (Join-Path $root 'licenses/inventory.json') -Raw | ConvertFrom-Json
    if ($inventory.schema -ne 1 -or $inventory.platform -ne 'win-x64' -or $inventory.runtimeVersion -ne $runtimeVersion -or
            $inventory.productVersion -ne $version) { throw 'Binary license inventory is for a different product/runtime.' }
    $lock = Get-Content -LiteralPath (Join-Path $root 'desktop/Entity.Desktop/packages.lock.json') -Raw | ConvertFrom-Json -AsHashtable
    foreach ($framework in $lock.dependencies.Values) {
        foreach ($dependency in $framework.GetEnumerator()) {
            $entry = @($inventory.nugetPackages | Where-Object { $_.id -ieq $dependency.Key -and $_.version -eq $dependency.Value.resolved })
            if ($entry.Count -ne 1 -or $entry[0].nugetContentHash -cne $dependency.Value.contentHash) {
                throw "Desktop dependency has no matching binary license inventory: $($dependency.Key)"
            }
        }
    }
    foreach ($name in @($inventory.runtimeLicenseFiles) + @($inventory.nugetPackages | ForEach-Object licenseFiles) + @($inventory.downloadOnly | ForEach-Object licenseFiles)) {
        if (-not $pins.ContainsKey($name)) { throw "Inventory license is not pinned: $name" }
    }
}
function Assert-PublicJar([string]$Path, [bool]$Client) {
    $archive = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        foreach ($entry in $archive.Entries) {
            if ($entry.FullName -match '(^|/)(Integration[^/]*|[^/]*(Harness|Fixture|Test))\.class$|OwnerCultivationBlockAckMixin') {
                throw "Private test class survived public build: $($entry.FullName)"
            }
            if ($entry.FullName -match '^META-INF/jars/[^/]+\.jar$') {
                $memory = [IO.MemoryStream]::new()
                $input = $entry.Open()
                try {
                    $input.CopyTo($memory); $memory.Position = 0
                    $nested = [IO.Compression.ZipArchive]::new($memory, [IO.Compression.ZipArchiveMode]::Read, $true)
                    try {
                        if (@($nested.Entries | Where-Object FullName -match '(^|/)(Integration[^/]*|[^/]*(Harness|Fixture|Test))\.class$').Count) {
                            throw 'Private test class survived nested public build.'
                        }
                    } finally { $nested.Dispose() }
                } finally { $input.Dispose(); $memory.Dispose() }
            }
        }
        if ($Client) {
            $selectors = [ordered]@{
                MinecraftClientMixin = @('method_1590(Z)V','method_1536()Z','method_1583()V')
                ClientPlayerInteractionManagerMixin = @('method_2910(Lnet/minecraft/class_2338;Lnet/minecraft/class_2350;)Z',
                    'method_2902(Lnet/minecraft/class_2338;Lnet/minecraft/class_2350;)Z','method_2899(Lnet/minecraft/class_2338;)Z')
                ClientPlayerInputMixin = @('Lnet/minecraft/class_742;method_5773()V','Lnet/minecraft/class_742;method_6007()V',
                    'Lnet/minecraft/class_744;method_3129()V','method_48300')
                BlockItemPlacementInvoker = @('method_7707','net/minecraft/class_1747')
                BaritonePropertyCostMixin = @('a(IIILnet/minecraft/class_2680;)D','b(IIILnet/minecraft/class_2680;)D')
                BaritoneBuilderMaterialsMixin = @('a(Lnet/minecraft/class_2680;Lnet/minecraft/class_2680;Z)Z')
            }
            foreach ($rule in $selectors.GetEnumerator()) {
                $entry = $archive.GetEntry("dev/entity/client/mixin/$($rule.Key).class")
                if ($null -eq $entry) { throw "Required mapped mixin missing: $($rule.Key)" }
                $reader = [IO.StreamReader]::new($entry.Open())
                try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }
                foreach ($selector in $rule.Value) {
                    if (-not $text.Contains($selector)) { throw "Required mapped selector missing: $($rule.Key) $selector" }
                }
            }
        }
    } finally { $archive.Dispose() }
}
function Publish-Project([string]$Project, [string]$Destination, [bool]$SingleFile) {
    $arguments = @('publish',$Project,'-c','Release','-r','win-x64','--self-contained','true','--output',$Destination,
        "-p:RuntimeFrameworkVersion=$runtimeVersion",'-p:TargetLatestRuntimePatch=false','-p:DebugType=None',
        '-p:DebugSymbols=false','-p:GenerateDocumentationFile=false','-p:ContinuousIntegrationBuild=true',
        '-p:Deterministic=true',"-p:PathMap=$root=/_/entity",'-p:PublishTrimmed=false',"-p:PublishSingleFile=$($SingleFile.ToString().ToLowerInvariant())")
    if ($SingleFile) { $arguments += '-p:IncludeNativeLibrariesForSelfExtract=true' }
    else { $arguments += '-p:RestoreLockedMode=true' }
    & $DotnetExecutable @arguments
    if ($LASTEXITCODE -ne 0) { throw "Windows publish failed: $Project" }
    foreach ($file in Get-ChildItem -LiteralPath $Destination -File -Recurse | Where-Object Extension -in @('.pdb','.xml')) {
        # Only generated publish output under the explicitly NEW owned root.
        Assert-NoReparseAncestor $file.FullName
        if (-not $file.FullName.StartsWith($output + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Publish cleanup escaped owned output.' }
        Remove-Item -LiteralPath $file.FullName
    }
}

if (-not [IO.Path]::IsPathRooted($OutputDirectory)) { throw 'OutputDirectory must be an absolute NEW directory.' }
$output = [IO.Path]::GetFullPath($OutputDirectory).TrimEnd('\','/')
if ((Test-Path -LiteralPath $output) -or $output -eq [IO.Path]::GetPathRoot($output).TrimEnd('\','/') -or
        $output.Equals($root, [StringComparison]::OrdinalIgnoreCase) -or
        $output.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
        $root.StartsWith($output + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'OutputDirectory must be NEW and disjoint from source. Existing files/data are never merged or replaced.'
}
Assert-NoReparseAncestor $root
Assert-NoReparseAncestor $output
if (-not (Test-Path -LiteralPath (Join-Path $root 'PUBLIC-SOURCE.json') -PathType Leaf)) { throw 'Run this script from the reviewed public source export, not the private development tree.' }
$export = Get-Content -LiteralPath (Join-Path $root 'PUBLIC-SOURCE.json') -Raw | ConvertFrom-Json
if ($export.sourceState -ne 'clean' -or $export.historyCopied -or $export.binariesIncluded) { throw 'A clean public source export is required.' }
Import-Module (Join-Path $root 'build-identity/Entity2.BuildIdentity.psm1') -Force
$version = Get-Entity2Version -VersionSource (Join-Path $root 'build-identity/version.properties')
Assert-LicenseInputs
if (-not (Get-Command $DotnetExecutable -ErrorAction SilentlyContinue)) { throw 'Provide a .NET SDK executable (not only the desktop runtime).' }
$sdk = (& $DotnetExecutable --version | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $sdk -notmatch '^(?:[89]|[1-9][0-9]+)\.') { throw 'A .NET 8 or newer SDK is required; runtime publishing is pinned to 8.0.31.' }
foreach ($project in @('Entity.Desktop','Entity.Setup')) {
    [xml]$definition = Get-Content -LiteralPath (Join-Path $root "desktop/$project/$project.csproj") -Raw
    if ($definition.Project.PropertyGroup.Version -ne $version -or $definition.Project.PropertyGroup.RuntimeFrameworkVersion -ne $runtimeVersion) {
        throw "Desktop/setup source version or runtime differs from the pair: $project"
    }
}
$setupSource = Get-Content -LiteralPath (Join-Path $root 'desktop/Entity.Setup/Installation.cs') -Raw
if ($setupSource -notmatch ('public const string Version = "' + [regex]::Escape($version) + '";')) { throw 'Immutable installer version differs from the Java pair.' }
if (-not $SkipJavaBuild) { & (Join-Path $root 'Build-Source.ps1') -GradleExecutable $GradleExecutable }
$commit = (& git -c "safe.directory=$root" -C $root rev-parse HEAD | Out-String).Trim().ToLowerInvariant()
if ($LASTEXITCODE -ne 0 -or $commit -notmatch '^[0-9a-f]{40}$' -or (Get-Entity2SourceState -RepositoryRoot $root) -ne 'clean') { throw 'Packaging requires a clean committed public checkout with a full SHA-1 source identity.' }
$identity = Read-Entity2BuildIdentity -Path (Join-Path $root 'build/identity/entity2-build-identity.properties')
if ($identity.sourceCommit -cne $commit -or $identity.version -cne $version -or $identity.sourceState -cne 'clean') { throw 'Existing Java identity is stale/different. Build the exact clean public source pair first.' }
$client = Join-Path $root "entity-client/build/libs/entity2-client-$version.jar"
$plugin = Join-Path $root "server-plugin/build/libs/EntityBridge-v2-$version.jar"
Add-Type -AssemblyName System.IO.Compression.FileSystem
foreach ($jar in @($client,$plugin)) { Assert-Entity2BuildIdentityMatch -Expected $identity -Actual (Read-Entity2JarBuildIdentity -Path $jar) -ActualLabel $jar }
Assert-PublicJar $client $true
Assert-PublicJar $plugin $false
Assert-Entity2JarResource -Path $client -EntryName 'entity2/blueprints/prefab/LICENSE.txt' -SourcePath (Join-Path $root 'entity-client/src/main/resources/entity2/blueprints/prefab/LICENSE.txt')
Assert-Entity2JarResource -Path $plugin -EntryName 'entity-companion-owner.txt' -SourcePath (Join-Path $root 'server-plugin/src/main/resources/entity-companion-owner.txt')
$bundle = Join-Path $root 'desktop/Entity.Setup/bundle.zip'
Assert-NoReparseAncestor $bundle
& git -c "safe.directory=$root" -C $root check-ignore --quiet -- desktop/Entity.Setup/bundle.zip
if ($LASTEXITCODE -ne 0) { throw 'Setup bundle.zip must be ignored by the public checkout before packaging.' }

[IO.Directory]::CreateDirectory($output) | Out-Null
$app = Join-Path $output 'portable'
Publish-Project (Join-Path $root 'desktop/Entity.Desktop/Entity.Desktop.csproj') $app $false
if (-not (Test-Path -LiteralPath (Join-Path $app 'Entity.exe') -PathType Leaf) -or
        -not (Test-Path -LiteralPath (Join-Path $app 'coreclr.dll') -PathType Leaf)) { throw 'Desktop publish did not produce a self-contained Windows application.' }
$runtime = Get-Content -LiteralPath (Join-Path $app 'Entity.runtimeconfig.json') -Raw | ConvertFrom-Json
if (@($runtime.runtimeOptions.includedFrameworks | Where-Object version -ne $runtimeVersion).Count -or
        @($runtime.runtimeOptions.includedFrameworks).Count -ne 2) { throw 'Desktop publish runtime does not match the licensed 8.0.31 .NET/WindowsDesktop pair.' }
foreach ($name in @('LICENSE','README.md','THIRD-PARTY-NOTICES.md','docs')) {
    Copy-Item -LiteralPath (Join-Path $root $name) -Destination (Join-Path $app $name) -Recurse
}
& (Join-Path $root 'Copy-BinaryNotices.ps1') -DestinationDirectory $app `
    -ProjectAssetsPath (Join-Path $root 'desktop/Entity.Desktop/obj/project.assets.json') -PublishedDirectory $app | Out-Null
[IO.Directory]::CreateDirectory((Join-Path $app 'local-ai')) | Out-Null
Copy-Item -LiteralPath (Join-Path $root 'local-ai/pinned-runtime.json') -Destination (Join-Path $app 'local-ai/pinned-runtime.json')
$payload = Join-Path $app 'payload'
[IO.Directory]::CreateDirectory($payload) | Out-Null
foreach ($jar in @($client,$plugin)) { Copy-Item -LiteralPath $jar -Destination (Join-Path $payload ([IO.Path]::GetFileName($jar))) }
$downloads = @(
    [ordered]@{ role = 'fabric-api'; file = 'fabric-api-0.129.0+1.21.8.jar'; sha256 = 'fe31a897f9886011914e3e8c292a2774d8427fe0cb2e3611cb8ce18289c612a7'; downloadUrl = 'https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.129.0%2B1.21.8/fabric-api-0.129.0%2B1.21.8.jar' },
    [ordered]@{ role = 'baritone'; file = 'baritone-api-fabric-1.15.0.jar'; sha256 = 'c58ef35a133b6ffce96a74682138ac2ee818cbc063b7c62671db9f9d7d783ebb'; downloadUrl = 'https://github.com/cabaletta/baritone/releases/download/v1.15.0/baritone-api-fabric-1.15.0.jar' }
)
$inventory = Get-Content -LiteralPath (Join-Path $root 'licenses/inventory.json') -Raw | ConvertFrom-Json
foreach ($pin in $downloads) {
    $notice = @($inventory.downloadOnly | Where-Object id -eq $pin.role)
    if ($notice.Count -ne 1 -or $notice[0].sha256 -ine $pin.sha256 -or $notice[0].sourceUrl -cne $pin.downloadUrl) { throw "Publisher download pin differs from license inventory: $($pin.role)" }
}
$productFiles = @([ordered]@{ role = 'client'; file = [IO.Path]::GetFileName($client); sha256 = Get-Hash $client },
    [ordered]@{ role = 'server-plugin'; file = [IO.Path]::GetFileName($plugin); sha256 = Get-Hash $plugin }) + $downloads
Write-Json (Join-Path $payload 'product.json') ([ordered]@{ version = $version; sourceCommit = $commit; files = $productFiles })
# No models, CUDA archives, game files, credentials, PDBs or build/test inputs are copied.
$files = Get-RelativeFiles $app
foreach ($file in $files) {
    if ($file.path -match '(?i)(^|/)(\.git|\.codex|\.agents|tests?|fixtures?|harness|worlds?|saves|accounts\.json|settings\.json|credentials\.json|secrets\.json)(/|$)|\.(pdb|gguf|schematic)$' -or
            ($file.path -match '\.jar$' -and $file.path -notin @("payload/$([IO.Path]::GetFileName($client))","payload/$([IO.Path]::GetFileName($plugin))"))) {
        throw "Unexpected private/generated/third-party binary in portable output: $($file.path)"
    }
    if ($file.path -match '\.(exe|dll|json|md|txt|yml|ps1)$') {
        $bytes = [IO.File]::ReadAllBytes((Join-Path $app $file.path))
        if (([Text.Encoding]::UTF8.GetString($bytes) + [Text.Encoding]::Unicode.GetString($bytes)) -match '(?i)[A-Z]:[\\/](?:Users|Documents and Settings)[\\/][^\s"''<>]+') {
            throw "Private absolute user path in published output: $($file.path)"
        }
    }
}
Write-Json (Join-Path $app 'PACKAGE.json') ([ordered]@{ version = $version; sourceCommit = $commit; files = $files })
$portable = Join-Path $output "Entity-$version-win-x64-portable.zip"
[IO.Compression.ZipFile]::CreateFromDirectory($app, $portable, [IO.Compression.CompressionLevel]::Optimal, $false)
Copy-Item -LiteralPath $portable -Destination $bundle -Force
$setupOutput = Join-Path $output 'setup-publish'
Publish-Project (Join-Path $root 'desktop/Entity.Setup/Entity.Setup.csproj') $setupOutput $true
$setupExe = Join-Path $setupOutput 'Entity-Setup.exe'
if (-not (Test-Path -LiteralPath $setupExe -PathType Leaf) -or @(Get-ChildItem -LiteralPath $setupOutput -File -Recurse | Where-Object Extension -in @('.dll','.json')).Count) {
    throw 'Installer publish was not a self-contained single executable.'
}
$installer = Join-Path $output "Entity-Setup-$version-win-x64.exe"
Copy-Item -LiteralPath $setupExe -Destination $installer
if ((Get-Entity2SourceState -RepositoryRoot $root) -ne 'clean') { throw 'Packaging changed tracked/public source; no final manifest was produced.' }
$artifacts = @([ordered]@{ path = [IO.Path]::GetFileName($portable); sha256 = Get-Hash $portable },
    [ordered]@{ path = [IO.Path]::GetFileName($installer); sha256 = Get-Hash $installer })
Write-Json (Join-Path $output 'release-manifest.json') ([ordered]@{
    version = $version; sourceCommit = $commit; buildId = $identity.buildId; runtimeVersion = $runtimeVersion; platform = 'win-x64'
    builtAtUtc = [DateTimeOffset]::UtcNow.ToString('o'); javaBuildSkipped = [bool]$SkipJavaBuild
    productFiles = $productFiles; files = $artifacts; acceptanceVerified = $false; installed = $false; published = $false
})
$sums = @($artifacts) + @([ordered]@{ path = 'release-manifest.json'; sha256 = Get-Hash (Join-Path $output 'release-manifest.json') })
[IO.File]::WriteAllLines((Join-Path $output 'SHA256SUMS'), @($sums | ForEach-Object { "$($_.sha256)  $($_.path)" }), [Text.UTF8Encoding]::new($false))
Write-Host "Packaged $portable and $installer. No installation, Minecraft/UI acceptance or publication was performed."
