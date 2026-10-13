# Result retention and input preparation

Implemented and validated on 2026-10-11.

## Result budgets

Each project's `VersionResultCache` has these internal limits:

| Retained state | Default limit |
| --- | --- |
| Context registrations, including sources and generations | 2,048 |
| Completed mode reports and incremental reports combined | 1,024 |
| Weighted payload | 1,000,000 units |
| Source/generation registration without newer work | 10 minutes |
| Successful / failed complete report | 10 minutes / 1 minute, capped by the native deadline |
| Incremental report | 10 minutes, capped by every contributing native deadline |

Payload units count snapshot declarations, fingerprint paths, candidate/notice
rows and their strings in 256-character blocks. Repeated references are counted
conservatively. Incremental replacement/removal updates its weight by delta;
reading status counts does not assemble a full report. This is a retention/work
budget, not a measurement or hard limit on JVM heap bytes. Exception graphs,
provider caches, running workers and open review plans need separate lifecycle
and native heap checks.

Contexts use least-recently-used eviction. When any budget is exceeded, eviction
removes the whole context: all mode reports, partial hints, source tracking and
generation bookkeeping. A single oversized payload is rejected without evicting
other contexts. An evicted review needs a fresh preview. These budgets accommodate
the existing 100-module / 10,000-declaration fixtures, but a larger project can
lose cached hints or reject a preview when its required reports exceed the budget.

Expiry is checked on access. A sweep removes expired payloads and inactive
registrations at most once every 30 seconds during cache activity. Idle caches
remain capacity-bounded; there is no cleanup timer. Provider refresh removes its
registrations immediately, source refresh removes matching registrations, and
project-service disposal clears the cache.

Registration generations come from a monotonic counter that is never reset.
Removing a registration needs no tombstone: a new registration always has a new
identity. Complete and partial publication require the original registration to
still exist. Late publication cannot recreate an evicted context, even if a fresh
registration uses the same file, snapshot, report object or clock timestamp.
Provider isolation, original metadata deadlines and owner-specific partial
cleanup are retained.

## Background save preparation

Refresh, preview and recovery now discover required input paths in cancellable
background work. Only the document-save step runs on EDT. Preview no longer
performs a duplicate save from its action handler. Discovery/checks run after
saving and retain the existing unsaved/external-input and apply guards.

- Gradle captures linked-build VFS paths and the configured user home under a
  read action, then walks external `init.d` files on IO without a read lock.
- Maven captures imported POMs, selected directories, settings and distribution
  homes under a read action. Settings/security relocation discovery runs on IO
  without a read lock; collecting save paths no longer fingerprints inputs.
- npm collects workspace manifests, ancestor configuration and configured
  runtime/user-configuration paths without reading or hashing their contents.

Cancellation during discovery prevents a subsequent save. The save callback
uses the current unsaved documents, so edits made while paths are being collected
are saved with their latest text. Project disposal and cancellation are checked
before the callback and between document saves. Scheduled checks still leave
unsaved inputs alone.

This moves action/save preparation to background work. At this validation point,
snapshot creation and synchronous quick-fix/apply freshness checks still read external inputs through
the existing provider fingerprint implementations, sometimes under read actions
or on EDT. Moving those reads requires a separate capture/revalidation design;
they remain unchanged here to preserve edit safety. Controlled slow-disk/native
typing measurements also remain open.

## Regression evidence

`VersionResultCacheTest` covers count/weight pressure, mode and provider isolation,
physical expiry cleanup, partial-owner cleanup, rejected oversized payloads and
late workers after eviction/refresh. A 10,000-cycle import/rename/scope fixture
asserts all retention limits throughout; it is a model soak test, not a native
import or heap measurement.

`BuildInputSavingTest` covers discovery outside EDT/read locks, saving the latest
text, cancellation before saving, selective workspace/build inputs, and real
nested Gradle user-init files. Maven regressions retain shared-parent inputs and
relocated/unsaved credential guards. Coordinator/recovery regressions retain
current-file isolation, retry scope and preview freshness.

## Validation of this batch

The final run passed on 2026-10-11 with SDKMAN Temurin Java 21.0.12+1.1:

- **419 plugin tests in 62 classes**, zero failures, errors or skips. This includes
  6 Maven, 4 npm and 5 Gradle native integration methods, 4 packaged-plugin
  lifecycle methods, 21 result-cache tests and 6 input-saving tests.
- **13 release-automation and 16 script tests** passed. Project release metadata
  remains `releasable=false` for `1.3.0-SNAPSHOT`.
- **`check`, `buildPlugin`, `verifyPluginProjectConfiguration` and `verifyPlugin`
  passed** in 2m 17s. This was an incremental build with existing local outputs
  and caches, not a clean-machine build.
- **IDEA 2025.3.6.1, 2026.1.4 and 2026.2.3 are binary-compatible**, retaining the
  same 10 experimental API usages and no deprecated/scheduled-for-removal usages.
- Packaged descriptor and notes checked: `1.3.0-SNAPSHOT`, minimum build
  `253.33813.55`, with both new save-preparation and retention notes. Changed
  Markdown local links and whitespace checks passed.

Archive: `build/distributions/version-checker-1.3.0-SNAPSHOT.zip`  
SHA-256: `fc60965a8480bce07330d13e808b3e25d22ea05a722d1a758cdfee2a18078626`

This archive was built from the phase 3 working tree on top of `d855e00`.
Full validation log: `/tmp/version-checker-phase3-input-cache-full.log`; focused
100-test regression log: `/tmp/version-checker-input-cache-tests.log`. Current XML
and verifier reports remain under `build/`. The new archive has automated and
integration evidence; it has not completed the native UI/typing/heap matrix.

```bash
python3 -B -m unittest discover -s .github/scripts -p 'test_*.py'
python3 -B -m unittest discover -s scripts -p 'test_*.py'
python3 .github/scripts/release_metadata.py
./gradlew check buildPlugin verifyPluginProjectConfiguration verifyPlugin \
  -PmavenIntegration=true -PnpmIntegration=true -PgradleIntegration=true --console=plain
```
