# Cached observation timing

The Dependency Versions review uses bounded, in-memory observations. Observation age describes when the cached result was observed. It does not establish that the dependency was successfully checked, that the build inputs still match, or that an edit is authorized. Failed checks and skipped declarations can also have observation times.

Timing comes from the original cache entry or active progress entry, including when the provider's report omits timestamps. Reading, filtering, repainting or exporting an observation does not reset its age or extend its deadline. Retained progress uses its earliest observation and original conservative expiry; it does not claim a separate exact check time for every contributing declaration. Pending declarations and declarations with unreported coverage without an observed result show unknown timing. Expired entries still leave the cache; no durable history or expired-result retention is introduced.

Age and expiry use the process's monotonic clock. Missing or future observation times, missing expiry times, inconsistent intervals and arithmetic overflow remain unknown. Zero is a valid monotonic timestamp. A known deadline reached at the display/export sample is expired. A positive remaining duration below one millisecond is rounded up to one millisecond so exported zero means expired. "Unexpired" only describes the cache deadline; refresh and review still precede editing.

## JSON schema 2

The export contains the displayed presentation rows, sanitized diagnostics and project-relative source paths. Its timing fields are:

| Field | Meaning |
| --- | --- |
| `schemaVersion` | `2`; consumers of schema 1 must account for the added timing fields. |
| `generatedAt` | Wall-clock time the export file was generated, not a dependency-check or release-publication time. |
| `generatedAtPurpose` | `export-creation`. |
| `observationTimingBasis` | `monotonic-durations-at-export`; all rows use one sampled monotonic time. |
| `observationAgeMillis` | Elapsed observation age in whole milliseconds, or explicit `null` if unknown. |
| `cacheExpiresInMillis` | Remaining cache duration in milliseconds, rounded up, `0` at/after expiry, or explicit `null` if unknown. |
| `cacheExpiryStatus` | `unexpired`, `expired` or `unknown` at export. |

`generatedAt` can change after a wall-clock adjustment without changing the exported observation durations. No exact observation wall-clock date or dependency publication date is inferred. Absolute monotonic timestamps, cache snapshots, result revisions, fingerprints and edit leases are never serialized. `requiresFreshReviewBeforeEditing` remains `true`.
