# Held fixes during partial checks

Updated 2026-10-11. This refines the
[shared quick-fix authorization policy](candidate-freshness.md).

A new partial result previously retired every descriptor from the preceding
partial report. A user holding an update for dependency A could lose that action
when unrelated dependency B completed, even though A's metadata and build inputs
had not changed.

Partial inspection fixes now bind to declaration tokens during fix construction.
Completing, replacing, removing or adding a notice/coverage outcome for one
declaration replaces only its token. Unrelated results preserve the tokens of
already rendered fixes. Reobserving the same candidate still replaces its token;
removing and later restoring identical rows cannot revive an older fix.

Each partial check has a separate lineage token. Refresh, cancellation, owner or
snapshot replacement, expiry, eviction, disposal and final-result publication
retire that lineage. A scoped lease retains its original observation time and
deadline; a later shorter partial deadline can retire it earlier. Binding after
the originating observation has been replaced fails closed.
An already bound lease cannot broaden to additional declarations; narrowing it
retains its original contributor guards and deadline.

Complete previews retain their existing whole-result identity policy. Partial
facts still cannot authorize a complete preview. Hints remapped across source
edits retain the conservative whole-observation guard until there is an exact
snapshot match.

## Provider scopes

- Maven literal/local overrides scope the matching declared coordinate and kind.
  Repeated matching declarations bind together. Shared properties and managed
  owners scope the represented shared consumers. If ownership or any consumer
  outcome is absent, the original whole-observation guard remains. Declaration
  indexing and guard reuse are local to an inspection analysis, avoiding a full
  report scan per fix.
- npm local edits bind their declaration. Workspace actions bind the eligible
  contributing declarations in the source report, including aliases. Existing
  workspace fingerprints, selector checks and no-downgrade rules continue to
  guard every edited manifest.
- Gradle shared catalog values bind all parsed consumers of their source range.
  Candidate agreement, original-text and whole-build input checks remain.

Lease callbacks retain identity tokens and declaration IDs rather than report or
snapshot payloads. Active declaration tokens participate in the existing result
payload budget and are removed with their rows. There is no growing token history
or refresh tombstone map.

## Regression coverage and remaining acceptance

Deterministic cache tests cover unrelated and related partial deltas, shared
contributors, absent/late bindings, original and shortened deadlines, completion,
refresh/cancellation/owner replacement, eviction/disposal, no revival after row
removal, conservative remapped hints and bounded token storage.

Headless platform tests hold real edit actions across partial observations for
Maven literals/properties and npm local/workspace aliases. Gradle fixtures retain
actual inspection descriptors while the coordinator publishes another result,
then verify successful shared catalog edits or unchanged text after a contributor
replacement.

Native Alt+Enter/focus acceptance on the supported IDEs remains open. This is an
authorization and usability change; it does not establish native latency, screen
reader behavior or complete native acceptance.

## Validation

Final validation passed on 2026-10-11 with SDKMAN Temurin Java 21.0.12+1.1:

- **107 focused tests in five classes** passed before the final scope-broadening
  guard; the repeated full suite includes that additional assertion.
- **488 plugin tests in 67 classes**, zero failures, errors or skips. All native
  integration flags were enabled: six Maven, four npm and five Gradle methods,
  plus four packaged-plugin lifecycle methods.
- **13 release-automation and 16 script tests** passed. Release metadata remains
  `1.3.0-SNAPSHOT`, EAP channel and `releasable=false`.
- **`check`, `buildPlugin`, `verifyPluginProjectConfiguration` and `verifyPlugin`**
  passed in 1m 58s using local Gradle outputs/caches. This was an incremental run.
- **IDEA 2025.3.6.1, 2026.1.4 and 2026.2.3** are binary-compatible with this
  archive, retaining ten existing experimental API uses per target and no
  deprecated/scheduled-for-removal uses.
- The packaged descriptor remains `1.3.0-SNAPSHOT`, minimum build `253.33813.55`,
  with this batch's change notes. Changed local links and diff whitespace pass.

```bash
python3 -B -m unittest discover -s .github/scripts -p 'test_*.py'
python3 -B -m unittest discover -s scripts -p 'test_*.py'
python3 .github/scripts/release_metadata.py
./gradlew check buildPlugin verifyPluginProjectConfiguration verifyPlugin \
  -PmavenIntegration=true -PnpmIntegration=true -PgradleIntegration=true --console=plain
```

Archive: `build/distributions/version-checker-1.3.0-SNAPSHOT.zip`  
SHA-256: `f871c7504de5e49fc47d6dda98df2bacf1956b86e78d5a783de9a85fe005336e`.

Built from this working tree on top of `723071d`. Final log:
`/tmp/version-checker-scoped-lease-full.log`; focused log:
`/tmp/version-checker-scoped-lease-focused.log`; artifact identity:
`/tmp/version-checker-scoped-lease-artifact.json`. An earlier full run also passed
before the scope-broadening adjustment; its log is retained at
`/tmp/version-checker-scoped-lease-initial-full.log`.
