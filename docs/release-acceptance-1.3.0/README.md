# 1.3.0 stable review candidate

Prepared 12 October 2026; recorded and rechecked 13 October. The user chose
`1.3.0` for stable review instead of `1.3.0-rc.1`. This is local preparation;
no tag, GitHub release or Marketplace upload was created.

## Artifact and automated checks

Archive: `build/distributions/version-checker-1.3.0.zip`  
SHA-256: `2efe6e5e13226c43ba4ed883b8d98f24f844a632c7aeceab04d383bedff08384`

The packaged descriptor declares `1.3.0` and minimum build `253.33813.55`.
Its notes contain the dated 1.3.0 changes, including physical source validation.
Stable tagged metadata returns `channel=default`, `releasable=true`.
The [machine-readable summary](artifact-validation.json) records the archive,
test totals and verifier verdicts. `build/distributions/SHA256SUMS` checks the
local ZIP; it is not a checksum for a future CI or signed archive.

On 12 October, the full run passed **507 plugin tests in 69 classes**, zero
failures/errors/skips, with Maven/npm/Gradle integration enabled, packaging,
project-configuration validation and all three Plugin Verifier targets.
Python suites passed **13 release tests and 19 script tests**.

On 13 October, the same command passed again in 1m 14s. Gradle reused the test
and packaging outputs; project configuration and all three binary verifications
ran again. Python suites and stable tagged metadata were rerun. The ZIP hash
remained identical. Each verifier target (2025.3.6.1, 2026.1.4, 2026.2.3) is
compatible, retaining the existing **14 experimental API usages**; no deprecated
or scheduled-for-removal usages were reported. Build Java is SDKMAN Temurin
21.0.12+1.1. Local reports remain in `build/reports` and `build/test-results/test`;
the resumed run log is `build/reports/release-preparation/validation-2026-10-13.log`.

The final pre-push run on 13 October explicitly reran the full plugin test task
with all native integration flags enabled: **507 tests in 69 classes**, zero
failures/errors/skips. Packaging, project configuration and all three verifier
targets passed in 2m 3s; the Python suites and stable tagged metadata also passed.
The ZIP checksum remained unchanged. Log:
`build/reports/release-preparation/final-before-push-2026-10-13.log`.

```sh
python3 -B -m unittest discover -s .github/scripts -p 'test_*.py'
python3 -B -m unittest discover -s scripts -p 'test_*.py'
python3 .github/scripts/release_metadata.py --tag 1.3.0 --prerelease false
./gradlew check buildPlugin verifyPluginProjectConfiguration verifyPlugin \
  -PmavenIntegration=true -PnpmIntegration=true -PgradleIntegration=true --console=plain
(cd build/distributions && shasum -a 256 -c SHA256SUMS)
```

## Physical source correction

A real-disk Gradle regression reproduced a held plan accepting an external
build-file edit before VFS refresh, despite unchanged length and timestamp.
The regression failed before the correction. Edit validation now compares fresh
physical source bytes with the IDE's saved baseline in cancellable background
work before entering the atomic command. Gradle retains separate saved hashes;
Maven/npm use a shared saved-input capture. Existing result, document, settings,
import and shared-consumer guards still run before changes.

Regressions cover unnoticed build/POM/workspace-manifest changes, tracked settings
changes, deleted inputs, atomic rejection across selected files, legitimate
unsaved text, changes during capture, and BOM/CRLF normalization. Physical reads
in the shared capture are asserted to run outside EDT and read locks.

## Native observations on the exact ZIP

12 October, Apple M4 / Mac16,1, 16 GiB RAM. Vendor IDE bundles were cloned into
disposable `.app` bundles with separate config/system/plugins/log paths. The
normal user installation/profile was unchanged. Gradle aggregation was **off**.
Startup logs confirmed **Version Checker (1.3.0)** before these accepted checks.

