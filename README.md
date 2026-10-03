# Entity

Entity is a Minecraft 1.21.8 survival companion: a dedicated Fabric client with
Baritone performs actions, while the mandatory EntityBridge Paper plugin supplies
commands and server observations. Human players can use an unmodified vanilla
1.21.8 client. A vanilla server, Realms, and remote-hosted bridge are not supported.

## Distribution status

This is the **2.22.0 distribution candidate source**, not a declaration that an
installer or final release has shipped. Standalone setup and AI
backend selection require the exact candidate's release verification. Follow the
release's downloadable checksums and notes when a verified release is published.
Existing gameplay remains experimental; no all-biome, all-schematic or flawless
survival guarantee is made. Original Entity code is **all rights reserved** with
personal download/build/install/play permission in [LICENSE](LICENSE); it is not
open source. Third-party rights are preserved in
[third-party notices](THIRD-PARTY-NOTICES.md).

## Intended Windows quick start

These are the target standalone-app steps; controls still under verification are
candidate scope, not already-shipped capabilities.

1. Use a Windows x64 machine capable of running Minecraft 1.21.8. Download the
   verified Entity installer or portable package and check its SHA-256. Prism is
   not required. The app/game data must be kept separate from any existing world.
2. Open Entity, choose a **new local Paper 1.21.8 server** or an explicitly selected
   compatible local server, and accept the Minecraft EULA yourself. EntityBridge
   must be installed on that server. Back up an existing server before migration;
   never replace its world or weaken authentication as a setup shortcut.
3. Enter your human player's exact name. The bot name is fixed to **Entity**.
   This release is **offline-name-only**, for deliberately offline local Paper
   servers. There is no account login option; authenticated servers are unsupported.
   Never weaken authentication on an existing server as a workaround.
4. Choose **Visible** or **Background**, independently of AI. Both run a complete
   Minecraft client and require normal graphics support: Background is not
   GPU-free/headless server execution. Background is intended to be silent and
   input-isolated. The dedicated bot must not capture your mouse or keyboard.
5. Choose **AI Off** to use deterministic `/e` commands without a model download.
   **Managed Local** selects the pinned NVIDIA/CUDA model/runtime, with optional
   multi-gigabyte verified downloads and readiness checks. **External** accepts
   a compatible endpoint/model; cloud use requires explicit
   consent and never be a silent fallback. See [AI and privacy](docs/ai-and-privacy.md).
6. Start the local server and bot, then join with your normal human client. Use
   `/e help`, `/e status`, `/e follow`, `/e come`, and `/e stop`. Only the configured
   owner/authorized controllers may control Entity. The app includes command help.
   Use Stop before changing work.

For manual setup from source, see [building and pairing](docs/building-source.md).
The client and server must be built from the same source identity; mixing jars
from different releases is unsupported.

## Building with schematics

The licensed offline collection includes eleven third-party Prefab designs and an
Entity-authored starter shelter. No owner-downloaded unlicensed houses are included.
Run `/e build list`, choose `/e build select <id>`, then inspect `/e build show`
and `/e build materials`. Selection uses **your feet** as the site corner; Home
registration uses **Entity's feet**. Move outside the footprint before
`/e build confirm`, or `confirm gather` to authorize material acquisition too.
`/e stop` retains the project; `/e build resume` is explicit.

Import requires a supported schematic source and permission to use it. The
desktop app queues local `.schem`/`.litematic` files (at most 8 MiB) for the running
client's Sponge v1/v2 or Litematica v7 format validation/library import. A queued
file is not an accepted design or permission to overwrite worlds. Existing in-game
`/e build import <source>` handles supported public URLs, not arbitrary filesystem
paths. Import and preview never grant construction authority. See
[schematic limits and credits](docs/schematics.md).

The bundled editable `owner` personality is preserved verbatim in the plugin's
`entity-companion-owner.txt` runtime resource. Use in-game personality controls
for other supported profiles/custom descriptions; personality changes style,
not permissions. Personal prompt documentation/history is not included.

## Data, updates and troubleshooting

Keep persistent worlds, settings, imports and world-scoped Entity state when
updating. Candidate app upgrades/rollback must operate on versioned application
files, not roll a live world back. Stop owned processes before changing jars;
retain the previous verified package and back up data separately.

If Entity cannot connect, check the Paper/plugin version, exact bot/owner names,
loopback bridge address, matching pairing token, and client/server source identity.
If work fails, use `/e status`, `/e plan` and `/e help <topic>`; a successful chat
reply is not evidence that blocks/items changed. AI can be disabled independently.
Do not upload accounts, tokens, worlds, private dialogue or unreviewed logs in bug
reports. Report the release identity and a small redacted incident description.

This source snapshot has no development history or private tests. Its export
manifest records included-file hashes; it does not certify shipped binary contents.
