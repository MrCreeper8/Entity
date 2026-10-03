# Entity third-party binary notices

Original Entity code and content are all rights reserved under the Entity
Personal-Use License. That license does not replace, limit or remove any rights
granted by the third-party licenses listed here. Entity is not an open-source
project; the dependencies retain their own licenses.

## What the Windows package contains

The Windows x64 package includes Entity's client/plugin jars, the Entity desktop
application and the following desktop libraries. Complete license text and
publisher third-party notices are in the adjacent `licenses` folder. Do not
separate that folder or this notice from copies of the application.

| Component | Version | License and attribution |
| --- | --- | --- |
| CmlLib.Core | 4.0.6 | MIT; AlphaBs / 권세인 |
| CmlLib.Core.Commons | 4.0.0 | MIT; CmlLib / AlphaBs |
| LZMA-SDK | 19.0.0 | MIT NuGet packaging, Mihir Mone; original SDK, Igor Pavlov |
| SharpZipLib | 1.4.2 | MIT; SharpZipLib Contributors |
| System.Security.Cryptography.ProtectedData | 8.0.0 | MIT; Microsoft / .NET Foundation and Contributors |
| System.Management | 8.0.0 | MIT; Microsoft / .NET Foundation and Contributors |
| System.CodeDom | 8.0.0 | MIT; Microsoft / .NET Foundation and Contributors |
| System.Text.Json | 8.0.5 | MIT; Microsoft / .NET Foundation and Contributors |
| System.Threading.Tasks.Dataflow | 8.0.1 | MIT; Microsoft / .NET Foundation and Contributors |
| .NET runtime and native host, Windows x64 | 8.0.31 | MIT and third-party grants retained in the full runtime notices |
| .NET Windows Desktop / Windows Forms runtime | 8.0.31 | MIT and third-party grants retained in the Windows Forms/WPF notices |

The .NET runtime and native host notices were extracted from the exact Microsoft
NuGet runtime packs and compared with the 8.0.31 publisher source. The NuGet
package copyright notices, immutable source commits, content hashes and required
notice files are recorded in `licenses/inventory.json` and
`licenses/runtime-pack-provenance.json`. The SDK's overall software license is
not substituted for these runtime licenses.

The client jar includes eleven Prefab designs, copyright (c) 2016 Brian Wuest,
under MIT. The full Prefab notice is both in `licenses/Prefab-MIT.txt` and inside
the client jar. Its resource `manifest.json` retains per-design provenance and
transformations. Owner-imported houses without redistribution permission are not
included.

## Publisher downloads, not bundled release assets

Minecraft, its assets/libraries and Java, Fabric Loader 0.16.14, Fabric API
0.129.0+1.21.8, Baritone API Fabric 1.15.0 and Paper are downloaded separately
from their publishers when required. Optional AI runtime/model files are also
separate downloads. They are not inside the Entity installer/portable archive;
their downloaded archives and embedded notices remain unchanged. Minecraft's
EULA must be accepted by the user. Entity does not grant rights to another
publisher's game, runtime, mod, asset or model.

Fabric API is Apache-2.0, credited to FabricMC. Its full license is included as
`licenses/Fabric-API-0.129.0+1.21.8-LICENSE.txt`. The exact unmodified archive
contains 41 nested Fabric modules; their versions, declared licenses and hashes
are recorded in `licenses/downloaded-mod-inventory.json`. Apache-2.0 notices in
that archive are preserved. [Fabric API source](https://github.com/FabricMC/fabric)
and [publisher artifact](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.129.0%2B1.21.8/fabric-api-0.129.0%2B1.21.8.jar).

Baritone is a separate LGPL-covered library used by Entity, made by the Baritone
contributors (including cabaletta, leijurv and Brady). Its source headers permit
LGPL version 3 or later; the jar declares LGPL-3.0. The complete LGPL version 3
and incorporated GPL version 3 are included as
`licenses/Baritone-1.15.0-LGPL-3.0.txt` and `licenses/GPL-3.0.txt`.
[Exact 1.15.0 source](https://github.com/cabaletta/baritone/tree/612a8a6dc31edd13fa35123aa422e0cdca5c3389)
and [publisher release](https://github.com/cabaletta/baritone/releases/tag/v1.15.0).

## Replacing Baritone

Entity uses Baritone as a separate runtime-loaded Fabric jar, not code merged
into Entity's jar. You may build and use an interface-compatible modified
version, recombine/relink Entity with it, and reverse-engineer Entity when needed
to debug those modifications. The Entity Personal-Use License expressly
preserves those rights. No publisher hash pin or signing key is a restriction on
this supported replacement path.

1. Stop Entity's Minecraft client.
2. Build an interface-compatible Fabric Baritone jar for Minecraft 1.21.8 using
   the [pinned source](https://github.com/cabaletta/baritone/tree/612a8a6dc31edd13fa35123aa422e0cdca5c3389)
   and its Gradle wrapper/build scripts. Retain the modified library's notices.
3. Place your replacement at `baritone-override.jar` inside Entity's chosen data
   folder (default `%LOCALAPPDATA%\Entity\data`). Deliberately placing that file
   is the explicit selection of the user-supplied library;
   the publisher checksum applies only to the standard publisher download.
4. Restart Entity. The launcher uses exactly one selected Baritone jar and does
   not silently overwrite an enabled replacement. A modification still has to
   preserve the linked Baritone interfaces and Fabric/Minecraft compatibility.
5. To return to the publisher version, move your override out of that exact path
   and restart. Entity retains the normal publisher download separately.

These instructions are the installation/replacement mechanism; they are not a
promise that arbitrary incompatible jars will work. The replacement's path and
hash may be recorded locally for diagnostics, but the standard hash is not
required for an explicit user replacement.

## Source availability and distribution boundary

Entity's release does not convey the Baritone object-code jar: the application
fetches it directly from the publisher. The source links above identify the
exact upstream revision, but are **not represented as an Entity written source
offer** for redistributed Baritone binaries. If a future Entity release bundles
Baritone, its release gate must provide the matching corresponding source,
build/installation materials and applicable license texts by a GPL/LGPL-compliant
method (for example, an exact source asset beside the binary with equivalent
download access), and retain a functioning interface-compatible replacement
mechanism. A repository hyperlink alone is not that packaging gate.

The publisher Baritone jar also embeds nether-pathfinder 1.4.1. Its exact POM,
jar and source revision `b3fcce3e9fcadc0ae00a2a87494746606e68c95b` do not state a
redistribution license for that library. This is not assigned LGPL merely because
it is nested in Baritone. Entity therefore does not redistribute those bytes.
Bundling them later requires an independently verified grant and their nested
native dependency notices/source obligations. User-side publisher downloading
and Entity redistributing a binary are different operations.

## Packaging instructions

Copy this document and **every** file under `release/licenses` into the installer
and portable application root as `BINARY-NOTICES.md` and `licenses/`. Preserve
license text verbatim (line-ending normalization only). Retain the Prefab notice
inside the client jar. Link these notices from Help/About; if the app displays
Entity copyright during execution, also display Baritone's contributor credit
and the LGPL/GPL notice location there.

The package builder must verify the pinned inventory against the resolved
desktop dependencies and .NET 8.0.31. It must fail rather than silently retain
an old notice set after a dependency/runtime update. Do not add downloaded
Minecraft/Java/Paper/Fabric/Baritone/model/native caches to either archive.
