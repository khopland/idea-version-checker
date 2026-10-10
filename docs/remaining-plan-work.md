# Remaining-plan implementation

Source: [10 October remaining-work plan](https://plan-api.k8r.no/p/4kY_bfKP7caOrUP_mCSwVhxY).

## Delivered code

- Opt-in anonymous interaction tracing propagates through coroutine dispatch, npm metadata/runtime workers, the coordinator and editor restart scheduling. Events include monotonic interval endpoints, so nested stages can be partitioned instead of summed. First accepted useful results, complete checks, preview plan preparation, displayed previews and changed editor text have distinct milestones.
- Gradle records host setup and total native execution. A debug-only temporary file carries configuration/query durations measured inside the Gradle JVM; buffered console delivery does not determine them. The file contains two numeric durations and is deleted with the native task inputs. Those durations are placed at the end of the host invocation for visualization; their absolute placement is approximate, and startup/tooling return overhead remains outside them.
- Selected-file activation has a native diagnostic endpoint. It requires a Version Checker inspection highlighter intersecting the visible viewport and completes an explicit editor paint. Warm installed markup is probed after selection; new markup is probed after daemon completion. Debug mode adds this paint and log overhead. Headless tests cannot certify this endpoint or screen compositor latency.
- Routine Maven/npm/Gradle hints lead with artifact, update kind and the declared version/selector change. Maven plugin/parent identity and npm selector operators remain visible; notices and manual-review explanations retain their detail.
- Maven shared/property/parent actions show known version-declaration and imported-POM counts. Scope labels are computed when an action name is requested, keeping project-wide traversal out of routine hint construction. Counts include effective inherited property consumers, exclude local version overrides and retain imported-owner/profile guards. Unknown scope is labelled for review rather than guessed. Counts describe supported version declarations, not arbitrary uses of a property elsewhere.
- Refresh, preview, retry and post-apply saving preserve unrelated unsaved editing. Maven saves participating imported POMs, settings and ancestor `.mvn` inputs. npm saves the workspace manifests and relevant registry/runtime configuration. Gradle saves linked-build scripts/catalogs/properties/wrapper/locks/verification inputs and known user-home properties/init scripts. After apply, only selected edit files are saved. A changed mode/scope saves that selection's required inputs before rediscovery.
- An Alt+Enter action ignores one exact artifact/version within this IDEA project's workspace settings. npm aliases share their registry identity. Both inspection presentation and bulk review honor the policy, while raw cached results and deprecation/relocation/manual-review notices remain available. Later releases and other artifacts remain visible. Remove the corresponding line in Settings → Tools → Version Checker to restore the update. Changed ignore policy invalidates prepared previews and retained version fixes; Maven shared edits cannot change an ignored artifact through another artifact's property update.
- Preview controls have readable mode/scope labels and label mnemonics. Tab/Shift+Tab enter and leave the table, and Space toggles highlighted rows using model indices after sorting/filtering. Hidden selections stay in the summary, and highlighted rows remain selected for repeated keyboard toggles. Update actions precede exact-release Ignore actions in Alt+Enter.

Selective saving follows inputs known to each supported adapter. Arbitrary files read by custom build scripts, convention-plugin source, custom Maven extensions or user tooling are not a complete general-purpose build-input graph. Save those inputs explicitly when their changes affect resolution. They are outside the adapters' current automatic-edit scope.

## Native validation status

The [native record and sanitized traces](native-validation-2026-10-10/README.md) now cover the small, warm, current-file Maven cases in IDEA 2025.3.6.1: 105 editor, 100 preview and 100 literal-fix endpoints. Their p95 values are 3.49, 43.86 and 5.45 ms respectively. Preview and editor captures have no native queries. Fixes start no native queries; preexisting background queries overlap 15 fixes during the undo/restore loop. IDEA 2026.1.4 has 76 partial local-fix endpoints and verified readable labels/action ordering. Native testing also corrected preview Tab traversal and verified filter/Space/hidden-selection behavior in 2025.3.

**Native npm validation is blocked:** JavaScript/Node support is disabled in the available IDEA sandbox. No usable licensed profile was supplied or verified; the user requested recording this blocker. Passing headless npm integration tests do not replace native validation.

| Native work | Status |
|-------------|--------|
| Small warm Maven editor/preview/literal fix, 2025.3.6.1 | Sample counts and latency recorded; globally idle fix capture remains to confirm |
| Small warm Maven, 2026.1.4 | Partial fix capture; editor/preview and 100-sample completion pending after native input interruption |
| Large Maven, roughly 100 modules / 10,000 declarations | Reproducible fixture generated and checked; native measurements pending |
| Gradle, both IDEA versions | Native performance/usability cases pending |
| npm, both IDEA versions | Blocked by disabled JavaScript/Node support in available sandbox |
| Cold/setup, delayed/offline/authentication, shared fixes and typing profiler | Pending |
| Remaining native keyboard/focus and recovery cases | Pending; findings and corrections recorded per version |

The measured cases do not establish the full plan's latency targets. Headless platform, native repository integration and packaged-plugin lifecycle tests establish behavior and safety; they do not replace the outstanding native sessions below.

Run both IDEA 2025.3.6.1 and 2026.1.4 with the packaged plugin. Enable the debug category documented in [performance.md](performance.md). Use separate captures for each ecosystem, project size, scope, mode and cache state. Record the IDEA/JDK/Node/Gradle/Maven versions, module/workspace count, declaration count, repository latency and update mode with each case.

1. Warm editor: alternate from an ordinary source tab to a dependency file with current cached hints, at least 100 times. Include small, roughly 100-module/workspace and 10,000-declaration projects. Capture FILE_ACTIVATED → DIAGNOSTIC_VISIBLE and record whether the relevant declaration is in the viewport.
2. Warm preview: invoke a current-file preview using current same-mode reports, up to 100 rows, at least 100 times. Capture PREVIEW_INVOKED → PREVIEW_READY. Confirm zero native goals/npm commands/Gradle tasks. Cancel between samples.
3. Local fix: invoke a literal/property selector fix, verify changed editor text, undo, restore a current hint and repeat at least 100 times. Capture FIX_INVOKED → EDITOR_TEXT_CHANGED. Confirm zero native requests inside the fix. Report workspace/parent/shared changes separately.
4. Cold/setup: use independent captures with cold caches; record first useful acceptance and complete CHECK separately. Include delayed repositories, one slow npm package, offline mode and authentication failure.
5. Typing: edit ordinary source and dependency files while checks run. Use IDEA's performance profiler to identify EDT stalls of 50 ms or more and report inspection CPU separately. Debug paint/logging overhead must be acknowledged or compared with tracing disabled.
6. Native usability: exercise label mnemonics, Tab traversal, Space toggles, sorting/filtering, hidden selections, empty previews, repeated preview focus, scope/mode changes, stale rejection, Refresh This File and failed-file Retry. Verify focus and readable slow/offline/authentication status in native windows. Record any issues and corrections per IDEA version.

Generate a sanitized report for one condition at a time:

```sh
python3 scripts/summarize-performance.py /path/to/idea.log \
  --case 'npm-small-current-patch-warm' --output /tmp/npm-warm-summary.json \
  --ecosystem npm --scope current-file --mode patch --cache warm \
  --modules 1 --declarations 25 --idea-version 2025.3.6.1 --repository-case fast-local
```

The report includes completed endpoint sample counts, nearest-rank p95, median, exclusive stage partitions, and separate stage distributions for first useful acceptance and complete checks. Incomplete interactions are excluded from endpoint statistics. It flags fewer than 100 endpoint samples. Counts alone cannot prove a case was warm: document cache state and check native invocation stages. Interaction ID zero is uncorrelated and excluded; IDs are grouped with their monotonic origin to distinguish application restarts. Other IDEA log lines are not exported.

## Conditional work

Linked-build Gradle batching, Maven plugin branch reduction and cross-module embedder/session reuse remain deferred. Choose an experiment only after native traces show the corresponding repeated work dominates, then compare exact candidates, repository/profile/authentication behavior, cancellation and lifecycle as well as elapsed time. No native resolution semantics have been changed for these experiments.

## Local validation: 10 October 2026

The final run passed 346 plugin tests with zero failures, errors or skips, including authenticated native Maven/npm/Gradle resolution and packaged-plugin unload/reload coverage. `test`, `check`, `buildPlugin`, `verifyPluginProjectConfiguration` and `verifyPlugin` passed. Both IDEA 2025.3.6.1 and 2026.1.4 passed compatibility verification with the existing experimental progress API notices and the existing 2026.1 deprecated read-action notice. The three trace-report tests and thirteen release-automation tests also passed.

New regressions cover selective saving of sibling/configuration inputs while preserving unrelated edits, serialization and removal of exact-release ignores, npm aliases and cached/bulk presentation, Maven snapshot identity filtering, stale policy guards, ignored Maven consumers sharing a property, scope counts, sorted/filtered keyboard toggling, coroutine trace context, document markup visibility and overlap-safe median/p95 reporting. The Maven native demo fixture now initializes its mapped model properties before resolving inherited DOM values. Existing native inspection assertions include both property and declaration diagnostics and distinguish update actions from ignores.

These results validate the implementation, including the native-discovered dialog/action corrections. Small Maven native measurements and their limits are recorded above; the rest of the performance/usability matrix remains pending or explicitly blocked.
