[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$GradleExecutable,
    [string]$BaritoneJar
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7 is required.' }
$root = [IO.Path]::GetFullPath($PSScriptRoot)
if (-not (Get-Command $GradleExecutable -ErrorAction SilentlyContinue)) { throw 'Provide the Gradle 8.14.3 executable path.' }
$gradleVersion = (& $GradleExecutable --version | Out-String)
if ($LASTEXITCODE -ne 0 -or $gradleVersion -notmatch 'Gradle 8\.14\.3\b') { throw 'This source build is pinned to Gradle 8.14.3.' }
$javaVersion = (& java --version | Out-String)
if ($LASTEXITCODE -ne 0 -or $javaVersion -notmatch '(?:openjdk|java)(?: version)?\s+"?21\.') { throw 'Select JDK 21 in JAVA_HOME/PATH.' }
& (Join-Path $root 'build-identity/Generate-Entity2BuildIdentity.ps1') -RepositoryRoot $root -RequireClean | Out-Null
$dependencyDirectory = Join-Path $root 'dependencies'
New-Item -ItemType Directory -Force -Path $dependencyDirectory | Out-Null
$baritone = Join-Path $dependencyDirectory 'baritone-api-fabric-1.15.0.jar'
$expected = 'c58ef35a133b6ffce96a74682138ac2ee818cbc063b7c62671db9f9d7d783ebb'
if ($BaritoneJar) {
    # LGPL-compatible user modification path. An explicitly selected, separate
    # interface-compatible library is not forced to match the publisher's pin.
    $baritone = [IO.Path]::GetFullPath($BaritoneJar)
    if (-not (Test-Path -LiteralPath $baritone -PathType Leaf)) { throw 'Explicit modified Baritone jar not found.' }
} elseif (-not (Test-Path -LiteralPath $baritone)) {
    $partial = "$baritone.partial"
    Invoke-WebRequest -Uri 'https://github.com/cabaletta/baritone/releases/download/v1.15.0/baritone-api-fabric-1.15.0.jar' -OutFile $partial
    if ((Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash -ine $expected) { throw 'Baritone publisher asset does not match the build pin; unverified download retained but not used.' }
    Move-Item -LiteralPath $partial -Destination $baritone
}
if (-not $BaritoneJar -and (Get-FileHash -LiteralPath $baritone -Algorithm SHA256).Hash -ine $expected) { throw 'Build dependency hash mismatch. Use -BaritoneJar to explicitly select an interface-compatible modified library.' }
foreach ($project in @('entity-client', 'server-plugin')) {
    & $GradleExecutable --project-dir (Join-Path $root $project) "-Pbaritone_jar=$baritone" clean build --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "$project build failed." }
}
$version = (Get-Content -LiteralPath (Join-Path $root 'build-identity/version.properties') | Where-Object { $_ -match '^version=' }) -replace '^version=', ''
Import-Module (Join-Path $root 'build-identity/Entity2.BuildIdentity.psm1') -Force
$identity = Read-Entity2BuildIdentity -Path (Join-Path $root 'build/identity/entity2-build-identity.properties')
$jars = @((Join-Path $root "entity-client/build/libs/entity2-client-$version.jar"),
    (Join-Path $root "server-plugin/build/libs/EntityBridge-v2-$version.jar"))
Add-Type -AssemblyName System.IO.Compression.FileSystem
$mixinSelectors = [ordered]@{
    'MinecraftClientMixin' = @('method_1590(Z)V','method_1536()Z','method_1583()V')
    'ClientPlayerInteractionManagerMixin' = @('method_2910(Lnet/minecraft/class_2338;Lnet/minecraft/class_2350;)Z',
        'method_2902(Lnet/minecraft/class_2338;Lnet/minecraft/class_2350;)Z','method_2899(Lnet/minecraft/class_2338;)Z')
    'ClientPlayerInputMixin' = @('Lnet/minecraft/class_742;method_5773()V','Lnet/minecraft/class_742;method_6007()V',
        'Lnet/minecraft/class_744;method_3129()V','method_48300')
    'BlockItemPlacementInvoker' = @('method_7707','net/minecraft/class_1747')
    'BaritonePropertyCostMixin' = @('a(IIILnet/minecraft/class_2680;)D','b(IIILnet/minecraft/class_2680;)D')
    'BaritoneBuilderMaterialsMixin' = @('a(Lnet/minecraft/class_2680;Lnet/minecraft/class_2680;Z)Z')
}
foreach ($jar in $jars) {
    Assert-Entity2BuildIdentityMatch -Expected $identity -Actual (Read-Entity2JarBuildIdentity -Path $jar) -ActualLabel $jar
    $archive = [IO.Compression.ZipFile]::OpenRead($jar)
    try {
        if (@($archive.Entries | Where-Object { $_.FullName -match '(^|/)(Integration[^/]*|[^/]*(Harness|Fixture|Test))\.class$|OwnerCultivationBlockAckMixin|entity2/blueprints/prefab/[^/]*owner' }).Count) {
            throw 'Built public jar contains a forbidden test/content entry.'
        }
        if ($jar -eq $jars[0]) {
            # Check the final remapped archive, not named compile outputs. This
            # protects the intentional intermediary selectors while proving the
            # ordinary named Minecraft mixins were remapped by TinyRemapper.
            foreach ($rule in $mixinSelectors.GetEnumerator()) {
                $entry = $archive.GetEntry("dev/entity/client/mixin/$($rule.Key).class")
                if ($null -eq $entry) { throw "Required runtime mixin missing: $($rule.Key)" }
                $stream = $entry.Open()
                $memory = [IO.MemoryStream]::new()
                try {
                    $stream.CopyTo($memory)
                    $classText = [Text.Encoding]::UTF8.GetString($memory.ToArray())
                } finally { $stream.Dispose(); $memory.Dispose() }
                foreach ($selector in $rule.Value) {
                    if (-not $classText.Contains($selector)) { throw "Final intermediary selector missing: $($rule.Key) $selector" }
                }
            }
            Write-Host 'Verified 15 final-archive Minecraft/Baritone runtime selector witnesses.'
        }
    } finally { $archive.Dispose() }
    Write-Host "Built $jar : $((Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash)"
}
Assert-Entity2JarResource -Path $jars[0] -EntryName 'entity2/blueprints/prefab/LICENSE.txt' `
    -SourcePath (Join-Path $root 'entity-client/src/main/resources/entity2/blueprints/prefab/LICENSE.txt')
Assert-Entity2JarResource -Path $jars[1] -EntryName 'entity-companion-owner.txt' `
    -SourcePath (Join-Path $root 'server-plugin/src/main/resources/entity-companion-owner.txt')
Write-Host 'Source pair built and identity checked. Installation, Minecraft live acceptance and desktop packaging have not been performed.'