| IDE | Workflow | Observed result |
| --- | --- | --- |
| 2026.2.3 | Dense Groovy Gradle review | Exactly 1,000 patch updates, 1.0.0 → 1.0.1. Saved bytes matched the expected replacements; one undo/save restored the exact baseline bytes. |
| 2026.2.3 | Held Gradle review after external source edit | First selector changed physically to 1.0.2 with the original size/mtime. Apply left that change and the other 999 original selectors intact; zero 1.0.1 replacements occurred. |
| 2026.2.3 | Typing during delayed Gradle check | A fixture-only 15-second configuration delay kept background checking active. All 100 individual letter keystrokes were saved in a comment; versions remained original. IntelliJ's Typing Latency Report showed average 2 ms, maximum 16 ms and 90th percentile 4 ms. Fixture delay/comment were removed afterward. |
| 2026.2.3 | Maven current-file review | Three patch choices, three saved dependency edits, one grouped undo/save. POM baseline hash restored. |
| 2026.2.3 | npm local/workspace review | Three root choices and five workspace choices across two manifests. Apply preserved range/alias syntax; one undo restored both manifests. Lockfile unchanged; all baseline hashes restored. |
| 2025.3.6.1 | Gradle review and actual Alt+Enter fix | Three patch edits saved and undone. Actual diagnostic offered alpha 2.0.0; the local fix changed only alpha, then undo/save restored original editor text. |

An initial installation attempt extracted the stable ZIP over an old snapshot
directory and left both plugin JAR versions present. The IDE reported
`1.3.0-SNAPSHOT`; its external-edit Apply attempt overwrote the fixture. This is
**invalid candidate evidence**, not a test of the stable source correction.
The profile was stopped, its mixed plugin directory retained separately, and a
fresh plugin directory was populated from the stable ZIP before the checks above.

Native observations are retained here from the tool transcript. Temporary
profiles, fixture baselines and raw native logs were cleared between sessions;
no raw trace export survives for this candidate. The resumed session has no
native UI automation tool. Earlier [dense snapshot traces](../native-validation-2026-10-12/README.md)
remain separate historical evidence and are not relabeled as stable-ZIP traces.

The typing report is one functional run, not a repeated p95/input-delay study.
It does not measure typing during the atomic bulk write. Repeated native scale,
slow-disk/init-tree, idle/retained-heap, and broader DSL/catalog/failure/accessibility
acceptance remain uncompleted.

## Remaining before publication

1. **Finish exact-ZIP Maven native smoke on IDEA 2025.3.6.1.** This final pass
   was interrupted before opening Maven. Earlier snapshot Maven acceptance and
   the passing minimum-platform integration tests do not replace it. A fresh
   isolated setup is ready under `build/release-review`: run
   `./build/release-review/open-minimum-maven.command`, trust the generated local
   fixture, wait for import and open `pom.xml`. Confirm Version Checker 1.3.0 is
   loaded. Review Current File/Patch: expect three dependency changes to 1.0.1,
   with the project version unchanged. Apply/save, then undo/save once and compare
   the POM with `build/release-review/minimum/pom-baseline.xml`. Refresh and confirm
   a dependency offers the Version Checker action through Alt+Enter; apply/undo
   it. Record the outcome and check that no plugin error occurred. This setup
   never changes the normal IDEA profile; it has not been launched in the resumed
   session. Native npm on 2025.3 is excluded at the user's request.
2. **User stable review and CI delivery.** Review the ZIP/notes, push or merge the
   release preparation to `main`, and require the Build workflow to pass for that
   commit. Check the matching draft's commit, notes and tested ZIP. Confirm
   `PUBLISH_TOKEN` is configured without disclosing its value. Publishing the
   stable draft then triggers the Release workflow and Marketplace's `default`
   channel; optional signing can produce a different archive/checksum. Follow
   [the release pipeline](../releasing.md). None of these delivery actions has
   been performed here.

The candidate is prepared for review; the pending native check and broader
measurement gaps remain explicit. These local checks do not certify complete
native acceptance or readiness for publication.
