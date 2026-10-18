# Remaining acceptance work — 18 October 2026

This continues the [17 October review](../2026-10-17-review/README.md).
The interactive matrix is **not complete**. Earlier native dialogs intermittently
opened behind the project window; fresh launches allowed the continuation below
to complete successful npm synchronization, Undo and export on both supported
IDEs. Normal user profiles were not modified. Windows smoke remains blocked by
the lack of a Windows host.

## Changes resulting from the checks

- Add opt-in `--installable` npm fixture packages. The default remains metadata
  only. Package archives have integrity hashes, no dependencies or lifecycle
  scripts, and are served only from loopback with explicit path allowlists.
- Add **Preview npm lockfile sync** to retained npm session summaries. It passes
  that summary's session token, so retrying an older session cannot silently use
  the latest session's token. Existing trust, input and command preview checks
  still apply; the summary closes before opening the chooser.
- Schedule the export save dialog through IntelliJ's non-modal UI scheduler.
  Accessibility activation of the previous toolbar action produced a platform
  write-unsafe-context error inside the file chooser's VFS refresh.
- Replace the stale “command not yet started” session text when execution is
  requested. Completion, inspected outputs and dependency verification remain
  unobserved until separately reported.

The session retry button and final export scheduling were exercised on both
supported IDEs with the current archive. The live running-status text still
needs a delayed-command observation on the current archive.

## Continuation: document retention and successful native synchronization

Current production source: `9059c7f5dbd0af5f9f8ac9af1fd7732a10e59934`.
Archive: `retained-document-candidate.zip`; SHA-256:
`ddfc150a75b8b786b6054dba975f675c060d1f98833820728f0cb2c1cb8f19ed`.
Both isolated profiles were cleanly installed and byte-compared against this ZIP.
This supersedes the earlier candidate below.

Forced soft-cache eviction reproduced the intermittent 10,000-declaration Maven
Apply rejection with unchanged VFS stamps, current snapshots and valid edits.
The saved-input guard held document stamps but allowed the clean documents to be
collected. It now retains captured documents for its short lifetime and checks
both identity and stamp. The regression forces eviction with IntelliJ GCUtil
between capture and Apply. It failed before the fix and passes afterward; the
unsaved sibling invalidation still passes. See the
[diagnosis record](document-retention-regression.json).

Serial validation passed: 729 main tests with all three native integrations,
six Maven 4 integration tests, packaging/configuration, and both IDE verifiers.
The 20 Python fixture tests were unchanged and previously passed.

On both IDEA 2026.1.4 and 2026.2.3, native dialogs responded after fresh launches. The current ZIP
applied five root/workspace selectors across two manifest files. The retained
session action opened the exact lockfile-only npm command, which completed and
changed the root lockfile. Declaration Undo and Save restored both manifests to
their baseline hashes while preserving the lockfile write. The session continued
to report verification Unknown and explained that installed dependencies were
not synchronized. Byte evidence for [2026.1.4](successful-sync-261.json) and
[2026.2.3](successful-sync-262.json), plus exports for
[2026.1.4](export-261-ddfc.json) and [2026.2.3](export-262-ddfc.json),
record this separately from older runs. The [current validation record](continuation-validation.json)
pins tests, CI and installed files. Both latest startups had no SEVERE/ERROR
entries when checked. The registry and isolated IDE processes were stopped; all
six baseline fixture files were restored.

The complete native matrix is still pending. In particular, these observations
do not certify the remaining Maven/Gradle flows, repeated timing measurements,
old-session attribution across multiple sessions, or Windows.

## Earlier candidate and automated verification

Source: `30651df877b51ca52eb9a6820169a6001ab7d755`, on merged #56 (`74142e4`).
Archive: `final-candidate.zip`, copied from `build/distributions/version-checker-1.3.0.zip`.
SHA-256: `080af34d75db44dcf964a62ab5f2996d0f8fbe933cc8566392068d7e1ee8c4b5`.
The [machine-readable record](validation.json) contains installed-file hashes,
startup excerpts, test counts and the explicit remaining cases.

- 729 tests passed with native Maven/npm/Gradle integrations enabled, zero
  failures/errors/skips.
- Six Maven 4 integration tests passed using 4.0.0-rc-7 on IDEA 2026.2.3.
- Packaging, project configuration and Plugin Verifier passed for IDEA 2026.1.4
  and 2026.2.3. Existing notices remain: 7/9 deprecated API usages, respectively,
  25 experimental usages on each, and the bounded compatibility-range advisory.
