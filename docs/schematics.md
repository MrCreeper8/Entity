# Schematics and offline architecture

Eleven bundled Prefab designs are by Brian Wuest and Prefab contributors, not
Entity-authored sample houses. They are distributed under the enclosed MIT
license, copyright **2016 Brian Wuest**. The original license, source asset
URLs/hashes and converted-file hashes are preserved under
`entity-client/src/main/resources/entity2/blueprints/prefab/`.
Source revision: `aa5386c78bbb57a8e183caa7468fdc4e5319e581` of
[MC-Prefab](https://github.com/Brian-Wuest/MC-Prefab).

The pack includes Desert, Hobbit, Snow and Tree houses; Large Barn; Watch Tower
and Dark Watch Tower; Mine Entrance; Small Bridge; Chicken Coop; and Rabbit
Hutch. Converted files use Sponge v2 `.schem`. The original gzip-JSON assets are
retained for attribution and reproducibility, not loaded as game worlds.

Compatibility changes are recorded per design in `manifest.json`: saved entities
and tile metadata are omitted, containers are empty, water is cleared, farmland
becomes unplanted dirt, waterlogged stairs become dry, legacy grass/wall states
are migrated, double slabs become full blocks, and petrified oak slabs become
ordinary oak slabs. An optional tree-house mineshaft marker is cleared; no shaft
is generated. Dry gardens are decorative, not irrigated farms. Snow/ice still
needs an appropriate site. Not every bundled design has been physically built
in every terrain configuration.

Use `/e build list`, `/e build select <id>`, `/e build show`,
`/e build materials`, `/e build rotate 90`, and `/e build here`. Preview is an
outline, not a ghost-block display of every interior. Confirm only after checking
the site, rotation, clearing/replacement and materials. Ordinary survival tools,
supplies, access and supported blocks are still required. Temporary access/support
materials are additional to static design material counts.

The desktop app queues local `.schem`/`.litematic` files (at most 8 MiB) for the
running client's import. Queue acknowledgement is not full format acceptance:
the client validates Sponge v1/v2 or Litematica v7 before adding to its library.
Legacy `.schematic` and ZIP files require conversion. In-game URL import remains
separate from this local inbox; arbitrary filesystem paths are not public URLs.

Importing a compatible file or supported public URL only adds validated content
to the library. It does not select a site or authorize building. You are responsible
for content permissions. Owner-downloaded houses lacking a redistribution grant
are intentionally absent from source and package inputs. Do not upload those
private imported caches as part of a public release.
