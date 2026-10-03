# AI and privacy

Entity's deterministic Minecraft commands do not require a language model.
`/e ai off` disables the language adapter; `/e ai on` enables an explicitly
configured backend. AI interprets or discusses existing abilities; it does not
replace owner authority, create new gameplay capabilities, or guarantee truthful
wording. Direct Stop remains available.

Managed-local setup in the app uses `local-ai/pinned-runtime.json` and verifies
the publisher download hashes before readiness checks. It downloads the pinned
NVIDIA/CUDA runtime and model only when selected. Downloads are several gigabytes
and optional; AI Off downloads no model. This CUDA pack
does not establish compatibility with every GPU; inference and Minecraft compete
for resources. No universal response-time promise is made.

External mode accepts an OpenAI-compatible endpoint and model. Local endpoints
and cloud endpoints are different trust choices. Cloud
requires explicit consent because contextual game facts and conversation may
leave the machine. API keys are protected locally for the selected Windows user
and passed to the owned server process, never public source/releases/diagnostic
uploads. No automatic cloud fallback is allowed. Selection/readiness are not a
guarantee of compatibility with every external provider or response latency.

The bundled `owner` personality is an intentionally preserved default runtime
profile in `server-plugin/src/main/resources/entity-companion-owner.txt`. It is
included verbatim as product content, not replaced with a sanitized profile.
Choose `/e personality set friendly`, `neutral`, or `chaotic`; use the in-game
help for a custom description. The owner profile is separate from technical
action contracts. Personality affects style, not permissions or Minecraft facts.
Conversation expires after ten minutes; world-scoped gameplay state is separate.

Do not publish local credentials, account caches, pairing tokens, raw chat, world
files or owner configuration. The source export deliberately excludes prompt
drafts/history, developer evidence and installed state; it retains only the
runtime default needed for the preserved product behavior.
