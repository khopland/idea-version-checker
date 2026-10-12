# Inspection/edit native acceptance follow-up

2026-10-11, Apple M4 / Mac16,1, 16 GiB RAM, macOS 27.0. Native current-IDE
checks use IDEA 2026.2.3 (IU-262.10968.63), bundled JBR 25.0.4 and the existing
Node 22.23.1 / npm 10.9.8 configuration. Vendor application bundles are unchanged;
config/system/plugins/log paths are isolated from the user's running IDEA.
The disposable npm workspace uses a loopback metadata-only registry. No install
or tarball operation is available or invoked.

## Failures found and corrected

Archive `60438c7e50baf97054f1340b2eae8e465b96c71999c52445f47aeeaeaf9b991a`
applied two selected declarations but failed while saving with IDEA's
“Access is allowed from write thread only” assertion. The editor text changed
despite the failure notification. The selective saving entry points
now acquire explicit write-intent access, and post-edit follow-up failures
accurately state that text changed. The intermediate generic throwable overload
was rejected by Plugin Verifier on the newer IDEs; the final implementation uses
the compatible `Runnable` overload.

Archive `ff7dc6fb40181d156b09405be931ec7785216fb035ea9df6c5336565b8d72614`
passed native Apply/save and grouped undo, but its trace exposed a feedback loop:
the language-independent Gradle visitor captured an unsupported npm file, then
each null reader requested another capture/highlighting restart. The
[before-fix summary](idea-2026.2.3-npm-before-loop-fix.json) records all 5,827
anonymous stage records over 484.13 seconds, including 2,348 restart records
(the queue and per-file stages both use that stage name). The
[trace excerpt](idea-2026.2.3-npm-input-validation.log) retains the first 200 and
last 60 records. Counts describe a mixed functional session, not idle CPU or a
latency distribution. The correction rejects unsupported Gradle files before
capture and only restarts highlighting when a captured reader has a snapshot.

## Functional observations

On the save-corrected archive above:

- Current File/Patch showed three checked/selected declarations. Keyboard Space
  unchecked alpha; filtering to beta retained two selected changes with one
  hidden selection. A read-only full-file proposed diff was checked on the
  earlier `60438c…` archive; that diff-window observation was not repeated here.
- A same-size, same-timestamp `.npmrc` disk edit made while the review was held
  caused Apply to reject the old plan without changing any dependency. The
  existing review refreshed and retained the filter and compatible selections.
- Switching to Minor kept the same review window, changed targets to 1.1.0 and
  required fresh selection. Whole Project displayed five checked declarations,
  including both workspace-member dependencies.
- With a 15-second registry response delay, Refresh showed pending work with
  Apply disabled. Cancel closed the window. Both observed native `npm view`
  processes were absent in the immediately following process check. This is a
  functional cancellation smoke check, not a ≤2-second latency certification.

The [registry observations](idea-2026.2.3-npm-registry.jsonl) contain only package,
response status and elapsed time. Delayed server responses can finish after their
client process has terminated; they are not evidence of a late plugin result.

The corrected final archive is
`0b3e71ec74054ac9d52acf6037ccd4df1de81ea481340994da0abea501cf8a9f`.
After restarting IDEA with that archive, native review again showed three checked
and selected declarations. Apply closed the review and saved alpha `^1.0.1`,
alias `npm:@fixture/alpha@~1.0.1` and beta `1.0.1` to disk. One native undo restored
all three selectors; saving restored the fixture baseline. Member selectors
stayed unchanged, `node_modules` remained absent, and lockfile SHA-256 remained
`c3ea3f47c785d8db62680ca0d5fc63c1a3ce47867efcd9d42f2b67f7c1bafb9c`.

The [final-archive anonymous trace](idea-2026.2.3-npm-final.log) records startup,
review and Apply/undo. A subsequent [107.41-second idle smoke observation](idea-2026.2.3-npm-idle-smoke.json)
recorded **zero** plugin stage records: no snapshot, highlighting restart or
repository query. This verifies the observed feedback loop stopped; it does not
replace the planned 15-minute baseline CPU/heap measurements.

Full validation of this archive passed **493 plugin tests in 68 classes**, all
native integration flags, 13 release tests, 16 script tests, packaging and all
three IDEA verifier targets. The explicit write-intent references increase the
experimental API baseline from 10 to 14; all targets remain binary-compatible
with no deprecated/scheduled-for-removal usages. See the
[engineering record](../inspection-edit-inputs.md) for commands and limits.

## Minimum IDE and remaining gates

IDEA 2025.3.6.1 was relaunched from an unchanged vendor bundle with isolated
configuration and the Ultimate module removed from the explicit disabled list.
IDE startup put that module back in the disabled list and logged that JavaScript
and TypeScript could not load because its Ultimate dependency was disabled.
Native npm remains blocked by the available minimum-IDE capability. No trial,
license agreement or workaround was accepted. This attempt is not a pass.
The user confirmed that no licensed IDEA 2025.3 installation/profile is available
and instructed that minimum-IDE npm be recorded as **blocked**.

**Scope update, 2026-10-12:** the user subsequently excluded native npm on IDEA
2025.3 from release acceptance. The blocked attempt above remains historical
evidence, not an outstanding release gate. Newer-IDE npm acceptance and automated
integration tests remain in scope.

Controlled 100-module/dense-file typing, EDT/write spans, slow-disk/large init
trees, 15-minute IDE-baseline idle CPU, import/rename/unload heap observations,
and the remaining supported-IDE failure/accessibility matrix remain open.
Gradle aggregation stays off; the version stays `1.3.0-SNAPSHOT`. RC/EAP identity
and exact-release archive acceptance precede any stable promotion.
