# Maven performance plan implementation audit

The [original proposal](maven-server-performance-ideas.html) is implemented for the supported Maven native path. Unsupported runtimes, extensions and custom Versions rules retain the existing read-only fallback. Full declaration coverage remains the default; faster scopes and persistence require an explicit choice.

| Proposal | Delivered behavior | Evidence |
|---|---|---|
| 1. Refresh once per scan | One generation per refresh; successful facts shared across matching module contexts; failed work remains retryable. Fresh misses force Resolver revalidation. | Native 100-module / 10,000-declaration fixture: 100 metadata HTTP requests, another 100 on Refresh; independent failed-key retry. |
| 2. Share raw metadata and stable indexes | Bounded context-keyed cache, shared in-flight workers, fixed original deadlines; stable Maven ordering and numeric branches prepared once for all baselines/modes. | Mode/context isolation, cancellation and cache-budget tests; exact selection parity and [CPU benchmark](performance-maven-history-2026-10-10.txt). |
| 3. Reuse native setup | Short-lived compatible scan sessions reuse the embedder pool and settings inputs across automatic, refresh and preview batches. Strict model/context fingerprints prevent unsafe model reuse. Workers retain a session until completion. | Native multi-batch tests, context/credential changes and packaged disposal tests. |
| 4. Native read-only batches | Bundled helper asks Maven Resolver for explicit histories. Batches retain per-artifact errors, secured XML parsing and Maven's repository/mirror/authentication behavior without dependency-tree or library-JAR resolution. | Authenticated repository fixture; malformed/DOCTYPE response isolation; native/fallback candidate parity. |
| 5. Fast editor scope | Opt-in omission of unused local management entries, with conservative inclusion when consumers are uncertain. Parents/BOMs, properties, active profiles and overrides remain covered. Partial status cannot satisfy a full preview; full Refresh results remain visible. | Native 1,000-entry management fixture plus consumer, coverage, full-report retention and stale-input tests. |
| 6. Platform first and owning declaration | Optional priority batches plus explicit Maven Platform previews. Imported parent chains lead to editable workspace owners, followed by explicit versions and overrides. Separate result identity preserves full audits. | Child → corporate → Boot-owner and inherited-BOM-property tests; native child platform preview produces one request and one edit. |
| 7. Bounded overlap and selected-file priority | Two metadata workers by default, configurable 1–4 within serialized native goals. First batch is small; completed goals yield to waiting equal/higher-priority checks. Slow work is bounded by a batch; an already-running goal is not preempted. | [50/250 ms delayed repository samples](performance-maven-latency-2026-10-10.txt); measured worker bounds, selected-module handoff, fairness and cancellation tests. |
| 8. Collapse plugin branch passes | Plugin histories selected locally using the shared index. Candidate POM prerequisites cached separately; candidates requiring newer Maven are rejected. Unsupported cases retain Versions goals. | Patch/Minor/Major parity, Maven prerequisite rejection and separate prerequisite invocation tracing. |
| Optional: persist the index | Default-off, bounded local successful facts with context digests, checksums and original age/expiry. Only fresh records can support results or edits; Refresh bypasses persisted data. | Native cache reopening: initial/reopened/refreshed HTTP counts 1/0/1. Unit coverage for corruption, clock changes, expiry, budgets, failed keys and old-worker races. |

The implementation keeps repository facts separate from edit authorization. Saved and unsaved POM/configuration fingerprints, declaration identity, shared-property consumers and report expiry are rechecked before applying a preview. Repository-cache POMs and reactor-only parent versions are never automatic edit targets.

## Validation and limits

Request-count, native Maven-server, IntelliJ platform, packaged lifecycle and compatibility checks cover implementation behavior. Controlled timing records distinguish first useful acceptance, complete checks, native goals and local preparation. Scope reductions are documented separately from equal-scope work reductions.

Final validation on 10 October 2026 passed 388 tests across 58 classes with Maven, npm and Gradle native integrations enabled, plus 12 Python trace-summary tests. The distributable ZIP was built, project configuration verified, and Plugin Verifier reported compatibility with IDEA 2025.3.6.1 and 2026.1.4. Existing notices remain: ten experimental API usages in each version and one deprecated API usage in 2026.1.4. Packaged lifecycle tests verify unload/reload and disposal.

The first clean run exposed an npm integration fixture that assumed every enum scope applied to every adapter. It now exercises the npm adapter's advertised scopes, so adding Maven Platform does not make the npm fixture request an unsupported preview. The full suite passed after that correction.

```sh
./gradlew clean check buildPlugin verifyPluginProjectConfiguration verifyPlugin \
  -PmavenIntegration=true -PnpmIntegration=true -PgradleIntegration=true --console=plain
python3 -m unittest discover -s scripts -p 'test*.py'
```

After correcting the fixture, the Gradle validation was repeated without `clean`; all production outputs came from the preceding clean build.

The original plan also proposed broad measurement campaigns. Large-project painted editor/preview/fix endpoints, cold installations, production throttling and typing-profiler sessions have not been fully measured in both IDE versions. Existing small warm native-window records remain valid for their recorded conditions; they are not a production p95 claim for this implementation. Full IDE restart timing for persistence is also unmeasured. See [performance.md](performance.md) and [native validation status](remaining-plan-work.md#native-validation-status).
