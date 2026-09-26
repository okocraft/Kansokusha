# Language resources

Kansokusha keeps translations with the module that owns the message.

- Shared translations live in `common/src/main/languages`.
- Paper-specific translations live in `paper/src/main/languages`.
- Velocity-specific translations live in `velocity/src/main/languages` when needed.

Platform builds merge the shared and platform-specific files into a single
`languages/<locale>.properties` resource. A language key must be owned by only one module;
duplicate keys between common and a platform fail the build.

English defaults come from `DefaultMessageDefiner` definitions in code. Japanese is bundled
as the initial non-default locale. On startup, Kansokusha creates language files under the
plugin data directory and appends missing keys while preserving existing customizations.

Messages are sent as Adventure translatable components, so Paper and Velocity render them
using the receiving audience's locale. Additional locale property files can be added to the
language directory by server administrators.