- 20 Python tests passed. The installable fixture test runs real npm install,
  updates only the lockfile after changing beta, checks that installed beta stays
  at its old version, then runs npm ci and checks the new package is installable.
  This verifies the fixture and native npm semantics, not the plugin's UI path.

This earlier candidate was cleanly extracted into both closed isolated IDE profiles
and every installed file compared with the ZIP. It was subsequently launched on IDEA 2026.2.3: JSON export completed with no new
SEVERE/ERROR entries. See [export](export-262-080.json). Earlier `a9c31d73…` startups
confirmed plugin 1.3.0 on both IDEs, with no SEVERE/ERROR entries at collection
time; these observations are recorded separately and not transferred to the
final ZIP. Runtime/environment
remains macOS 27.0.1 / Mac16,1; bundled JBR 25.0.3 and 25.0.4 respectively;
SDKMAN Java 21.0.12 for Gradle; Node 22.23.1 / npm 10.9.8.

An overlapping validation attempt was stopped and excluded. Final validation was
serial; see [excerpts](automated-excerpts.txt). Two additional diagnostic full
runs passed without reproducing the previously observed 10,000-declaration Maven
Apply rejection. Temporary instrumentation was removed. The subsequent continuation below diagnosed and fixed the scaling rejection.
No input guard or assertion was weakened.

## Intermediate native observations

These results belong to their recorded archives and are **not transferred** to
the final candidate above. Raw screenshots were inspected in the tool transcript
but not exported. Portable records retain observations, exports and byte hashes.

| Case | IDEA 2026.1.4 | IDEA 2026.2.3 | Archive |
| --- | --- | --- | --- |
| External input change | Held Apply rejected/rechecked; external beta edit preserved, no stale writes. | Same result. | `d87902a1…` |
| Unsaved workspace child edit | Open declaration, add whitespace, return to held preview; Apply rechecks and preserves editor text. Undo/save restores baseline. | Same result. | `d87902a1…` |
| Rapid mode changes during delayed checks | Major → Patch → Major; Apply disabled during checking; final major targets shown. | Same result. | `d87902a1…` |
| Export/expiry | Midway export preserved original deadline; observations disappeared and final export was empty after expiry. | Not run. | `d87902a1…` |
| Session after project restart | Not run. | No retained session available. | `cc01d712…` |
| Session retry action | Not run. | Opens manifest chooser and exact command preview for session #1. | `cc01d712…` |
| Cancel before synchronization starts | Not run. | Session records cancelled-before-start; verification Unknown. | `cc01d712…` |
| Registry denial during synchronization | Not run. | Failed exit 1; no inspected lockfile changes; partial-write warning; verification Unknown. | `cc01d712…` |
| Cancel running synchronization | Not run. | Cancellation requested, termination unconfirmed; no inspected changes; partial-write warning; verification Unknown. | `cc01d712…` |

[Input byte evidence](input-evidence.json), [original-age expiry exports](historical-expiry.json)
and [intermediate session/sync evidence](sync-262-intermediate.json) identify the
archives and observed boundaries. The expiry deadline stayed approximately
08:37:45 UTC across exports at 08:29:22 and 08:34:55; the 08:37:51 export was empty.
An unrelated built-in status-bar accessibility implementation error occurred
when attempting to activate its process panel. These platform/automation errors
are retained as limitations, rather than attributed to successful plugin checks.

## Remaining matrix

For both IDEs on the current ZIP, the following remain pending: optional-integration
startup; complete keyboard/toolbar traversal and theme/zoom pass; expiry and
held-preview expiry; malformed catalog/zero-match coverage; rapid scope changes
and late completion; Maven/shared-catalog apply and Undo; external/unsaved inputs;
inherited Maven reload and effective-version verification; broken/disconnected
reload; adverse npm synchronization phases and delayed running-status observation;
retained older-session actions and project reopen; three cold/warm small/large
interaction measurements. The successful npm Apply/synchronization/Undo and JSON
export cases are complete on both IDEs. Earlier candidate results remain
historical. Automated coverage does not substitute for the remaining
[native matrix requirements](../../ide-acceptance.md).

Fixture command for the successful npm case:

```sh
python3 scripts/create-native-npm-fixture.py build/acceptance/npm-installable --installable
```

Use fresh paths. Delay/authentication controls remain in `registry-control.json`.
The plugin's command updates only lockfiles; declaration Undo must not claim to
undo those native writes. Successful plugin synchronization and that native Undo boundary were subsequently
observed on both supported IDEs with the current ZIP, as recorded above.

The acceptance IDE processes and loopback registry were stopped after collecting
the record. All six original fixture manifest/lockfile byte sequences were
restored. Isolated profiles and archives remain locally available for resuming.
