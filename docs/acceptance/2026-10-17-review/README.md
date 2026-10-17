# Interactive review acceptance — 17 October 2026

The narrow-window and high-zoom review pass found that two-column filters could
collapse selectors, while the clear button consumed most of the search row under
Islands themes. Filters now occupy full-width rows, the clear icon follows runtime
zoom, and a toolbar toggle hides/restores the filter fields to free result space.
Search and selected filters are preserved. Hiding filters performs no checks.

This record covers the final ZIP built from `9602fbec692784d6e144fd7500dbacc37464e2a9`,
on merged #55 (`1108e43`). Archive: `review-candidate.zip` (a copy of
`build/distributions/version-checker-1.3.0.zip`). SHA-256:
`d87902a13ba68eeffcb628f49703a5257995d8b37e6dfe90ed93c05f072a62a8`.
The [machine-readable evidence](validation.json) includes installed-file hashes,
startup excerpts, fixture byte comparisons and registry request counts.

## Automated verification

729 tests passed, with zero failures, errors or skips, including native Maven,
npm and Gradle integration. Packaging, project configuration and Plugin Verifier
passed for IDEA 2026.1.4 and 2026.2.3. Verifier retains 7/9 deprecated API usages
respectively and 25 experimental usages on each target, plus the existing bounded
compatibility-range advisory. See [log excerpts](automated-excerpts.txt).

The layout regression checks usable search/selector width at 400 and 200 unscaled
pixels, preserved filtering when fields are hidden/restored, increased result
space, and zero provider reads. The stronger width assertion failed before the
compact clear-button fix. An accidentally overlapping pair of Gradle runs produced
invalid shared outputs; those runs are excluded. The final run was serial and
completed successfully. Maven 4 was not rerun locally for this candidate.

## Native checks on the exact ZIP

Both official IDEs ran on macOS 27.0.1 / Mac16,1 in disposable app bundles with
separate config/system/plugins/log directories. Every installed plugin file was
compared byte-for-byte with the final ZIP. Logs confirm Version Checker 1.3.0.
Bundled runtimes were JBR 25.0.3 (2026.1.4) and 25.0.4 (2026.2.3); Node 22.23.1
and npm 10.9.8 served the native npm workflow. These are warm fixture/profile
checks after restarts for the layout fixes, not cold-performance measurements.

| Scenario | IDEA 2026.1.4 | IDEA 2026.2.3 |
| --- | --- | --- |
| Narrow layout, Islands Light/Dark, 100%/200% IDE zoom | Passed all four combinations. | Passed all four combinations. |
| Filter visibility at high zoom | Hidden fields free result space; search remains available; rows and wrapping details visible. | Same observed result. |
| Keyboard discovery | Find Action opens view; Cmd+F searches; Tab/Space clears; keyboard selects npm/root, row details and Enter opens source with editor focus. | Same observed result. |
| Passive filtering | Registry requests 46 → 46 across the recorded sequence. | Registry requests 49 → 49. |
| Denied registry and retry | Existing preview retained; credential guidance shown; Apply disabled. Refresh after restoring access returns ready. | Same observed result. |
| Subset apply | Filter beta, clear selection, select visible: one of five declarations. Only root beta changes 1.0.0 → 2.0.0. | Same observed result. |
| Undo/save | One Undo restores exact original root manifest; child manifest and lockfile remain unchanged throughout. | Same observed result. |
| Delayed-check cancellation | Checking shown with Apply disabled; Cancel closes preview; all three fixture files retain original bytes. | Same observed result. |

The tool window was approximately 400 logical pixels wide at normal zoom; at
200% its available unscaled content width is roughly 200 pixels. Column labels
and values elide at that width; selected details provide full values. Use the
filter toolbar button to free vertical space after choosing filters. The native
toggle was exercised with accessibility-driven pointer activation; a complete
keyboard-only toolbar traversal remains unverified. Core keyboard navigation
above was exercised on both IDEs. No screen reader certification is implied.

Fixture reproduction:

```sh
python3 scripts/create-native-npm-fixture.py build/acceptance-review/npm
# Open this project in each isolated IDE; use a separate copy for the second IDE.
# registry-control.json: deny=true for failure; delayMs=10000 for cancellation.
```

The registry serves metadata only. No successful installation, lockfile sync or
application build is claimed. Local fixture/profile evidence remains under
`build/acceptance-2026-10-17-review`; raw screenshots were inspected in the native
tool transcript but were not exported. Portable evidence here consists of the
recorded observations, sanitized log excerpts, byte hashes and request counters.

The final startup intervals contain no ERROR-level log entries; the expected
denied-registry check logs an npm failure warning. Earlier intermediate sessions
showed platform error notifications. A hidden IDE trust dialog also required
restarting the isolated 2026.2 profile and trusting only the generated fixture.
These environmental observations do not establish a clean full-IDE acceptance.
The test IDEs and fixture server were stopped; fixture bytes were restored and
the temporary chooser symlink removed. Normal IDE profiles were untouched.

## Remaining release acceptance

The [full matrix](../../ide-acceptance.md) remains incomplete. Native Maven/Gradle
cases on this ZIP, cache expiry/export, malformed coverage, rapid mode/scope and
late completions, external/unsaved inputs, reload failures, npm synchronization,
retained-session lifecycle and repeated cold/warm measurements are **not run**.
Previous candidate results remain historical and are not transferred here. This
is a bounded interactive review pass and layout fix, not release approval.
