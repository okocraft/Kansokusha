# Log search

Kansokusha searches the DuckDB database owned by the current Paper/Folia server or Velocity proxy.
The search command is available on both platforms:

```text
/kansokusha search [conditions...]
/kansokusha event <event-id>
```

`event` loads one persisted event by its UUIDv7 event ID. Search result rows link to
`event` when the sender has event-detail permission. Pagination controls are also clickable.

## Query semantics

A field may be repeated. Values of the same field are alternatives (OR); different non-empty
fields are combined (AND). `exclude <field> ...` uses the same grouping rules and removes rows
matching the resulting exclusion predicate.

| Syntax | Meaning |
| --- | --- |
| `user <name>` | Player name. Matching is case-insensitive and resolves the latest historical UUID for that name. |
| `action <event-type>` | Event type. The `kansokusha:` namespace may be omitted, for example `action block_break`. |
| `time <duration>` | Relative time range ending at now, for example `time 1h` or `time 1w2d6h`. |
| `time <newer>-<older>` | Relative bounded range, for example `time 1h-2h`. Either order is accepted. |
| `time today`, `time yesterday` | Calendar-day range in `search-time-zone`. |
| `from <date-or-datetime>`, `to <date-or-datetime>` | Absolute bounds. A date-only `to` includes that whole local date by using the next day as the exclusive bound. |
| `include <key>`, `target <key>` | Target type; `include` and `target` are aliases. |
| `filter <text>` | Case-insensitive literal substring in the communication text. Quote values containing spaces. |
| `actor-uuid <uuid>` | Actor UUID directly. |
| `actor-kind player\|entity\|block` | Actor kind. |
| `actor-type <key>` | Entity/block actor type. |
| `world <key>` | World key. |
| `position <world> <x> <y> <z>` | Exact block position. |
| `around <world> <x> <z> <radius>` | Inclusive X/Z square around an explicit center; Y is not bounded. |
| `radius <radius>` | Shorthand for `around` centered on the executing Paper player's current block position. |
| `order newest\|oldest` | Result order. Default is `newest`. |
| `limit <count>` | Page size. Paper/Velocity players default to 10 and may request at most 50; non-player senders default to 50 and may request at most 1000. |

`radius` is available only when a Paper player supplies the current location. Paper console,
Velocity players, and Velocity console must use `around` or `position` instead.

`time` cannot be combined with `from` / `to`. A duplicate singleton modifier such as
`from`, `to`, `order`, or `limit`, an empty/inverted time range, or another invalid condition is
rejected, and the error message states the reason. Conditions that exclude everything they
include are not rejected; they simply match no events.

Examples:

```text
/kansokusha search user Alice action block_break time 1h
/kansokusha search action paper_chat filter "hello world" time today
/kansokusha search world minecraft:overworld around minecraft:overworld 100 -40 20
/kansokusha search action block_place target minecraft:diamond_block exclude user Bob
/kansokusha search actor-uuid 123e4567-e89b-12d3-a456-426614174000 order oldest limit 25
```

## Time zone

`config.yml` contains `search-time-zone`, an IANA time-zone ID such as `UTC`,
`Asia/Tokyo`, or `Europe/London`. The bundled default is `UTC`. It controls `today`,
`yesterday`, date-only bounds, and datetimes without an explicit UTC offset. Datetimes with an
offset keep that offset. Configuration changes take effect after restart.

## Player names

Paper join and Velocity post-login observations record each `(UUID, username)` pair with the time
it was last seen. Older observations processed later do not roll `last_seen` backwards. Name
changes are not recorded as separate events; the login events themselves keep the username.

`user` lookup is case-insensitive. If a historical name has been used by multiple UUIDs, the UUID
that most recently logged in with that name is selected. `actor-uuid` bypasses name resolution.
Offline `user` completion uses the same names and keeps the newest casing for each
case-insensitive name.

## Communication text

Only these built-in event types have a communication-text search projection:

- `paper_chat`
- `velocity_chat`
- `paper_player_command`
- `paper_server_command`
- `velocity_command`

Chat stores a plain-text projection of the original message; command events store the original raw
command. `filter` evaluates only this projection with a case-insensitive literal substring match.
Characters such as `%`, `_`, and `*` are ordinary characters. Search does not interpret regex,
wildcards, or fuzzy matching and does not scan the opaque payload BLOB.

## Identity and pagination

Every persisted event receives a UUIDv7 `event_id`. Chronological ordering is defined by the
pair `(occurred_at, event_id)`, so events sharing one millisecond still have a stable total order.
Next/Previous pagination uses that pair as a keyset cursor rather than an OFFSET. New rows inserted
while paging therefore do not shift already traversed rows as OFFSET pagination would.

The UUIDv7 timestamp is not the event occurrence time; `occurred_at` remains authoritative.

## Permissions

The command tree uses the platform permission API for every check:

- `kansokusha.command` — parent command
- `kansokusha.command.search` — search command
- `kansokusha.command.event` — event-detail command and links
- `kansokusha.command.search.event.<event-type>` — visibility of each event type, for example
  `kansokusha.command.search.event.kansokusha:block_break`

Kansokusha asks the platform permission API for the exact event permission node. It does not
implement wildcard expansion itself. A permission plugin's wildcard grants, explicit negative
nodes, inheritance, and precedence are therefore respected through the platform's returned
permission result.

Event types without permission are removed from search constraints and completion metadata, so
unauthorized rows and action/metadata suggestions are not returned. Explicitly querying an action
without its event permission is rejected. `event` also rechecks the exact event-type permission
before rendering the event.

## Execution and output

DuckDB reads run on the runtime's storage-owned background thread rather than the Paper/Velocity
command or event thread. Search does not force queued writes to flush; it reads committed storage
state only. The bounded write path, periodic/batch flush behavior, and write transaction ownership
are unchanged.

Paper and Velocity share parser, pagination, formatting, permission-filtering, and localization
support in `common`. Result rows show the event time/type and available actor, target, world /
position, and communication text. Player actors show the observed name when available and expose
the UUID on hover; the UUID is used directly when a name is unavailable. The event ID is available
on hover, and authorized rows link to `/kansokusha event <event-id>`.

## Initial-scope limitations

The initial search scope intentionally does **not** include:

- network-wide cross-server database search
- a public search API for other plugins
- arbitrary payload-field search
- raw payload search or payload dumping
- regex or fuzzy communication search
- search by `server`
- user-facing `payload_generation` or `expires_at` filters

Each Paper/Folia server and Velocity proxy searches only its own local DuckDB database. The
`server`, `payload_generation`, and `expires_at` values may still be persisted or displayed
where applicable; their presence does not make them user-facing search filters.
