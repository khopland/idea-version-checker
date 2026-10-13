# External input guards

Updated 2026-10-11. This continues the background input-capture and
[candidate freshness](candidate-freshness.md) work.

Maven now fingerprints saved external configuration with SHA-256 instead of file
size and modification time. User/global settings, credential files and relocated
credential targets, plus ancestor `.mvn` configuration, use the same identity in
discovery, background result validation, inspections and final edit guards.
An external rewrite with unchanged size and timestamp invalidates the old input
identity without requiring a VFS refresh. Missing inputs remain explicit; creating
or deleting them changes identity. Touching unchanged bytes retains identity.

The digest uses an 8 KiB buffer and checks cancellation between reads. Background
capture uses coroutine cancellation; synchronous guards use the IDE progress
indicator. One Maven input view continues to share saved fingerprints across
selected modules and overlay current unsaved document stamps. Fingerprints retain
hashes rather than configuration or credential contents.

Gradle final bulk validation now captures external user-home inputs once per
validation and shares those hashes across linked roots. Each validation captures
fresh bytes again, including after a rejected apply. Root-specific build inputs,
settings and unsaved external text still validate separately before the first
write. No hashes from preview preparation are reused to authorize a later apply.

## Regression coverage

- Maven same-size/same-timestamp changes to settings, security XML, relocated
  credentials and all four ancestor configuration forms reject synchronous and
  background validation, preview preparation and the whole two-file transaction.
  Rediscovery accepts the changed content without a VFS refresh.
- Held Maven parent-update and local-override inspection fixes reject the same
  external settings rewrite, preserving both POMs.
- Maven missing/present inputs change identity on creation/deletion, while
  timestamp-only touches preserve identity.
- Twelve linked Gradle builds with nine external files read those files nine
  times during a final validation. A same-stamp rewrite rejects every edit; a
  subsequent validation rereads the files and can apply when the original bytes
  are restored. The previous guard read each external file per linked root.

## Remaining scope

This closes the saved-content identity gap documented in the Maven capture batch
and reduces repeated Gradle work during apply. At this validation point, synchronous
inspection and edit guards still perform external disk work, and Maven content
hashing adds byte reads to those paths. Background capture remains outside IDE
read actions. Native slow-disk, typing/input-delay and supported-IDE acceptance
remain required before making latency claims or finalizing a release candidate.

## Validation

Final validation passed on 2026-10-11 with SDKMAN Temurin Java 21.0.12+1.1:

- **475 plugin tests in 67 classes**, zero failures, errors or skips. All native
  integration flags were enabled: six Maven, four npm and five Gradle methods,
  plus four packaged-plugin lifecycle methods.
- **13 release-automation and 16 script tests** passed. Release metadata remains
  `1.3.0-SNAPSHOT`, EAP channel and `releasable=false`.
- **`check`, `buildPlugin`, `verifyPluginProjectConfiguration` and `verifyPlugin`**
  passed in 2m 3s using local Gradle outputs/caches. This was an incremental run.
- **IDEA 2025.3.6.1, 2026.1.4 and 2026.2.3** are binary-compatible with this
  archive, retaining ten existing experimental API uses per target and no
  deprecated/scheduled-for-removal uses. These verdicts do not establish UI latency.
- The packaged descriptor is `1.3.0-SNAPSHOT`, minimum build `253.33813.55`, with
  this batch's change notes. Changed documentation links and diff whitespace pass.

```bash
python3 -B -m unittest discover -s .github/scripts -p 'test_*.py'
python3 -B -m unittest discover -s scripts -p 'test_*.py'
python3 .github/scripts/release_metadata.py
./gradlew check buildPlugin verifyPluginProjectConfiguration verifyPlugin \
  -PmavenIntegration=true -PnpmIntegration=true -PgradleIntegration=true --console=plain
```

Archive: `build/distributions/version-checker-1.3.0-SNAPSHOT.zip`  
SHA-256: `ade30d37172f008bd430fcf74721dff655809bd358d0d92ea7e7245bd187af11`.

Built from this working tree on top of `7455bb8`. Final log:
`/tmp/version-checker-final-input-full.log`; artifact identity:
`/tmp/version-checker-final-input-artifact.json`. An earlier full run had four
incremental test-bytecode signature failures after the helper changed; the final
helper retains its one-argument entry point, and the repeated full run passed.
The earlier log is retained at
`/tmp/version-checker-final-input-incremental-failure.log`.
