# Inspection and edit input capture

Updated 2026-10-11. Maven, npm and Gradle production inspection visitors no
longer synchronously capture external configuration. Each provider prepares an
immutable saved-input capture on cancellable IO outside IDE read actions; its
reader overlays the latest editor text, PSI, imports, settings and runtime/path
selection under a short read action. The status widget uses the same provider
contract, with its disk phase outside the read lock.

The non-suspending visitor uses a project-owned cache with at most 16 entries,
including pending work, and at most four active capture workers. Entries expire
after two seconds and release their reader physically. Expiry does not start a
query or capture: a subsequent visitor requests new preparation. Duplicate
visitors share pending work. Source/provider refresh, eviction and disposal
cancel owned workers; late workers cannot publish into replacement entries.
Unrelated VFS changes do not invalidate all captures.

The cache is for display only. Its external bytes can be up to two seconds old;
live unsaved text and IDE settings are overlaid whenever a visitor reads it.
Unsupported/unavailable snapshots do not request a highlighting restart. Gradle
also checks current-file ownership before asking for preparation. This prevents
the native feedback loop where an inspection of an npm file repeatedly prepared
a null Gradle snapshot and restarted the same file's highlighting.

Every production update fix and review plan prepares a **new** saved-input
capture when invoked. Cheap result/ignore/pause authorization runs before that
work, then again inside the write command. The captured input guard overlays
current IDE state inside that command, before validating all selected text and
element edits. Selected/combined plans retain every contributing provider guard;
no partial edit is applied when one input or result has changed. Modal progress
keeps this synchronous native edit entry point cancellable while disk work runs
in the background. It does not initiate a repository query.

Disk-only edits made before invocation, including same-size/same-timestamp
configuration edits, are compared by the providers' saved-content fingerprints.
Unsaved edits and path/import/settings changes during preparation are checked
against the live state before writing. This is not an OS-wide filesystem
transaction: another process can change a file after the saved-input capture.
Preview/display captures never authorize Apply, and a filesystem watcher is not
used as proof of freshness. Existing low-level synchronous adapter helpers remain
for tests and non-inspection analysis; production visitor/edit paths use the new
contracts.

Native IDEA 2026.2.3 testing also found that accessibility-dispatched review
clicks do not implicitly provide the write-intent access required to save a
document. Both selective input saving and selected-edit saving now acquire that
access explicitly. The `Runnable` overload is compatible with all three verifier
targets. If saving or another follow-up fails after the edit, the notification
states that the editor text changed and needs saving/review.

## Evidence and remaining gates

Regressions exercise background-thread/no-read-lock disk captures, shared reads,
latest unsaved overlays, rejected external changes, bounded pending work,
cancellation/expiry/disposal, unavailable-snapshot feedback prevention, and atomic
combined/selected validation. Existing quick-fix, shared-owner, retention, native
resolver and packaged-plugin lifecycle suites remain enabled.

Final full validation passed on 2026-10-11: **493 plugin tests in 68 classes**,
zero failures/errors/skips, all three native integration flags, `check`, packaging,
project-configuration validation and Plugin Verifier for IDEA 2025.3.6.1,
2026.1.4 and 2026.2.3. Each verdict reports compatibility and 14 experimental API
usages: the existing ten progress usages plus four explicit write-intent
class/method references. No deprecated or scheduled-for-removal API usage is
reported. Release/script suites passed **13 + 16 tests**. The incremental full
build took 3m 20s with SDKMAN Temurin Java 21.0.12+1.1.

Archive: `build/distributions/version-checker-1.3.0-SNAPSHOT.zip`  
SHA-256: `0b3e71ec74054ac9d52acf6037ccd4df1de81ea481340994da0abea501cf8a9f`  
Log: `/tmp/version-checker-f09-complete-validation.log`.

```sh
./gradlew check buildPlugin verifyPluginProjectConfiguration verifyPlugin \
  -PmavenIntegration=true -PnpmIntegration=true -PgradleIntegration=true \
  --no-configuration-cache --console=plain
python3 -B -m unittest discover -s .github/scripts -p 'test_*.py'
python3 -B -m unittest discover -s scripts -p 'test_*.py'
python3 .github/scripts/release_metadata.py
```

Metadata still returns `channel=eap` and `releasable=false` for the snapshot.

The [native acceptance record](native-validation-2026-10-11/README.md) records
archive identities, the observed save/highlighting failures and their retests.
Native slow-disk, large init-directory, 100-module typing/EDT and repeated heap
measurements remain uncompleted. Moving explicit external reads off the EDT
does not prove that VFS/PSI work is cheap or establish native latency bounds.
Gradle cross-file aggregation remains off by default.
