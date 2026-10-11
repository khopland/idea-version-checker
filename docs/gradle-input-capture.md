# Gradle background input capture (F09)

Updated 2026-10-11. This continues the [save preparation and retention work](result-retention.md)
and [shared result freshness work](candidate-freshness.md).

Gradle discovery, coordinator result validation and preview preparation now read
user-home `gradle.properties`, `init.gradle`, `init.gradle.kts` and the nested
`init.d` tree on `Dispatchers.IO`, outside IDE read actions. Each operation captures
one saved-content hash map and reuses it across linked roots. The map is local to
that operation; it is not another metadata cache and has no independent lifetime.

A subsequent read action captures current linked-build inputs, declarations,
settings and unsaved external document text. External fingerprints retain both
the saved-content digest and the unsaved-document digest. Missing fixed inputs
remain explicit, and added/removed init scripts change the input map. A changed
Gradle user-home selection discards the capture and retries, up to three attempts;
continued changes reject the operation with refresh guidance.

The coordinator calls the new suspend `isCurrentInBackground` adapter contract
before native queries and result publication, including incremental publication.
Its default keeps existing Maven/npm validation semantics. Gradle supplies the
external capture implementation. Cancellation is checked between disk inputs,
during tree traversal and before entering the IDE read action.

## Guards and remaining scope

Synchronous inspection snapshot creation and final quick-fix/bulk apply validation
still use the current-content fingerprint path. Final application therefore still
rejects disk edits made outside the VFS even when size and modification time are
unchanged. No saved-file timestamp shortcut or asynchronous watcher replaces this
content guard.

This closes the Gradle external-disk portion of background discovery, validation
and preview preparation. It does **not** close F09: Maven/npm external capture,
Gradle synchronous inspection/apply paths, project VFS/PSI capture costs and native
slow-disk/typing measurements remain open. Editor and settings capture is still
inside a read action; this change does not establish a maximum read-lock duration
or native UI latency improvement.

## Regression coverage

Five new Gradle cases check:

- Thread/read-lock boundaries and one disk capture across two linked builds with
  24 nested init scripts, with fingerprints equal to synchronous captures.
- Same-size/same-timestamp disk edits, newly created properties, removed scripts
  and final application rejection without changed dependency text.
- An unsaved external document edited on EDT while disk capture is in progress;
  validation uses its latest text and discovery preserves the existing fingerprint.
- A user-home change during disk capture, with only the new home's inputs returned.
- Cancellation after the first read, before another input or any snapshot returns.

A coordinator regression rejects synchronous provider validation and checks both
interactive cached reuse and automatic publication through the suspend contract.
The packaged-plugin lifecycle proxy implements the extended internal contract,
retaining active-check cancellation and preview unload coverage.

## Validation

The final local run passed on 2026-10-11 with SDKMAN Temurin Java
21.0.12+1.1:

- **57 focused tests in 8 classes** passed, including the new capture cases,
  coordinator checks, scaling/inspection regressions and packaged-plugin lifecycle.
- **436 plugin tests in 63 classes** passed with zero failures, errors or skips.
  All native flags were enabled: 6 Maven, 4 npm and 5 Gradle integration methods,
  plus 4 packaged-plugin lifecycle methods.
- **13 release-automation and 16 performance-report tests** passed. Project metadata
  still reports `1.3.0-SNAPSHOT`, EAP channel and `releasable=false`.
- **`check`, `buildPlugin`, `verifyPluginProjectConfiguration` and `verifyPlugin`**
  passed in 1m 53s using local Gradle outputs/caches. This was an incremental run.
- **IDEA 2025.3.6.1, 2026.1.4 and 2026.2.3 are binary-compatible**, retaining the
  10 existing experimental API usages and no deprecated/scheduled-for-removal uses.
- The archive descriptor remains `1.3.0-SNAPSHOT` with minimum build
  `253.33813.55`; packaged notes and the new suspend validation implementation
  were checked. Changed documentation links and diff whitespace also passed.

```bash
python3 -B -m unittest discover -s .github/scripts -p 'test_*.py'
python3 -B -m unittest discover -s scripts -p 'test_*.py'
python3 .github/scripts/release_metadata.py
./gradlew check buildPlugin verifyPluginProjectConfiguration verifyPlugin \
  -PmavenIntegration=true -PnpmIntegration=true -PgradleIntegration=true --console=plain
```

Archive: `build/distributions/version-checker-1.3.0-SNAPSHOT.zip`  
SHA-256: `9b1971289ff0848aa665774e104b6861e0a3b972f08b69cea5800d8cfe82b9c0`

This archive was built from the Gradle-capture working tree on top of `447bc32`,
before committing this continuation. Logs: `/tmp/version-checker-gradle-input-focused.log`
and `/tmp/version-checker-gradle-input-full.log`. Artifact identity is also saved
locally in `/tmp/version-checker-gradle-input-artifact.json`. The thread checks,
content guards and work counts establish implementation behavior, not native
latency, heap retention or final-archive UX acceptance.
