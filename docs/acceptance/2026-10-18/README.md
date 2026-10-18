# Remaining acceptance work — 18 October 2026

This continues the [17 October review](../2026-10-17-review/README.md).
The remaining interactive matrix is **blocked**, not certified complete. Native
dialogs intermittently opened behind the project window and could not reliably
be focused through the available macOS automation. Restarting isolated IDEs did
not resolve this consistently. An EDT dump confirmed a hidden session dialog;
the available UI snapshot exposed only its parent. Normal user profiles were
not modified. Windows smoke is separately blocked by the lack of a Windows host.

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

The session retry button was exercised on an intermediate archive. Native
confirmation of the final export scheduling and live-status changes is blocked.

## Final candidate and automated verification

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

The final candidate was cleanly extracted into both closed isolated IDE profiles
and every installed file compared with the ZIP. It has not been launched after
the final queued-export expiration guard was added. Earlier `a9c31d73…` startups
confirmed plugin 1.3.0 on both IDEs, with no SEVERE/ERROR entries at collection
time; these observations are recorded separately and not transferred to the
final ZIP. Runtime/environment
remains macOS 27.0.1 / Mac16,1; bundled JBR 25.0.3 and 25.0.4 respectively;
SDKMAN Java 21.0.12 for Gradle; Node 22.23.1 / npm 10.9.8.

An overlapping validation attempt was stopped and excluded. Final validation was
serial; see [excerpts](automated-excerpts.txt). Two additional diagnostic full
runs passed without reproducing the previously observed 10,000-declaration Maven
Apply rejection. Temporary instrumentation was removed. That intermittent
scaling failure remains undiagnosed; no input guard or assertion was weakened.

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

For **both IDEs on the final ZIP**, the following remain blocked by unreliable
native dialog focus: optional-integration startup; complete keyboard/toolbar
traversal and final theme/zoom pass; expiry/export and held-preview expiry;
malformed catalog/zero-match coverage; rapid scope changes and late completion;
Maven/npm/shared-catalog apply and undo; external/unsaved inputs; inherited Maven
reload and effective-version verification; broken/disconnected reload; npm's
complete synchronization lifecycle including successful writes; retained older
session actions and project reopen; three cold/warm small/large interaction
measurements. Earlier candidate results remain historical. Automated coverage
does not substitute for these [native matrix requirements](../../ide-acceptance.md).

Fixture command for the successful npm case:

```sh
python3 scripts/create-native-npm-fixture.py build/acceptance/npm-installable --installable
```

Use fresh paths. Delay/authentication controls remain in `registry-control.json`.
The plugin's command updates only lockfiles; declaration Undo must not claim to
undo those native writes. Successful plugin synchronization and that native Undo
boundary have not yet been observed on the final ZIP.

The acceptance IDE processes and loopback registry were stopped after collecting
the record. All six original fixture manifest/lockfile byte sequences were
restored. Isolated profiles and archives remain locally available for resuming.
