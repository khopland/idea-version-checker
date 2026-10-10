# Phase 2 native follow-up: 10 October 2026

Native npm works in the available IDEA **2026.2.3** installation despite its
“Unlock Ultimate” button. This session replaces the earlier blanket npm blocker
with current-IDE functional evidence. The minimum-IDE npm configuration remains
unvalidated. Phase 2 is still incomplete; no RC, EAP or stable release was published.

## Artifact and conditions

All native cases below loaded the same `1.3.0-SNAPSHOT` archive:

`2160464c1166bf18a026388d1be2e0a89187ee299319e0457e891fff945a0d21`

The host was an Apple M4 / Mac16,1 with 16 GiB RAM, macOS 27.0, aarch64.
Separate disposable configuration, system, plugin and log directories were used;
the user's normal IDEA profile was untouched. Tracing was enabled. IDEA versions
were IU-253.33813.55 (2025.3.6.1) and IU-262.10968.63 (2026.2.3), with bundled JBR
21.0.11 and 25.0.4 respectively. Gradle fixtures used the checkout's Gradle 9.8.0
wrapper, SDKMAN Temurin 25.0.3 and a local file repository. The current IDEA npm
provider used the detected local Node 22.23.1 / npm 10.9.8 runtime and a disposable loopback
metadata registry. No dependency installation ran.

These are observed functional cases, not repeated latency benchmarks. Mixed
mode/scope traces must not be combined into a p95 result. Native UI actions and
file-content checks supplied the functional observations; sanitized traces retain
only the numeric performance schema.

## Observed cases

| IDEA / case | Result | Trace |
|---|---|---|
| 2025.3.6.1, small Groovy Gradle | Three visible Version Checker problems; Alt+Enter updated alpha to 2.0.0; beta/gamma stayed unchanged; one undo restored alpha. This resolves the earlier missing-diagnostic reproduction for this fixture. | [Trace](idea-2025.3.6.1-gradle-phase2.log) |
| 2026.2.3, small Groovy Gradle | Three diagnostics and local quick fix; Patch preview applied all three 1.0.1 targets; one undo restored all three. | [Trace](idea-2026.2.3-gradle-small-phase2.log) |
| 2026.2.3, npm workspace | Root and child editor checks, local and workspace quick fixes, alias/range preservation, grouped undo, Patch/Minor/Major previews, Current File/Whole Project scope, authentication failure and recovery. Details below. | [Trace](idea-2026.2.3-npm-workspace-phase2.log) |
| 2026.2.3, Kotlin Gradle with shared catalog | Preview listed all three consumers in one shared-version row; applying changed the shared version from 1.0.0 to 1.0.1; one undo restored it. Shared-version update and ignore intentions were offered. | [Trace](idea-2026.2.3-gradle-catalog-phase2.log) |
| 2026.2.3, 1,000 Groovy declarations, Maven disabled | Preview selected all 1,000 repeated alpha declarations. Apply changed all 1,000 to 1.0.1; one undo restored all 1,000 to 1.0.0. | [Trace](idea-2026.2.3-gradle-dense-phase2.log), [profile summary](idea-2026.2.3-gradle-dense-profile.json) |
| 2026.2.3, npm with Gradle disabled | Plugin startup and native npm checking completed; root status reported three updates. No edits were applied in this configuration. | [Trace](idea-2026.2.3-npm-gradle-disabled-phase2.log) |
| 2026.2.3, Maven with JavaScript disabled | Plugin startup, native Maven check and three-row Patch preview completed; status reported three updates. Preview was cancelled. | [Trace](idea-2026.2.3-maven-javascript-disabled-phase2.log) |

The provider-disabled cases produced expected warnings about bundled IDE plugins
requiring the disabled provider. No Version Checker missing-class error was
observed. These startup/query smoke tests do not establish dynamic unload safety.

### npm edit safety and recovery

The fixture has three root declarations and two child declarations. Root alpha
uses `^1.0.0`, its alias uses `npm:@fixture/alpha@~1.0.0`, and child alpha uses
`^1.0.0`. The local quick fix changed only root alpha to `^2.0.0`; undo restored
it. The workspace alpha action changed those three declarations to `^2.0.0`,
`npm:@fixture/alpha@~2.0.0` and `^2.0.0`, preserving beta and gamma. One workspace
undo, including IDEA's confirmation, restored all three. Saved manifests were
checked after apply and undo.

