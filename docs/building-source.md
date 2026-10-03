# Build and pair from source

Use Windows x64, PowerShell 7, Git, JDK 21 and Gradle **8.14.3**. The standalone
desktop package requires a .NET 8 or newer SDK; self-contained Windows x64
publishing is pinned to .NET/Windows Desktop **8.0.31**. The build downloads
dependencies from their publishers; it does not use the original developer's
Minecraft caches or private harness. No game, Paper server, model, DLL or jar is
included in this source repository.

The exporter does not initialize Git. The release maintainer creates a new
repository/history only after reviewing the clean snapshot. A source archive
without Git must be initialized and committed locally before generating a build
identity. A public repository clone already has the required history.

From a clean committed checkout:

```powershell
pwsh -File ./Build-Source.ps1 -GradleExecutable <path-to-gradle.bat>
```

## Windows portable package and installer

```powershell
pwsh -File ./Build-Windows.ps1 `
  -DotnetExecutable <path-to-dotnet.exe> `
  -GradleExecutable <path-to-gradle.bat> `
  -OutputDirectory <absolute-new-folder-outside-source>
```

The default builds the public Java pair once, publishes the self-contained app,
and packages an immutable-version self-contained installer. `-SkipJavaBuild`
reuses a pair only after rechecking current clean source/version/build identity,
mapped selectors and required resources. Output must be NEW and disjoint from
source; existing directories and junction/symlink paths are refused. Outputs are
`portable/`, `Entity-<version>-win-x64-portable.zip`,
`Entity-Setup-<version>-win-x64.exe`, `release-manifest.json` and `SHA256SUMS`.
`PACKAGE.json` hashes every installed app file except itself. Installer
`bundle.zip` is ignored in source. The script never installs, launches or publishes.

Only Entity client/plugin jars are local payloads. Fabric API and Baritone remain
pinned publisher URL/hash downloads, not bundled jars. Optional AI pins are
included; models, CUDA/game files, credentials and private build evidence are not.
Publish output strips PDB/XML documentation and maps compiler paths away from the
source root. Full `BINARY-NOTICES.md`/`licenses` (including exact .NET runtime/host
and Windows Desktop notices) are mandatory: missing or mismatched inputs fail
packaging, never become fabricated placeholders. Build/package output does not
claim install, gameplay or UI acceptance.

The Java build verifies the pinned Baritone download, builds the Fabric client and
Paper plugin with the same identity, then checks both jars. Desktop packaging is
a separate app release gate; building the Java pair does not install or run it.
The public client uses Fabric Loom rather than the private offline compiler.
The Paper API is a Maven snapshot: dependency resolution and a clean network
build still need verification before a reproducible public release is claimed.
To build against your own interface-compatible modified Baritone library, pass
`-BaritoneJar <absolute-path-to-your-jar>`; only automatic publisher downloads
are forced to match the publisher pin. Keep Baritone replaceable as a separate jar.

Public source is projected from the private product: six integration drivers,
their entrypoint hooks/test mixin, the private companion facts writer and a wall
canary are omitted. All ordinary gameplay owners remain. The exported source
must be built and its exact jars physically verified; private candidate binaries
are not made clean merely by exporting source. Byte-identical reproducibility is
not claimed across the two build pipelines.

## Manual local pairing

Install Paper 1.21.8 separately through its publisher, accept its EULA, and put
the built `EntityBridge-v2-<version>.jar` in that server's `plugins` directory.
Set EntityBridge's `owner-name` to the human's exact name and grant the documented
control permission (or configure explicit controller names). Keep its bridge at
`127.0.0.1`; do not expose that port to the Internet.

The server generates a secure bridge token when its configured token is empty.
Configure the dedicated Fabric 1.21.8 client with Fabric Loader 0.16.14, Fabric API
0.129.0+1.21.8, Baritone API Fabric 1.15.0 and the exact matching Entity client jar.
Copy the pairing token into this client's generated Entity configuration. Keep
the token private. Human clients do not need these bot mods.

This release supports only deliberately offline local Paper servers, with bot
name **Entity** and no account login option. Do not change an existing
`online-mode=true` server as a workaround. Human players can use vanilla 1.21.8.
The app provisions/pairs its own local server; exact install/runtime acceptance
remains documented in the release notes, not inferred from compilation.
