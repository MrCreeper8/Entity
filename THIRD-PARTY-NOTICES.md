# Third-party notices and redistribution gates

These notices describe source inputs, not a completed binary license audit.
Original Entity code is all rights reserved under [LICENSE](LICENSE), with
personal download/build/install/play permission. Entity is not open source.
Third-party rights, including LGPL modification/replacement/relinking and debugging
rights, remain governed by their own licenses. No license for another author's
work is invented by this document.

## Included content

The eleven **Prefab** designs and originals are under MIT, copyright (c) 2016
Brian Wuest. Their full permission notice is included at
`entity-client/src/main/resources/entity2/blueprints/prefab/LICENSE.txt` and must
stay with source and binary copies. The pinned enclosing upstream license is
[Forge/License.txt](https://github.com/Brian-Wuest/MC-Prefab/blob/aa5386c78bbb57a8e183caa7468fdc4e5319e581/Forge/License.txt),
SHA-256 `4d580922b445264fcb96298721eb9283c86b2387e9bfcccceb95ae268bb0cfdc`.
The unrelated NeoForge template-root license is not the applicable asset grant.
Per-asset provenance and transformations are in the bundled `manifest.json`.
Owner-downloaded houses with no verified redistribution permission are excluded.

## Dependencies downloaded separately

- **Baritone 1.15.0** uses [LGPLv3](https://github.com/cabaletta/baritone/blob/v1.15.0/LICENSE).
  Binary distribution needs the exact applicable LGPL/GPL texts and notices,
  source/relinking obligations and a documented way to use an interface-compatible
  modified library. Preserve Baritone as a replaceable separate jar. Pinning its
  hash for download integrity must not be used to prohibit user modifications.
- **CmlLib.Core** is MIT, copyright (c) 2020 권세인(AlphaBs), as recorded in
  its [upstream license](https://github.com/CmlLib/CmlLib.Core/blob/master/LICENSE).
  The exact desktop package version's full license and transitive notices must
  accompany distributed DLLs, not merely this hyperlink.
- **Fabric Loader, Fabric API, Yarn, Sponge Mixin and MixinExtras** retain their
  own upstream notices. The binary package inventory must identify exact versions,
  nested jars, licenses and any copied/modified upstream implementation code.
- **Paper**, **Minecraft**, **Java**, **.NET** and native runtime components are
  separate publishers' software. Do not commit game binaries/assets or package
  their caches. Verify distribution rights, exact notices and download terms for
  anything actually included in the installer; user-side downloading is separate
  from bundling. Users must accept the Minecraft EULA themselves.
- Optional **llama.cpp** is MIT. The pinned model manifest identifies Apache-2.0
  upstream/model publisher metadata; model and CUDA files are not included here.
  Before bundling any model/runtime/native DLL, verify that exact archive's grant,
  attribution/NOTICE and NVIDIA redistributable conditions. A manifest's license
  string alone is not proof that all extracted CUDA dependencies may be repackaged.

## Specific publication blockers

The source snapshot may be reviewed without redistributing dependency binaries.
A downloadable final installer must not be advertised as license-complete until:
exact binary/transitive inventories and required license texts are packaged;
Baritone modified-library/relinking requirements are
satisfied; and optional CUDA/model archive grants are verified if those bytes are
bundled. Any unknown content grant is a blocker for that content, not permission
to distribute it. These gates are separate from clean export or compilation.