Patch, Minor and Major previews showed 1.0.1, 1.1.0 and 2.0.0 targets with the
range/alias syntax retained. Whole Project showed five selected declarations;
that preview was cancelled. Apply guidance explicitly described manifest-only
updates and subsequent lockfile synchronization. `node_modules` was absent;
the lockfile remained byte-identical, SHA-256:

`c3ea3f47c785d8db62680ca0d5fc63c1a3ce47867efcd9d42f2b67f7c1bafb9c`

Setting the fixture registry to reject metadata requests returned HTTP 401.
Refresh displayed retry/authentication guidance and made no manifest changes.
Show Details exposed the npm E401 explanation. Restoring registry access and
refreshing returned three current-file updates. The notification itself used the
generic `NpmViewFailure` label: this is evidence for the remaining F14 recovery
presentation work. The [registry observations](idea-2026.2.3-npm-workspace-registry.jsonl)
also include a manual CLI metadata sanity check and the Gradle-disabled smoke;
their total is not a plugin-only request count. No credentials are recorded.

### Dense edit timing and profiling

The 1,000-edit application endpoint took **622.93 ms**. The first Major native
query took 10,060.99 ms; Patch preview became ready in 6,642.54 ms, including a
fresh native query. Those queries use different modes and must not be treated
as a cache regression or compared as equal workloads.

The plugin test suite and Plugin Verifier ran concurrently with application.
This single contended observation exceeds the proposed 500 ms budget and does
not certify that target. One undo restored the entire edit set. The local JFR
recording is `/tmp/version-checker-phase2-dense.jfr`; its committed anonymous
summary contains 21 EDT execution samples in the second containing focus,
application and post-write work. Sampling and truncated stacks do not establish
maximum EDT occupancy, input delay or per-component time attribution. Controlled
repeated measurements and typing tests remain necessary.

## Environment issues and open gates

- IDEA 2026.2.3's native accessibility bridge raised an
  `AccessibleTextPanel` / `AccessibleAction` error when automation clicked the
  status widget. The stack identified IDE/JBR accessibility code rather than
  plugin code. Find Action provided a working route to the review dialog.
- The minimum IDEA profile still had unusable JavaScript support. Working npm
  on 2026.2.3 does not establish minimum-IDE npm behavior.
- Remaining native work includes npm ignore/recovery, cancellation, offline and
  runtime/configuration failures; per-version Kotlin/catalog and provider
  settings/unload cases; cold/setup conditions; 100-module projects; controlled
  dense/shared-edit latency; typing/input-delay measurements and the broader
  keyboard/accessibility matrix. Earlier historical evidence remains separate.

## Reproduction and automated validation

The new npm generator serves metadata on loopback and refuses a nonempty
destination. While running, its `registry-control.json` supports delay and 401
failure/recovery. The Gradle generator can repeat one coordinate for dense edits:

```sh
python3 scripts/create-native-npm-fixture.py /tmp/version-checker-npm
python3 scripts/create-native-gradle-fixture.py /tmp/version-checker-dense \
  --repository /tmp/version-checker-small/repository \
  --declarations-per-module 1000 --repeat-artifact alpha
```

The fixture server and owned native sandbox processes were stopped after capture;
temporary optional-provider disablement was removed. The performance reporter
now accepts IDEA 2026.2.3. Four new fixture tests cover destination preservation,
metadata-only HTTP behavior, live authentication recovery and safe repeated
artifact input.

Full validation passed with SDKMAN Temurin Java 21:
`check buildPlugin verifyPluginProjectConfiguration verifyPlugin` with all three
integration flags enabled, **395 tests in 59 classes**, zero failures/errors/skips,
and compatible verifier verdicts for 2025.3.6.1, 2026.1.4 and 2026.2.3 (the existing
10 experimental API usages remain). Python suites passed **13 release tests and
16 script tests**. The Gradle run took 2m 5s and used existing caches.

Concurrent error-reporter and release-documentation changes were preserved.
The rebuilt archive includes those changes and has SHA-256
`82bcb87d99b6027e3dc00015865c0de507400de2a8821bdef9c1f7358e901707`.
It passed automated validation but is **not** the archive used for the native
cases above. Exact final-release archive testing remains a gate.
