# Version-check performance work

The [performance plan, revision 2](https://plan-api.k8r.no/p/uQBB44ZJlfWkuneRIxSpEf51/v/2) now has dependency filtering (M1), combined npm queries (N1), workspace metadata sharing (N2), reusable Maven scan inputs (M2), and profiling hooks implemented. Check-scoped runtime and discovery reuse (N3) and pass-local Maven fingerprint reuse (M5) are also implemented. Plugin branch rules (M3) and Maven session experiments (M4) remain follow-up work guided by profiling.

Maven's dependency goal receives both `dependencyIncludes` and `dependencyManagementIncludes`, built from the selected snapshot's resolved group/artifact pairs. The list ignores baseline differences and preserves Maven's native version selection. Empty dependency categories skip the goal. Coordinates that cannot safely be represented as exact inclusion patterns retain the broad query. Retrieval-failure exclusions apply alongside the inclusions, and metadata fallback uses the selected declarations.

npm normally executes `npm view '<name>@>=0.0.0' name version deprecated --json` with the existing authentication, configuration, runtime and freshness flags. The response supplies baseline deprecation notices and candidate deprecation checks for all update modes. Aliases and member manifests share that response within the same npm workspace and resolution configuration. Unsupported response formats and a range with no stable match use the previous versions/deprecation path; authentication errors, transport errors and cancellation propagate. Selectors and update modes are evaluated locally from the shared raw versions and notices.

## Workspace sharing and Maven scan inputs

The project-scoped npm cache shares successful raw metadata and in-flight requests by workspace root, configuration digest, package name and refresh generation. Configuration includes project/ancestor/user npm settings, environment, selected Node/npm runtime, known runtime-manager files and package-manager/workspace/runtime fields in `package.json`. Dependency selector edits reuse raw metadata; strict edit fingerprints still include the complete workspace manifests and deprecation policy. Credentials enter only the digest, never the cache key or traces.

Successful responses expire ten minutes after completion. Reports inherit the earliest metadata deadline, so creating another report from a warm response cannot extend its freshness. Completed entries use LRU eviction with limits of 512 packages, 100,000 version slots and 8,000,000 characters of version/deprecation data. The version-slot budget includes raw strings, parsed records and baseline-map entries; parsed history shares the original strings. Oversized histories serve their current callers but are not retained. Failures and cancellations are not retained. Cancelling one caller preserves a request needed by another; cancelling its last caller stops the native worker. Project disposal or plugin unload stops all workers, including older generations.

Manual refreshes, scheduled scans and bulk previews begin a new generation once per adapter, allowing files in that scan to share fresh responses. Workers already serving callers may finish, but cannot join or populate the new generation. Independent npm roots and changed resolution settings remain isolated. The legacy query fallback can still require per-version deprecation commands when a registry does not supply complete combined metadata.

Maven receives the complete selected coordinates from the captured snapshot, groups them once, reads Maven config properties once and copies explicit profiles once per scan. Legacy lookup callers collect declarations once. Goals, branch retries, metadata expiration and retrieval fallbacks reuse those inputs. Effective repository data is evaluated lazily once and resolved separately for dependency and plugin repositories. Each POM still gets a fresh embedder; no cross-scan Maven session is retained.

## npm discovery during source editing

The discovery cache now distinguishes manifest content from structural changes. Ordinary source document/PSI edits, saved source changes, and source-file creation or renaming retain the discovered manifests, workspace ownership and manifest hashes. Manifest edits rebuild ownership and workspace hash collections while reusing unchanged files' hashes. Manifest creation/copy/move/rename/deletion, directory changes, project-root changes and index availability rebuild discovery conservatively. All listeners are removed on project disposal or plugin unload.

Hashes include saved VFS bytes and unsaved document text separately, and are cached by both stamps. A cached saved document cannot hide externally refreshed file contents. npm configuration files are checked independently by their stamps; external disk configuration is still read fresh. SHA-256 values use the JDK's hexadecimal encoder instead of formatting each byte individually.

A repeated source-edit fixture validates one manifest in a workspace of 101 manifests and 10,000 declarations. The original cache rebuilt shared inputs after each source edit; the revised cache retains them. The [before/after record](performance-npm-discovery-2026-10-08.txt) contains five alternating warm/source-edit samples per implementation. These timings cover local currentness validation, not network queries or completed editor highlighting.

| Validation path | Before median / p95 | After median / p95 |
|-----------------|--------------------:|-------------------:|
| Warm inputs | 0.65 / 1.48 ms | 0.69 / 0.79 ms |
| After a source edit | 11.20 / 14.86 ms | 0.83 / 1.10 ms |

The source-edit median fell by about 10.4 ms per validation in this fixture. The full suite passed 210 tests, including native Maven/npm integration and packaged-plugin unload checks. Build, configuration and compatibility checks passed for both supported IDEA versions.

## npm runtime setup during a scan

Compatible checks share lazy runtime resolution by workspace root, configuration digest and refresh generation while any check or metadata worker is active. A metadata worker owns its own session lease: cancelling the initiating check cannot kill setup needed by a surviving caller. Successful resolution is shared across query waves, including legacy deprecation queries. Warm metadata does not resolve or launch Node/npm. Failed setup is not retained, and the last departing lease removes the session. Later scans therefore observe changes behind a version-manager shim without retaining a runtime across scans. Refreshes detach old sessions; project disposal cancels both current and detached workers.

The selected local interpreter still executes npm at the workspace root, preserving shim directory selection and wrapper behavior. Only the npm package directory discovered through `process.execPath` is reused. Both that ten-second probe and the sixty-second registry query use cancellable process handlers; cancellation propagates, and timeout requests native process termination before falling back or reporting failure. Sessions span one file check and any overlapping compatible checks, rather than retaining a runtime through sequential project-wide file checks. Shared warm metadata avoids setup entirely for repeated packages in later files. Four independent registry queries may run concurrently as before. The `NPM_RUNTIME_RESOLUTION` debug stage distinguishes one-time runtime setup from individual command construction.

An opt-in native fixture wraps the configured Node interpreter in a POSIX shim. Twelve runtime requests in three waves during one active scan cause one probe; a refresh and a later scan each cause a fresh probe. Separate native cancellation and timeout checks assert that the Node process exits. These are setup-count and lifecycle assertions, not an end-to-end editor latency benchmark.

## Maven project inputs per read pass

Whole-project discovery and bulk-preview validation now capture the non-ignored imported POMs, saved/unsaved POM stamps and common settings fingerprint once per read pass. Project lookup uses that captured view. Ancestor `.mvn` configuration stays specific to each module, with each shared path read once within the pass. Settings and ancestor configuration stamps now also include unsaved documents; the previous fingerprint only tracked their saved timestamps and sizes. Individual snapshots still contain complete fingerprints, including unselected sibling POMs, so shared-property safety is preserved.

The input view is local to discovery, preparation or validation. Applying a prepared preview captures fresh inputs inside the write command and rejects changed sibling documents, saved or unsaved settings/ancestor configuration, imports/ignores or deprecation policy before making any edits. It is never reused across suspended repository work or a later validation pass. Individual pre/post-native checks still capture fresh project inputs. Each POM retains its fresh native embedder and repository context.

The [before/after record](performance-maven-inputs-2026-10-08.txt) measures a simulated import of 101 POMs with 10,000 declarations, without running Maven goals. Five repeated discovery/collection-validation samples cover DOM traversal and local fingerprints; discovery also includes background scheduling and equality checks. The fixture proposes and applies 100 literal edits and checks unsaved sibling invalidation.

| Local path | Before median / p95 | After median / p95 |
|------------|--------------------:|-------------------:|
| Discovery | 136.93 / 168.31 ms | 105.98 / 115.63 ms |
| Collection validation | 22.90 / 24.13 ms | 4.14 / 5.44 ms |

Collection validation saved about 18.8 ms per pass in this fixture. These are separate test JVMs with warm local inputs; JIT, scheduling and filesystem state affect results. They do not establish a production or native-query speedup. The full suite passed 216 tests, including native Maven/npm integration and packaged-plugin lifecycle tests. Plugin build, configuration and compatibility checks passed for IDEA 2025.3.6.1 and 2026.1.4 with the existing API notices.

Per-module fingerprint maps still contain the full shared POM stamps; this change removes repeated input collection and filesystem reads rather than every quadratic map copy or comparison.

## Editor hint preparation: 9 October 2026

Completed results use a 50 ms fixed highlighting batch for files selected in the editor, while other files retain the 200 ms batch. Selection is read on the EDT. A selected-file request promotes a pending path out of the background batch, and repeated requests do not postpone either batch's deadline. Both workers belong to the project service's coroutine scope and stop on cancellation. These windows control restart scheduling; they do not establish time to a rendered diagnostic or include a busy EDT's dispatch delay.

Maven's inspection bridge now captures one snapshot and report for updates, relocation notices and quick-fix construction. Apply still revalidates the current project inputs, including unselected sibling POMs. Manually constructed analyses retain their lazy snapshot fallback. The regression fixture counts one snapshot for an inspection with both kinds of diagnostic, then changes a sibling and verifies that the retained fix revalidates and refuses to edit.

Gradle inspections group candidates by declaration ID once and index the first notice for each ID. Matching no longer searches the complete candidate list for every declaration. Duplicate candidate IDs remain ambiguous, and shared-version notices retain their original report order. A 1,000-declaration visitor fixture checks all 1,000 diagnostics and fixes while bounding candidate traversal to linear work. Separate cases check duplicate rejection and first-notice ordering.

npm inspections group workspace consumers by artifact once per pass, then build each workspace fix from the matching group. They preserve selector operators, alias targets, workspace ownership and no-downgrade checks. Grouping avoids scanning unrelated packages for each candidate. Later work also shares prepared workspace actions for repeated aliases within that pass; see the measured workspace-fix section below. The inspection fixture checks separate alpha/beta groups and applies an alpha workspace fix without changing beta.

The targeted reproduction command is:

```bash
./gradlew test --tests '*FileProblemRefreshQueueTest' \
  --tests '*MavenWarningRetentionTest' --tests '*GradleInspectionTest' \
  --tests '*NpmWorkspaceVersionFixTest'
```

Use a clean rebuild after changing internal constructor signatures if incremental test bytecode is stale. These tests validate work counts, batching and edit safety, not production editor latency. Measure first visible hints and restart frequency in IDEA before claiming an end-to-end speedup or reducing the active window further. Active-file scan prioritization, incremental result publication and mode-aware cached previews were subsequently delivered; see their sections below and the [fast-hints review](fast-hints-ux-review.html).

The complete validation passed 255 plugin tests with no failures, errors or skips, including authenticated native Maven/npm/Gradle integration and packaged-plugin unload coverage. `check`, `buildPlugin`, `verifyPluginProjectConfiguration` and `verifyPlugin` passed. Both IDEA 2025.3.6.1 and 2026.1.4 remain compatible, with the existing experimental progress API notices and the existing deprecated read-action notice on 2026.1.4. No native editor latency measurement was performed.

## Check scheduling

Each adapter still runs one native check at a time. A cancellable queue gives manual checks priority, then inspections of selected editor files, then background work. The selected-file set is captured on the EDT and updated by a service-owned editor-selection listener; priorities are evaluated at each handoff. Equal priorities keep arrival order. Running native work is not preempted merely because the selected file changes.

Current-file refreshes no longer wait for the whole-project refresh mutex: they can take the next adapter slot between modules. Whole-project refreshes remain serialized, and scheduled checks skip active manual requests and queues. Discovery checks selected files first. Superseded automatic jobs are cancelled, including queued jobs, while scan revisions and currentness checks continue to reject stale output. Cancellation of an npm caller still follows its metadata worker's existing lease rules.

Queue tests cover selection changes, priority order, FIFO, cancellation and handoff. Coordinator tests hold one module open and verify that a current-file refresh finishes while an unrelated module remains blocked; another replaces a queued inspection and verifies that obsolete native work never starts. These checks establish ordering and lifecycle behavior, not remote latency or visible highlighting speed.

## Reproduce the comparisons

```bash
./gradlew test -PmavenIntegration=true -PnpmIntegration=true -PperformanceTrace=true \
  --tests '*MavenSettingsIntegrationTest' \
  --tests '*NpmRegistryIntegrationTest'
```

The fixtures use IDEA's Maven server and configured Node/npm with authenticated local HTTP registries. The tests assert native HTTP request counts and candidate/notice parity. They alternate the old and new paths five times, print each duration and report the median and nearest-rank p95 (the maximum with five samples). Raw benchmark output is retained in `build/test-results/test/TEST-*.xml` under `system-out`.

The Maven comparison uses a child with one versionless declaration controlled by parent management. Four other regular dependencies are inherited from the parent. All five metadata timestamps are expired before each comparison so both paths check the same fresh registry data, retaining native artifacts. Broad queries retrieve five metadata documents; filtered queries retrieve one and preserve the selected update. Additional checks cover a newly published release under a daily policy, active/inactive profiles, a BOM import and an empty dependency category.

A parent that **only manages** five dependencies and a child that declares one already produce one request with the current broad path. The pinned Versions Plugin uses original-model dependency management when `processDependencyManagementTransitive=false`, which the checker already sets. The request reduction above concerns inherited regular dependencies; it does not demonstrate savings for unused parent-managed entries alone. See the pinned [extraction implementation](https://github.com/mojohaus/versions/blob/2.21.0/versions-common/src/main/java/org/codehaus/mojo/versions/utils/MavenProjectUtils.java#L91).

The npm comparison uses a scoped package and an alias in one manifest, with a deprecated baseline and highest patch candidate. The old path requires four commands/HTTP requests: versions, baseline notice, rejected candidate notice and accepted candidate notice. The combined path uses one command/request with identical candidates and notices. Tests also cover all modes and both file/project scopes, an older `latest` tag, one stable release, later deprecations, only prereleases, authentication failure, forced refresh, cancellation and unchanged lockfiles/install state.

## First-stage recorded run: 8 October 2026

The full validation run used IDEA 2025.3.6.1, its bundled Maven server 3.9.11, Versions Plugin 2.21.0, the Java 21 Gradle toolchain, Node 22.23.1 and npm 10.9.8 on macOS arm64. See the [raw samples and representative stage traces](performance-2026-10-08.txt).

| Fixture/path   | Metadata HTTP requests/check |   Median | p95, five samples |
|----------------|-----------------------------:|---------:|------------------:|
| Maven broad    |                            5 | 211.4 ms |          267.1 ms |
| Maven filtered |                            1 | 214.8 ms |          237.1 ms |
| npm legacy     |                            4 | 598.6 ms |         1116.2 ms |
| npm combined   |                            1 | 135.3 ms |          146.7 ms |

Maven's request reduction did not produce a median elapsed-time improvement in this small local fixture. npm reduced both requests and elapsed time. These measurements include native query/check work, not the complete editor interaction. The full suite passed 190 tests, including Maven/npm authentication, refresh and cancellation coverage and packaged-plugin lifecycle tests. Plugin verification reported compatibility with IDEA 2025.3.6.1 and 2026.1.4, with existing experimental API notices and a deprecated API notice on 2026.1.4.

## Workspace validation: 8 October 2026

The native npm fixture also checks 100 member manifests plus the workspace root, with 200 declarations of one scoped package through direct selectors and aliases. A fresh generation requires one native metadata command and one authenticated registry HTTP request for all 101 snapshots, preserving all 200 proposed edits. A unit workload checks 10,000 references to 100 packages and requires 100 loads per generation. See the [workspace validation record](performance-stage2-2026-10-08.txt). The single workspace timing is a work-count check, not a production latency comparison. The full suite passed 207 tests, and plugin build, configuration and compatibility checks passed for IDEA 2025.3.6.1 and 2026.1.4, retaining the existing API notices.

## Cached, selectable previews: 9 October 2026

Report caching now keys successful checks by build context and update mode. Opening a preview uses an exact fingerprint/declaration match and an unexpired report for that mode, with current-input validation before reuse. A second check inside the interactive queue avoids repeating a check completed while the request was waiting. Retained inspection-only reports cannot authorize preview edits. npm continues to reuse raw metadata across modes while deriving candidates locally; Maven and Gradle need a report for the requested mode or perform a native check.

Refresh invalidates the selected provider/source across modes and its native metadata before checking again. Preview guards retain the exact report identity, generation and original freshness deadline, plus the adapter's complete input and shared-consumer validation. Expired, refreshed or replaced results reject apply before any edit. The displayed age is time since report preparation; it does not extend underlying repository metadata expiry or imply a new HTTP request.

The preview includes filtering, sortable checkbox rows, scope/mode controls, Refresh and a collapsed Needs review section. Filtering preserves selections and explicitly counts selected hidden rows. Select visible adds visible rows; Clear selection removes all selections. Applying selects atomic declaration/property/catalog edits in their original order, with every original plan guard retained. Shared edits remain indivisible. Only the selected providers' post-apply synchronization guidance is shown. Empty previews retain Refresh and mode/scope controls instead of requiring another menu trip. The editor context menu opens a patch preview directly, and repeated invocations focus an existing preview.

The native npm fixture asserts zero registry requests for repeated same-mode/current-input previews after a fresh check. Coordinator and UI fixtures check mode isolation, forced refresh, mode/refresh dialog transitions, sorted-row selection, hidden selections, empty previews, stale-input rejection and provider-specific guidance. These are work-count and behavior checks; no end-to-end visible-hint or native IDEA usability timing is claimed.

The full suite passed 271 tests with no failures, errors or skips, enabling all three native integration suites and packaged-plugin lifecycle checks. Plugin build, project configuration and compatibility checks passed for IDEA 2025.3.6.1 and 2026.1.4. The verifier retained the existing seven experimental API usages on both versions and one deprecated API usage on 2026.1.4.

## Earlier npm inspection results: 9 October 2026

npm publishes one inspection update after each package and its aliases finish. Independent packages retain the four-query limit, shared raw metadata and runtime worker ownership. Package failures are collected without cancelling unrelated queries; the final report remains unsuccessful if any package failed, and interactive checks preserve the native exception type/details. A successful empty package result removes that declaration's older hint.

Early reports live in a separate presentation cache. They cannot satisfy complete-check reuse or bulk-preview lineage. Each update validates current build inputs and the refresh generation before publication. Previously valid hints for unprocessed declarations are retained conservatively, and partial expiry is capped by retained/native metadata deadlines. Once new progress starts, an older complete major-mode report stops authorizing edits. Progress accumulates in declaration-indexed maps; deltas update only completed declarations, and lists are materialized when an inspection reads them. Cancelled jobs clear only their own progress, without clearing a replacement owner's entry. Final publication replaces progress with the complete report.

A gated package fixture proves that a fast package and its alias publish while the slow package remains blocked. A coordinator fixture proves the inspection sees the early result while a bulk preview waits for the complete check, then reuses the final report without another native query. Failure, expiry, retained-hint replacement, cancellation, refresh and packaged-plugin unload tests cover the same path. These are ordering and safety assertions, not measured native-window rendering latency.

The full suite passed 279 tests with zero failures, errors or skips, including native Maven/npm/Gradle and packaged-plugin lifecycle tests. Plugin build, configuration and verification passed for both supported IDEA versions, retaining the existing API notices.

## Earlier Maven inspection results: 9 October 2026

Incremental Maven checks run dependency, plugin and parent categories in that order within the same fresh embedder per POM. Each finished category supplies a presentation delta for its declarations, with relocation notices read for those coordinates. Metadata expiration, repositories, profiles and Maven configuration remain native. Failed categories are reported separately while independent categories can continue. The final adapter report is unsuccessful if any category failed, so it cannot authorize a complete bulk preview. Coroutine/IDE cancellation and publisher errors propagate and stop later categories. Strict lookup callers retain immediate native failure propagation.

The authenticated native Maven fixture gates execution after the dependency category publishes. It verifies a real dependency inspection warning is available while plugin checking remains blocked, and that the preview cache stays empty until both categories finish. The native npm fixture blocks one authenticated registry response and verifies another package's real inspection warning appears while the scan continues. On release, both fixtures require a complete successful final report with every candidate. Category tests cover failure isolation, strict failure behavior, cancellation and publisher invalidation. These establish earlier availability and completeness guards, without claiming a measured deadline for visible IDEA rendering.

The full suite passed 285 tests with zero failures, errors or skips, enabling native Maven/npm/Gradle and packaged-plugin lifecycle tests. Plugin build, project configuration and compatibility verification passed for IDEA 2025.3.6.1 and 2026.1.4, retaining the existing seven experimental API usages on both and one deprecated API usage on 2026.1.4.

## Current-file status: 9 October 2026

The optional **Versions** status-bar widget reports the selected build file's queued/running state, current complete results, failures, offline mode, required saves, disabled checks and unavailable build information. Its popup reuses the current-file Refresh and Review actions and opens plugin settings. “Checked” describes supported declarations only; retained warnings and partial reports cannot produce it. Failure tooltips give recovery steps without copying native repository error details.

Coordinator events, editor selection, build-input document/VFS changes, project roots, indexing and plugin settings request coalesced background reads. Selection clears the preceding file's result immediately, and a second selection check prevents publishing a result into a different editor. Snapshot work stays off the EDT even when the factory receives an EDT scope. Displaying status neither calls `updates()` nor schedules a native check. Partial-result counts read declaration-indexed maps without constructing a full presentation report.

Complete and partial counts keep their original monotonic expiry deadlines. A deadline can clear the displayed result while native work continues; it never triggers another repository query. Project/widget disposal cancels the child scope and removes owned listeners. Platform fixtures cover passive reads, queued versus running files, partial/final transitions, blocked checks, retry, cancellation, changed inputs, selection, expiry and disposal. Native-window rendering and usability timing still require a real IDEA session.

The full suite passed 291 tests with zero failures, errors or skips, including native Maven/npm/Gradle and packaged-plugin lifecycle checks. Plugin build, configuration and compatibility verification passed for IDEA 2025.3.6.1 and 2026.1.4, with no new verifier warnings: the existing seven experimental API usages on both versions and one deprecated usage on 2026.1.4 remain.

## Faster failure recovery: 9 October 2026

Manual and scheduled project scans collect repository/discovery failures and offline providers into one summary. Successful file results stay available. Automatic inspection failures share one active notification per adapter, keeping every failed file as a retry target while bounding representative native details to twenty samples. Native messages and stack traces appear only in the on-demand details dialog; the summary contains recovery guidance. Opening details performs no repository work.

**Retry Failed Checks** saves documents, invalidates reports only for the failed sources across modes, and starts one fresh metadata generation per affected adapter. Discovery captures current inputs before filtering to those sources. Successful files retain their complete report identity, freshness deadline and preview guards. A discovery failure with no known source requires rediscovering that provider; unavailable requested sources are reported for import/reload recovery. Retries use the interactive queue and do not wait for a whole-project scan mutex. Coroutine and IDEA cancellation are excluded from failure collection, including discovery and post-edit discovery.

Maven literal/property/override, npm local/workspace and Gradle fixes explain rejected input/edit guards and offer **Refresh This File**. Workspace and Gradle plans also report rejection at the final write-command validation, preserving atomicity. Repeated stale messages in one file are deduplicated. Declining write permission remains a cancelled operation. Stale-fix feedback does not trigger a native query, and recovery actions remain disabled while checks are paused.

Actionable recovery notifications expire after one minute and are removed when expired. Their project service expires every active notification on disposal; details dialogs use the existing cancellable, nonmodal preview-dialog lifecycle. Packaged-plugin tests create a live recovery notification, unload the plugin, assert expiry and collect the old class loader across three reload cycles. Platform fixtures cover mixed-provider grouping, automatic deduplication, selective retry with preserved successful preview lineage, cancellation, guarded/atomic edits, paused recovery and on-demand native details. These are behavior and work-count checks, not measured native-window interaction timings.

The full suite passed 302 tests with zero failures, errors or skips, including all three native integration suites and packaged-plugin lifecycle checks. Plugin build, project configuration and compatibility verification passed for IDEA 2025.3.6.1 and 2026.1.4 with no new warnings; the existing experimental API notices and one deprecated API usage on 2026.1.4 remain.

## Shared npm version histories: 9 October 2026

Each npm metadata response now owns one thread-safe, lazily prepared stable-version index. The native metadata cache prepares it outside its lock before issuing a lease, accounts for its retained records and baseline-map entries, and starts the completion TTL afterwards. Consumers share it across workspace files, aliases and update modes for the lifetime of that response. Refresh, configuration isolation, expiry, cancellation and disposal retain their existing metadata ownership rules.

Baseline notices use a numeric-version map preserving the first original registry spelling, including build metadata. Candidates keep stable descending precedence and the original order among equal versions. Patch/minor selection uses a binary search to skip newer groups, then stops at the baseline; major selection starts at the highest release. Deprecation checks stop at the first eligible nondeprecated candidate without allocating a complete candidate list or reparsing its version. Legacy notice queries, alias prefixes, unsupported selectors, zero-major rules and unpublished baselines retain their existing behavior.

An opt-in CPU fixture compares the original per-declaration selection algorithm with the real checker across manifests. Each sample checks all three modes against one shared raw response, including its first index preparation. It uses shuffled histories, direct selectors, aliases and complete deprecation metadata. Every sample asserts complete candidate/notice parity. After three warmups, five samples alternate between paths. The original reference excludes the new path's coroutine/report-merge overhead; neither path launches npm, sends HTTP requests or renders an editor. See the [raw baseline and validation samples](performance-npm-history-2026-10-09.txt).

| Stable versions / consumers | Original median / p95 | Shared index median / p95 |
|----------------------------|----------------------:|--------------------------:|
| 100 / 2 | 0.55 / 0.61 ms | 0.12 / 0.18 ms |
| 100 / 200 | 35.12 / 44.69 ms | 2.99 / 5.01 ms |
| 5,000 / 200 | 1,578.92 / 2,319.29 ms | 5.33 / 6.16 ms |

The long-history fixture demonstrates meaningful repeated local CPU work and its removal. These five-sample p95 values are the maximum sample, not reliable production percentiles or a general speed multiplier. JIT, allocation and GC costs vary between runs; the record includes an earlier separate-JVM baseline. End-to-end visible-hint and preview timing still requires a real IDEA session.

The authenticated native npm fixture checks 101 manifests with 200 direct/alias declarations and 5,008 published versions, including one prerelease. All three modes share one metadata command and one HTTP request in a fresh generation. It preserves patch/minor candidates and skips the deprecated highest major release, selecting 14.9.48. The single 940 ms native workload timing is a work-count check, without a corresponding old-path comparison.

Reproduce the isolated CPU fixture with:

```sh
./gradlew test --tests '*NpmVersionHistoryBenchmarkTest' -PnpmHistoryBenchmark=true
```

The benchmark is excluded by default. The full validation run explicitly enabled it alongside all three native integration suites: 310 tests passed with zero failures, errors or skips. Mixed/invalid histories, equal precedence, safe numeric limits, legacy notices, concurrent index sharing, cache budgets and packaged-plugin unload checks passed. Plugin build, configuration and compatibility verification passed for IDEA 2025.3.6.1 and 2026.1.4 with the existing API notices only.

## Shared npm workspace fixes: 9 October 2026

An npm inspection now prepares one workspace action per artifact and exact target-version string within that visitor. Equivalent alias warnings reuse its guarded edit list; every local action still owns its declaration's edit and selector. Absent workspace actions are cached too, avoiding repeated preparation when only one manifest has eligible declarations. Separate versions, packages and inspection passes keep separate actions. This cache has no project-level lifetime or repository data.

The workspace action retains its existing full-input fingerprint, expected-selector checks, writable-file preparation and final atomic write validation. Newer consumers, peer/manual/local dependencies, excluded/nested workspaces and unsupported package managers keep their existing exclusions. Changing a sibling invalidates every alias's shared action. The action still applies one undoable command with the same impact count, operator/alias preservation and synchronization guidance.

An opt-in fixture runs the real visitor against complete cached reports in a 100-member workspace with 10,000 declarations: each member has 100 aliases of one package. The selected file produces 100 warnings, each offering a local and workspace fix. After two warmups, five samples cover snapshot capture, report/consumer indexing, PSI traversal, edit construction and problem registration. Initial report preparation, native registry queries and editor rendering are excluded. Before and after samples use separate JVMs; see the [raw baseline, targeted and full validation runs](performance-npm-inspection-2026-10-09.txt).

| Workspace declarations / selected-file warnings | Before median / p95 | After median / p95 | Distinct workspace actions |
|------------------------------------------------|--------------------:|-------------------:|---------------------------:|
| 4 / 2 | 1.64 / 1.83 ms | 0.59 / 0.61 ms | 2 → 1 |
| 10,000 / 100 | 1,512.10 / 1,535.07 ms | 68.46 / 73.90 ms | 100 → 1 |

The first registered problem's median was 49.33 ms before and 68.13 ms in the final validation run. Sharing removes the repeated work after that first problem; it does not demonstrate faster first-problem preparation. Earlier targeted after-runs took about 51–53 ms for the complete large visitor. The work-count reduction is stable, while elapsed values vary with JIT, GC, project/index state and scheduling. Five-sample p95 values are maxima, and these headless timings do not establish time to a visible IDEA hint.

Reproduce the fixture with:

```sh
./gradlew test --tests '*NpmInspectionBenchmarkTest' -PnpmInspectionBenchmark=true
```

It is excluded by default. Full validation enabled both npm benchmarks and all three native integration suites: 315 tests passed with zero failures, errors or skips. New platform fixtures cover action sharing within one pass, independent local aliases, pass/version/artifact isolation, stale siblings and newer-member exclusions. Existing atomicity, undo, ownership, recovery and packaged-plugin unload checks also passed. Build, configuration and compatibility checks passed for IDEA 2025.3.6.1 and 2026.1.4 with no new verifier warnings.

## Gradle inspection and preview preparation: 10 October 2026

Gradle's script parser now looks ahead by index after a dependency literal, skipping closing call parentheses to detect customization blocks. Previously, `drop()` and `dropWhile()` copied the remaining token list for every declaration. The new walk preserves quoted-token handling, wrapped calls, comments, constraints and manual-review decisions without allocating those suffixes. It changes local parsing only; native resolution boundaries are unchanged.

Inspection and bulk-plan preparation also capture the complete file text once and share that immutable string with the file's edits. Expected literals come from the same text used to parse their ranges. The four-argument edit constructor remains available for individual callers. Every edit still checks exact current whole-file text before apply, and provider/report guards remain intact. The capture is confined to the preparation pass; it has no project-level cache or new freshness policy.

An opt-in fixture measures the real Gradle inspection visitor and adapter plan builder with current complete cached reports. Inspection includes snapshot capture, parsing, candidate indexing, edit construction and problem registration. Plan preparation includes full-input validation, parsing and edit creation. After two warmups, five samples alternate inspection/preview order. Assertions check all warnings, labels, expected/target literals and descending ranges. See the [raw original, intermediate and final runs](performance-gradle-preparation-2026-10-10.txt).

| Declarations / path | Original median / p95 | Final median / p95 |
|---------------------|----------------------:|-------------------:|
| 100 / inspection | 2.78 / 7.03 ms | 1.14 / 1.25 ms |
| 100 / plan preparation | 1.92 / 2.04 ms | 0.80 / 1.10 ms |
| 1,000 / inspection | 33.60 / 37.38 ms | 8.00 / 10.45 ms |
| 1,000 / plan preparation | 21.06 / 29.34 ms | 5.48 / 7.81 ms |

The 10,000-declaration workload was added after file-text sharing. With the old token-copying parser still present, inspection took 1,826.49 / 2,123.38 ms median / p95 and plan preparation took 847.43 / 1,050.65 ms. Removing the suffix copies reduced those measurements to 63.61 / 107.04 ms and 29.02 / 37.74 ms respectively in the final validation run. This comparison begins from the intermediate implementation; no fully original 10,000-declaration baseline was measured. A targeted final run recorded 51.94 ms inspection and 27.88 ms plan-preparation medians.

These are separate JVMs, and JIT, allocation, GC, index state and scheduling affect elapsed values. Five-sample p95 values are maxima. Native Gradle/repository work, preview-dialog construction, editor rendering and apply are excluded. Apply still validates each edit and commits its document as before; this change does not establish faster application or visible hints.

Reproduce the fixture with:

```sh
./gradlew test --tests '*GradlePreparationBenchmarkTest' -PgradlePreparationBenchmark=true
```

It is excluded by default. Full validation enabled all three preparation benchmarks and all three native integration suites: 320 tests passed with zero failures, errors or skips. New regressions cover wrapped customization blocks, stale captured text despite unchanged version literals, changes to unselected declarations, different-length selected replacements and one-command undo. Existing shared-catalog, native-resolution, atomicity, recovery and packaged-plugin unload checks passed. Build, configuration and compatibility checks passed for IDEA 2025.3.6.1 and 2026.1.4 with the existing API notices only.

## Maven shared-property preview preparation: 10 October 2026

The Maven plan builder now traverses the supplied usage POMs once to index references to candidate version properties. Previously it traversed every POM twice for each property, reading attributes and leaf values repeatedly. The index records exact reference containment, including composite, repeated and nested markers, and stays local to one preparation pass. Compatible consumers are matched through an anchor set after confirming they all request the same target version.

Each indexed use still needs the existing exact version reference, local property ownership and covered-consumer checks. Attributes, unselected modules, inactive-profile consumers and other uses still prevent automatic shared-property edits. Conflicting updates stay in Needs review. Provider input validation, stale-edit guards, atomic application and one-command undo are unchanged; no native Maven query or cache behavior changed.

The opt-in fixture measures the actual `MavenBulkUpdatePlan.create()` with IDEA XML PSI and Maven DOM property resolution. After two warmups, five samples cover problem discovery, resolution, use checks and edit construction in one POM. Every proposed edit, its order and exact versions are asserted. Snapshot/current-input capture in the adapter, native Maven/repository work, background scheduling, dialog construction/rendering and apply are excluded. See the [raw before/after samples](performance-maven-preparation-2026-10-10.txt).

| Properties / consumers per property | Original median / maximum | Final median / maximum |
|------------------------------------|--------------------------:|-----------------------:|
| 10 / 2 | 4.21 / 4.84 ms | 0.89 / 1.15 ms |
| 100 / 2 | 55.04 / 62.47 ms | 5.44 / 6.05 ms |
| 500 / 2 | 1,071.46 / 1,182.81 ms | 30.59 / 32.86 ms |
| 1 / 1,000 | 13.55 / 14.56 ms | 15.57 / 18.67 ms |

The benefit is clearest with many independent properties. The single-property fixture has no demonstrated elapsed-time improvement: the targeted final run measured 13.04 ms median, while the full run measured 15.57 ms. These are separate JVMs; JIT, allocation, GC, DOM/index state and scheduling affect elapsed values. Five-sample maxima do not establish production p95. This change does not establish faster visible hints or complete dialog interaction.

Reproduce the fixture with:

```sh
./gradlew test --tests '*MavenPreparationBenchmarkTest' -PmavenPreparationBenchmark=true
```

It is excluded by default. Full validation enabled all four opt-in benchmarks and native Maven/npm/Gradle integration: 325 tests passed with zero failures, errors or skips. New regressions cover nested/composite/repeated references, independent similarly named properties, per-property review decisions and inactive-profile uses. Existing unselected-module, attributes, shared dependency/plugin properties, inherited versions, stale apply, undo and packaged-plugin lifecycle checks passed. Build, configuration and compatibility checks passed for IDEA 2025.3.6.1 and 2026.1.4 with the existing API notices only.

## Inspect real-project traces

In IDEA, open **Help → Diagnostic Tools → Debug Log Settings** and enable:

```text
#io.github.khopland.versionchecker.CheckPerformance
```

Trace entries in `idea.log` contain a fixed stage, anonymous interaction ID, monotonic start/end/origin timestamps, elapsed nanoseconds and a count:

```text
version-check stage=MAVEN_DEPENDENCY_GOAL elapsedNs=123456789 count=1 interaction=12 startNs=1000000000 endNs=1123456789 originNs=900000000
```

Coroutines and shared npm metadata/runtime workers propagate the initiating interaction. Native tasks and UI dispatch do not log paths, coordinates, URLs, settings, command arguments or credentials. Interaction zero denotes a standalone uncorrelated stage. IDs are process-local; use ID plus origin when reading a log spanning restarts.

Stage coverage includes Maven/npm/Gradle snapshots, coordinator queues and complete checks; Maven model/settings/embedder/metadata work and individual goals; npm runtime/command construction, shared metadata waits, CLI execution and local version indexing; Gradle setup, native execution and configuration/query durations; accepted results; highlight queues/restarts; preview plan preparation and readiness; local fix invocation and changed editor text. `FILE_ACTIVATED` to `DIAGNOSTIC_VISIBLE` ends after installed Version Checker markup intersects the selected viewport and an explicit editor paint completes. `IDEA_HIGHLIGHTING` observes daemon start through that paint. `PREVIEW_READY` requires a showing dialog and completed content paint. These debug-only paints and logging add profiling overhead, and compositor latency is excluded.

Refresh and committed mode/scope changes start a separate preview interaction. Its native work, preparation and ready endpoint retain that new ID across dispatch; time spent reviewing the previous dialog is excluded. Stale-plan recovery also starts a fresh interaction after its explanation is dismissed. Bulk Apply starts its fix interaction at dialog acceptance, before selected-plan construction and writable-file preparation, and carries that origin through the atomic edit. Rejected or cancelled edits have no changed-text endpoint.

Gradle configuration/query durations are measured inside its JVM and read from a numeric temporary timing file, including failed queries that reach the task. They do not use buffered console callback times. Their timeline positions are approximated at the end of the host native invocation; configuration includes init-script-to-query work, while process startup/tooling overhead stays outside these native spans. Early failure/cancellation can produce only the overall native duration. Shared npm workers belong to the first initiating interaction; other callers' `NPM_METADATA_WAIT` records their wait without attributing a second native invocation.

Do not add overlapping stages or milestone durations. The [trace summarizer](../scripts/summarize-performance.py) partitions interval coverage into exclusive categories and leaves unidentified time visible, rather than treating whole `CHECK`/preview/diagnostic endpoints as independent work. It exports only the trace schema plus a case label, completed endpoint sample counts, median/p95, and separate stage distributions. Full reproduction conditions and the still-required native performance/usability matrix are in [remaining-plan-work.md](remaining-plan-work.md).

Reports also include incomplete interactions, endpoint counts (preview rows or changed declarations), and native invocation observations per sample: invocations owned by that interaction, starts within its endpoint interval, and all overlapping invocations, including uncorrelated background spans. Gradle's host invocation counts once; its nested query duration is not a second invocation. Aggregate overlap counts are sample observations: a long background invocation may overlap multiple samples. Background spans are not charged to another interaction's exclusive stage partitions. Uncorrelated events contribute to stage distributions and overlap checks, but cannot create endpoint samples.

Use one IDEA process and condition per capture, with complete native spans. A call still running when the capture ends has no completed duration to report. These counts describe recorded host invocations, not HTTP requests or activity in an incomplete capture; use independent repository request counters when verifying remote traffic. Earlier checked-in JSON reports retain their original schema and can be regenerated from their sanitized traces with the updated script.

Disable tracing after profiling. Capture current-file/project scope, mode and cold/warm cases separately. Metadata expiration/invocation counts do not establish HTTP revalidation; repository fixtures count requests independently.

## Maven server metadata batching: 10 October 2026

Supported Maven 3/Java 17+ checks now use a bundled helper inside IDEA's Maven server. It submits artifact metadata batches to Maven Resolver, then selects Patch/Minor/Major candidates locally. Plugin candidates also have their Maven prerequisites read through Maven's project builder. The helper inherits Maven's repository, mirror, authentication and transport configuration. Custom extensions/rules and unsupported runtimes keep the existing Versions-goal path.

The project service shares bounded version histories across compatible modules and modes for ten minutes from successful completion. Metadata keys exclude declaration baselines and modes, but include the resolution-context digest and refresh generation. Successful sibling entries survive a failed artifact and are reused on retry. A new generation cannot join or retain an older worker's results. A shared worker survives one cancelled consumer; the final consumer or project disposal cancels it. Refresh/preview sessions are short lived, serialized and retained until their surviving workers finish.

Full scope remains the default. Optional editor filtering omits unused local management entries, retaining used coordinates, imported BOMs, property declarations and inherited overrides. Uncertain consumer resolution retains full scope. Reduced snapshots expose a coverage description and partial status; they cannot authorize full refresh/preview results. Parent and Spring Boot platform priority is a separate optional ordering control. Metadata concurrency defaults to two and is bounded from one to four.

The native integration fixture uses IDEA's real Maven server, an authenticated HTTP settings mirror, daily release-update policy, 100 imported modules and 100 repeated artifact coordinates per module (10,000 declarations). Module baselines alternate between 1.0 and 1.1. The server delays successful metadata responses by 10 ms and offers stable versions, a prerelease and a snapshot. These are synthetic modules, not a full Spring Boot application or a timing comparison against 100 legacy module checks.

| Assertion                                                | Observed work                                                            |
|----------------------------------------------------------|--------------------------------------------------------------------------|
| Legacy Versions comparison for one module                | 100 metadata HTTP requests; exact candidate map matches the native path  |
| Native scan of all 100 modules                           | 100 fresh metadata HTTP requests for 10,000 declarations                 |
| Warm Patch and Minor mode changes                        | 0 additional metadata HTTP requests; original expiry retained            |
| New release followed by explicit generation invalidation | 100 fresh requests; new candidates observed                              |
| One artifact fails, then recovers                        | 99 successful hints retained; recovery requests only the failed artifact |
| Same mirror ID, changed URL                              | 100 fresh requests to the changed URL without explicit invalidation      |
| Changed settings credential                              | 100 fresh authenticated requests without explicit invalidation           |

The separate settings/native integration test checks plugin candidate POMs: a release requiring Maven 99 is skipped in favor of the newest compatible release, and its prerequisite decision is reused. It also checks core-extension fallback. Unit and platform regressions cover expiry, generation races, overlapping consumer cancellation, disposal, cache bounds, plugin/dependency repository-key isolation, partial/full snapshot separation, inherited management overrides and unresolved consumers. Packaged dynamic unload includes the Maven metadata service.

A follow-up native fixture uses a private temporary local repository and a controlled HTTP repository with a 2 ms delay per metadata response. Its workspace parent declares 1,000 managed entries; a child uses 25 versionless dependencies. A synthetic external parent uses the Spring Boot parent coordinate to exercise platform-priority ordering; this is not a real Spring Boot release or application. The child issues 25 metadata requests. Fast parent checking issues 26 requests (25 used entries plus the external parent), publishing the external parent first. Explicit full checking issues 1,001 requests and returns all 1,001 expected updates. A subsequent Patch lookup issues no additional requests. Fast/full timings describe different coverage and cannot be used as an equal-scope speedup claim.

Completed and failed full audits now retain their coverage in the editor until expiry or invalidation. This avoids replacing a Refresh result immediately with a reduced snapshot. Global settings and security/relocation inputs are shared between metadata-context discovery and preview fingerprints. Regressions first failed on lost full coverage and changed credential targets, then passed after the corrections; unsaved credential changes also reject prepared edits. Settings-file discovery and disk stamps are reused within a single input pass.

Run the native request-count fixture with:

```sh
./gradlew test --tests '*MavenSettingsIntegrationTest.testBatchedMetadataSharesOneHundredArtifactsAcrossOneHundredModulesAndModes' -PmavenIntegration=true
./gradlew test --tests '*MavenSettingsIntegrationTest.testLargeManagementFastScopeAndFullAuditWithPlatformPriority' -PmavenIntegration=true
```

The fixture emits an anonymous `benchmark=maven-shared` work-count line. `MAVEN_METADATA_BATCH` spans are counted as native repository invocations by the trace summarizer; reuse and coordinate/context counts are separate events. The full integration validation uses `-PmavenIntegration=true -PnpmIntegration=true -PgradleIntegration=true`. This establishes request deduplication and correctness boundaries, not a production p95 or a general speed multiplier. Boot-child model cost, large single management POMs, cold starts, remote tail latency and painted editor endpoints still need comparable measurements on both IDEA versions.

Final follow-up validation: 369 platform/unit/native integration tests passed with zero failures, errors or skips; all nine trace-summary tests passed. `check`, `buildPlugin`, `verifyPluginProjectConfiguration` and `verifyPlugin` passed against IDEA 2025.3.6.1 and 2026.1.4. The verifier reported compatibility with experimental API notices on both versions and a deprecated API notice on 2026.1.4. The large-management test uses a fresh, temporary Maven local repository on each run.

### Automatic-check setup reuse

Automatic native editor checks now open the same short-lived scan session used by Refresh and previews. Model evaluation, metadata batches and plugin-prerequisite goals borrow from one embedder pool for a configuration. Nested checks keep the enclosing session, while a standalone check releases its session after completion; shared metadata workers retain their lease until their last consumer finishes. `MAVEN_SESSION_POOL` records pool creation separately from `MAVEN_SESSION` embedder acquisition, and the trace summarizer classifies it as native setup.

The helper also prepares effective repositories once per repository kind in a goal, creates one secured XML parser for its sequential metadata parsing, and looks up the plugin artifact factory once when needed. These objects stay local to a goal. For the 1,001-coordinate full fixture with platform priority, nine metadata goals now share the check's pool; repository and parser setup scales with goals rather than individual metadata files.

The native automatic-check regression failed before the change because its incremental callback had no scan session. It now invokes the adapter without an outer session, checks that both platform/dependency callbacks share a session, and verifies that completion releases it. The authenticated 100-module fixture additionally serves malformed XML and a forbidden external DTD among valid metadata: 98 sibling updates survive, no DTD request is sent, and recovery requests only the two failed artifacts. Existing shared-worker cancellation, context changes, prerequisite compatibility and unload regressions remain applicable.

One full-scope before/after observation is retained in the [setup validation record](performance-maven-batch-setup-2026-10-10.txt). Both checks returned all 1,001 expected updates with 1,001 metadata requests. This is one sample per implementation from a controlled repository, not a production latency estimate or a speedup claim.

Setup-reuse validation passed all 369 platform/unit/native integration tests with zero failures, errors or skips, plus all nine trace-summary tests. `check`, `buildPlugin`, `verifyPluginProjectConfiguration` and `verifyPlugin` passed on both supported IDEA versions with the existing verifier notices.

### Shared Maven version-index preparation

Raw Maven histories now lazily prepare one ordered stable index per cached value. The index parses comparable versions and numeric branch prefixes once, groups Minor/Patch candidates by branch, and preserves the first repository spelling when Maven considers versions equal. Dependency lookups read the newest eligible entry; plugin lookups reuse the same ordered history and consume an `ArrayDeque` while checking prerequisites. Parsed data remains bounded by the raw history cache's version/character limits; per-baseline candidate lists are not retained in that cache.

Parity tests compare complete eligible lists and newest selections with the previous algorithm across all modes, shuffled histories, duplicates, equivalent spellings, unstable/dynamic versions, nonnumeric releases and overflowing numeric prefixes. The opt-in CPU fixture includes first index construction, three warmups and five alternating samples with exact result assertions. It measures one history shared by multiple consumers over all three modes, including both dependency selection and plugin eligible-list construction. It excludes Maven goals, HTTP and editor rendering.

| Version history | Consumers | Previous median | Indexed median |
|-----------------|-----------|-----------------|----------------|
| 100 versions | 2 | 6.79 ms | 0.40 ms |
| 100 versions | 100 | 75.25 ms | 1.11 ms |
| 5,000 versions | 100 | 4,223.48 ms | 16.00 ms |

The [raw CPU record](performance-maven-history-2026-10-10.txt) records the fixture conditions. These are local algorithm measurements, not server or production p95 claims. Run with `./gradlew test --tests '*MavenVersionHistoryBenchmarkTest' -PmavenHistoryBenchmark=true`. `MAVEN_VERSION_INDEX` is local preparation; `MAVEN_PLUGIN_PREREQUISITES` counts native candidate-POM goals separately from `MAVEN_METADATA_BATCH` version-history goals.

## Native small-Maven measurements: 10 October 2026

The [native validation record](native-validation-2026-10-10/README.md) contains sanitized traces and reproducible JSON reports. IDEA 2025.3.6.1 completed 105 warm editor endpoints (p95 3.49 ms), 100 cached three-row preview endpoints (p95 43.86 ms) and 100 literal-fix endpoints (p95 5.45 ms). IDEA 2026.1.4 completed 100 per endpoint, with p95 values of 6.11, 34.23 and 6.58 ms. All final editor/preview captures contain no native queries. Fixes initiate no native queries; already running background goals overlap 15 fix intervals in 2025.3 and none in the final 2026.1 capture. The earlier partial 2026.1 capture remains available separately.

These cases use one Maven module and three declarations with a local file repository. They do not establish the complete plan's targets or EDT typing behavior. Native npm validation is blocked by disabled JavaScript/Node support in the available IDEA sandbox, as requested by the user; no usable licensed profile was supplied or verified. Large projects, remaining Gradle and failure/usability cases are still pending.

## Native small-Gradle editor and preview: 10 October 2026

IDEA 2026.1.4 completed 100 cached current-file Patch previews with three Groovy dependency rows: median 31.35 ms, nearest-rank p95 44.28 ms. There were no incomplete previews or native Gradle invocations in the capture. Each dialog was verified ready and cancelled before the next invocation. A separate capture completed 100 warm Major editor endpoints (median 3.08 ms, p95 9.82 ms), with all three hints visible and no native queries. Its 100 ordinary-source activations without Version Checker diagnostics remain counted as incomplete and excluded from latency statistics. Gradle 9.8.0 used SDKMAN Temurin 25.0.3+9-LTS and the same generated local repository as Maven. The [native record](native-validation-2026-10-10/README.md#native-gradle-editor-and-preview-idea-202614) contains archive identities, fixture conditions and sanitized traces/JSON reports. No fix, large-project, Kotlin/catalog, cold/failure or 2025.3 Gradle result is inferred.

## Measurement limits

`FIRST_INSPECTION_RESULT` measures time from a check starting to its first accepted incremental update containing a candidate or notice. Its count is the number of candidates/notices in that delta. It excludes queue wait and does not measure IDEA rendering; compare it with overall `CHECK` and highlighting stages separately.

The earlier five-sample comparisons use small local fixtures with warm native installations and forced metadata revalidation. Maven server startup, setup and OS caches can affect the first sample; npm registry/cache startup can do the same. Five samples are sufficient to catch changed work counts, not to establish a reliable production p95 or a general speed multiplier. The later native record measures small warm Maven editor/preview/fix and Gradle editor/preview cases. Large project workloads, cold installations, private remote repository latency and remaining ecosystems/endpoints still require measurement before choosing broader session or concurrency changes.
