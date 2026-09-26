# Runtime retention configuration reload

Kansokusha supports replacing the active retention policy set without restarting the
runtime.

A reload reads and validates the complete `config.yml` first. Only after validation
succeeds is a new `RetentionPolicySet` built and atomically installed at the intake
boundary. If loading or validation fails, the replacement is not installed and the
previously active retention policy set remains in use.

Retention is resolved when an event is accepted. Events already accepted into the
bounded queue keep their resolved policy identity and absolute `expiresAt` value.
Only submissions accepted after the replacement use the new policy mappings and
durations.

The live reload scope is intentionally limited to retention policy definitions,
event-type mappings (including qualified mappings), and the fallback policy. Ingestion queue capacity, writer batch
settings, cleanup interval, cleanup pass size, debug mode, and platform identity are
startup settings and require a restart to take effect.

There is no automatic file watching. Platform adapters expose an explicit synchronous
trigger boundary:

- Paper/Folia: `KansokushaPaperPlugin.reloadRetentionPolicies()`
- Velocity: `KansokushaVelocityPlugin.reloadRetentionPolicies()`

Administrative integration should invoke the appropriate adapter method after updating
`config.yml`. The method returns `true` only when an active runtime accepted the
replacement. Invalid configuration is logged and returns `false`; the active policy
set is preserved.

Reload is not accepted once runtime shutdown/draining has started.
