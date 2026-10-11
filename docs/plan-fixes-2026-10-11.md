# Review findings and large-table follow-up

This implements the three findings in the [11 October project review](https://plan-api.k8r.no/p/2uBWeH8wm5V_jhrWnNZK7tuS)
and continues the large-review task from the [release plan](https://plan-api.k8r.no/p/UetOAiITTXzNzuiU9ZPj2L4F).
The source is `9950c4b` plus these working-tree changes; the candidate remains
`1.3.0-SNAPSHOT`.

## Implemented corrections

- **Sorter warning:** Swing calls table-model listeners in reverse registration
  order. The dialog's summary listener ran before JTable updated its sorter's row
  count, so growing an unfiltered table read obsolete mappings. Summary and
  selection listeners now wait for replacement notifications to finish. The
  regression reproduces the warning before the fix and exercises growth from
  100 to 10,000 rows, sorting, filtering, keyboard selection, shrinking and an
  empty replacement afterward.
- **R01, Gradle target authorization:** Detached configurations still discover
  candidates. Before returning a candidate, copies of every matching resolvable
  source configuration must resolve that exact version. Copies retain source
  attributes, constraints and resolution rules; only matching copied dependencies
  receive the new version. Substitution, rejection or selection of another
  coordinate/version returns a manual-review reason and no automatic candidate.
- **R02, Gradle ownership:** An empty source-owner set now returns an explicit
  manual-review reason. The adapter therefore cannot count that declaration as
  checked. This is conservative coverage, not new support for following
  cross-project declarations back to their source. Unused catalog declarations
  also need review when there is no consumer owner.
- **R03, Maven stability:** The shared qualifier exclusion policy now includes
  `dev` and `eap`. Native metadata, prepared histories, direct/wrapped reports and
  parent reports agree on dot/hyphen separators, case and numeric suffixes. Stable
  candidates remain available when a higher development version is published.

The native Gradle regression includes future-version substitutions to a module
and a project, target-only rejection in runtimeClasspath, forced versions, strict
constraints, an unaffected dependency, root/subprojects declarations and an unused
catalog. Legacy, indexed and cross-file batch results agree. Adapter-level
coverage for the root/subprojects declaration is manual review. No build task or
artifact download is required by these metadata probes.

## Validation and artifact

```sh
JAVA_HOME=/Users/kristoffer/.sdkman/candidates/java/21.0.12+1.1-tem
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew check buildPlugin verifyPluginProjectConfiguration verifyPlugin \
  -PmavenIntegration=true -PnpmIntegration=true -PgradleIntegration=true \
  --no-configuration-cache --console=plain
python3 -B -m unittest discover -s .github/scripts -p 'test_*.py'
python3 -B -m unittest discover -s scripts -p 'test_*.py'
```

Passed **498 plugin tests in 68 classes**, zero failures, errors or skips,
including 6 native Maven, 4 npm and 6 Gradle integration methods. The release and
script suites passed **13 + 16 tests**. Packaging and project-configuration
validation passed. IDEA 2025.3.6.1, 2026.1.4 and 2026.2.3 are binary-compatible,
each with the existing 14 experimental API usages and no deprecated or
scheduled-for-removal usages. The full Gradle run took 3m 32s; local log:
`/tmp/version-checker-plan-fixes-full.log`.

The packaged and native-tested archive is
`build/distributions/version-checker-1.3.0-SNAPSHOT.zip`, SHA-256
`1fb04a19f48ff67dcda8be92c05b53d5cc2e24b179c8e14f2286e4694266c12a`.
See the [native follow-up](native-validation-2026-10-11/gradle-large.md#sorter-and-interaction-follow-up)
for Apply/undo and cancellation evidence and measurement limits.

## Remaining work

This closes the reproduced sorter warning and R01–R03. Native functional checks
cover the 10,000-declaration review, but repeated typing/EDT/write measurements,
slow-disk behavior, 15-minute baseline idle CPU, retained heap after import/rename/
unload, other supported-IDE workflows and accessibility acceptance remain open.
Minimum-IDE npm stays blocked by the previously recorded licensing limitation.
Gradle aggregation remains experimental and off by default. RC identity,
exact-RC acceptance, EAP feedback and stable promotion are still release gates;
no release was published.
