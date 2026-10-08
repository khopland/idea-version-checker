# Version Checker 1.2.0 readiness

Validated locally on 2026-10-09, macOS arm64, Java 21, Node 22.23.1 and npm 10.9.8. The source version and finalized changelog are 1.2.0. The packaged descriptor includes that version, the new change notes and Apache license.

## Release decision

The implemented performance and lifecycle changes are ready for the next release. They reduce native request counts and repeated local input collection while preserving authenticated native resolution, stale-edit rejection, cancellation and plugin disposal. Build and release CI now enable all three native repository integrations with a pinned Node fixture runtime.

The final full local validation passed:

- 233 plugin tests: zero failures, errors or skips, including native Maven (4), npm (3), Gradle (3), packaged-plugin lifecycle (4) and runtime-session ownership (6) tests.
- 13 release automation tests.
- `check`, `buildPlugin`, `verifyPluginProjectConfiguration` and `verifyPlugin`.
- Compatibility with IDEA 2025.3.6.1 and 2026.1.4. The existing seven experimental progress API usages remain; 2026.1.4 also reports the existing deprecated read-action usage in highlighting refresh.
- Stable release metadata validation for tag `1.2.0`; no tag or release was created locally.
- ZIP descriptor/version, change notes and license inspection.

The new native shim fixture makes twelve runtime requests during one active check session and observes one probe. Refresh and subsequent sessions each resolve again. Native cancellation and timeout tests verify that the process exits. Shared-worker tests verify that one cancelled caller cannot stop work needed by another, while the last caller and disposal cancel setup.

The reproducible full validation command and delivery steps are in [releasing.md](releasing.md). Test reports are in `build/reports/tests/test/index.html`; compatibility reports are in `build/reports/pluginVerifier`.

## Pre-release correctness review

The [8 October review](https://plan-api.k8r.no/p/gvQXy93C5-ZrVtU5k5V1wNQT) identified four defects at the earlier `c711730` commit. All four were repaired before release; its CI recommendation had already been implemented.

- Maven root property references now use their unique active POM profile owner. Equal baseline values cannot select the shadowed default. Multiple active owners withhold property/shared edits; inactive overrides do not block the default. Quick fixes and bulk usage checks share the ownership decision. Native effective-POM checks confirm both equal and different default/profile values update the actual dependency version, preserving the default.
- Scheduled npm eligibility uses an adapter hook for physical resolution input paths, alongside existing manifest fingerprints. Unsaved workspace/ancestor npm registry and runtime files skip affected checks without saving documents. Coordinator regressions verify `.npmrc`, `.nvmrc`, shared ancestor `.node-version`, unrelated-workspace eligibility and resumption after save.
- Gradle stable qualifier matching normalizes case in native candidate selection and local reporting, retaining separate JRE and Android channels. `final`, `ga` and `release` are equivalent stable qualifiers.
- Gradle service packs order their numeric qualifier after the numeric version prefix. A service-pack scan collects supported candidates through Gradle's repository resolver, then resolves the chosen exact version; ordinary scans keep the original single candidate resolution. This extra service-pack step handles native mixed-case ordering that otherwise prefers `SP1` over `sp2`. Native tests cover `sp1` to `sp2`, `sp2` to `sp10`, mixed case, reverse rejection, variant isolation and patch scope, including a single numeric component.

The new Maven, scheduler and Gradle regressions reproduced the original defects. A full clean build removed stale incremental test classes after internal API changes. Final validation passed all 233 tests and both compatibility targets; the unsigned ZIP and its embedded repaired change notes were inspected again.

## Scope and follow-up

M1, M2, N1, N2, discovery reuse and check-scoped runtime reuse from N3, and pass-local Maven fingerprints (M5) are included. Runtime sessions span a file check and overlapping compatible checks; they are removed when those users leave. Sequential file checks can still resolve a shim again for previously uncached packages, while shared warm metadata needs no setup.

M3 per-artifact Maven plugin branch rules and M4 compatible-module session reuse remain experiments. They change native resolution boundaries and need representative traces and candidate parity before inclusion. Retain fresh per-POM Maven embedders for this release. A coordinator-wide runtime session, a long-lived Node worker, persistent caches and a Maven broker are also deferred.

The measurements in [performance.md](performance.md) cover local fixtures and native query/request counts. Real-project time to visible diagnostics and native window behavior have not been measured in this validation; headless inspection, preview and lifecycle paths are covered. Publishing/signing and an Ubuntu CI run were not executed locally.

## Local artifact

`build/distributions/version-checker-1.2.0.zip`

SHA-256: `fc954cb3d0860e77b55b7b17f4ea987862375f15cf16641815ea8518a75f2fad`

The checksum also appears in `build/distributions/SHA256SUMS`. This is the locally tested unsigned archive; the release workflow rebuilds tagged sources and signs the delivery archive when configured.
