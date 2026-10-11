# Dense Gradle validation setup

11 October 2026, same Apple M4 / 16 GiB host and isolated IDEA 2026.2.3 /
JBR 25.0.4 profile as the [large-review follow-up](gradle-large.md).
The installed snapshot SHA-256 remains
`1fb04a19f48ff67dcda8be92c05b53d5cc2e24b179c8e14f2286e4694266c12a`.
Aggregation is enabled only in this disposable profile; the shipped default is off.

## Fixture correction and import

The initial single-file fixture put 10,000 identical dependency statements in one
Groovy closure. Native IDEA import failed with `MethodTooLargeException` before
plugin review. The fixture generator now emits dependency blocks of at most 100
statements, preserving all 10,000 declarations in one file. This fixes the test
fixture, not plugin code or supported declaration semantics.

Generated literal fixtures passed `gradlew help --offline` with Gradle 9.8.0 /
SDKMAN Java 21.0.12: Groovy in 1 second and Kotlin DSL in 5 seconds. The corrected
Groovy fixture also completed native IDEA sync successfully. These durations are
configuration smoke checks, not controlled plugin performance samples. Kotlin's
CLI configuration does not replace its native IDEA workflow checks.

```sh
python3 scripts/create-native-gradle-fixture.py /tmp/version-checker-acceptance-dense-chunked-20261011 \
  --repository /tmp/version-checker-native-repository-20261011/repository \
  --declarations-per-module 10000 --repeat-artifact alpha
python3 scripts/create-native-gradle-fixture.py /tmp/version-checker-acceptance-dense-kotlin-20261011 \
  --repository /tmp/version-checker-native-repository-20261011/repository \
  --declarations-per-module 10000 --repeat-artifact alpha --dsl kotlin
```

The chunked Groovy build file replaced the initial invalid build file in the
already-open disposable dense project before successful sync. Both generators
preserve declaration counts and refuse nonempty destinations. The script suite
passes all 16 tests, including the 10,000-declaration regression for both DSLs.

## Instrumentation baseline and native input blocker

Bundled JBR method tracing successfully captured `IdeEventQueue.dispatchEvent`
spans. The [sanitized setup summary](idea-2026.2.3-dense-setup.json) retains the
110-second instrumentation smoke recording and a post-import full-GC class
histogram baseline. Raw JFR and histogram files stay in the disposable local
workspace. No plugin Apply span was captured. Generic event dispatch durations
do not establish keystroke-to-render latency, controlled UI pause acceptance,
or write-command duration. The histogram's plugin object bytes are shallow sizes,
not transitive retained heap; rename/unload comparisons remain outstanding.

Native input could not reach the review: the editor accessibility element reported
`cannotClickOffscreenElement`, coordinate input reported `noWindowsAvailable`,
and keyboard/Find Action attempts produced no visible action dialog. Accessibility
reads and screenshots still exposed the dense project. Rebinding, raising/fitting
the window and restarting only the isolated instance did not restore editor input.
A request to move the disposable window to the active display is pending. The
user's normal IDEA instance was untouched, and JFR recording was stopped.

Dense review/typing/Apply/undo, slow-disk tests, paired 15-minute idle CPU,
retained heap after rename/unload, and remaining supported-IDE workflow and
accessibility checks remain open. No dependency update was applied during this
attempt. The user chose to finish validation before stable release; the version
remains `1.3.0-SNAPSHOT`, with no RC/EAP publication performed.
