# Quick-fix and preview freshness

The F11 engineering follow-up introduces one `VersionResultLease` contract for
inspection fixes and complete previews. A lease contains the provider/context,
update mode, refresh revision, original observation time and original deadline.
Its validation also checks the identity of the retained result observation.

## Authorization policy

- Complete results receive a new identity on every publication, even when the
  report object and clock timestamp are identical. A held fix or preview cannot
  follow a replacement result.
- Each incremental publication replaces the whole partial observation's identity.
  Its hints cannot supply a complete preview. The later
  [partial-fix follow-up](partial-fix-freshness.md) binds fixes to contributing
  declaration tokens: unrelated deltas preserve those fixes, while replacing a
  contributor retires them. Remapped or incompletely represented scopes retain
  conservative whole-observation leases.
- Warnings retained across version edits carry their originating result's
  deadline and observation time. Remapping declarations, filtering ignored
  versions and rebuilding a presentation cannot make that metadata younger.
- Refresh, cancellation of the owning partial check, capacity eviction, expiry,
  provider invalidation and cache disposal retire the relevant leases.

Maven literal/property/parent edits and local overrides, npm local/workspace
edits, and Gradle script/shared-catalog edits use the inspection report's lease.
They also retain the provider input fingerprint, original text, ignore-policy
and shared-consumer guards. Held inspection fixes reject paused checks.
Low-level edit helpers retain their explicit guard parameters for prepared plans
and synthetic fixtures; production inspections supply leased result views.

The result cache stores raw repository reports. It attaches leases only to
inspection presentations and returns the same lease contract for cached preview
results. Lease callbacks capture the result's small identity token and context,
not an evicted report or snapshot payload. The previous result-retention budgets
therefore continue to apply to cache-owned payloads.

## Relation to input-read work

Quick fixes check their lease before hashing provider inputs. Bulk plan guards
also check result/ignore authorization before provider input validation, inside
the existing write command. Expired or refreshed results now reject without
performing that disk work. Writable-file preparation and all-edit prevalidation
remain in place; shared edits still apply atomically and undo together.

F09 remains open for live-result input validation. Snapshot creation and
quick-fix/apply fingerprints still perform external reads through their existing
implementations. Moving those reads needs a capture/revalidation design that
preserves changes made outside VFS, unsaved configuration and shared ownership.
This batch establishes the common result-authorization boundary first; it does
not claim that all input reads are off EDT or outside read actions.

## Regression coverage

Deterministic clock/cache tests cover common quick-fix/preview expiry, original
age through retained presentations, partial-to-complete transitions, same-object
replacement in one clock tick, source/provider refresh, partial cancellation,
eviction and disposal.

Headless IDEA platform fixtures retain actual inspection descriptors across refresh for
Maven parent/local-override and Gradle shared-catalog fixes. npm fixtures exercise
an expired local fix, a refreshed workspace fix and paused checks. Files remain
unchanged on rejection and the existing **Refresh This File** recovery is used.
An additional bulk-plan regression proves that rejected authorization performs
zero provider input reads. Existing retention, alias/range, shared-consumer and
undo fixtures run with these changes.

## Validation of this follow-up

Final validation passed on 2026-10-11 with SDKMAN Temurin Java 21.0.12+1.1:

- **430 plugin tests in 62 classes**, zero failures, errors or skips, with all
  native integration flags enabled. This includes 6 Maven, 4 npm and 5 Gradle
  integration methods and 4 packaged-plugin lifecycle methods.
- **13 release-automation and 16 script tests** passed. Release metadata still
  reports `releasable=false` for `1.3.0-SNAPSHOT`.
- **`check`, `buildPlugin`, `verifyPluginProjectConfiguration` and `verifyPlugin`
  passed** in 2m 28s. Incremental outputs/caches were reused. Test classes had
  been explicitly rebuilt after the shared report constructor changed.
- **IDEA 2025.3.6.1, 2026.1.4 and 2026.2.3 are binary-compatible**, with the same
  10 experimental API usages and no deprecated/scheduled-for-removal usages.
- Packaged version/minimum build checked: `1.3.0-SNAPSHOT` / `253.33813.55`.
  The lease class and new freshness notes are present. Changed local Markdown
  links and whitespace checks passed.

Archive: `build/distributions/version-checker-1.3.0-SNAPSHOT.zip`  
SHA-256: `eec872c887de4ec7bad0f2228826d7038592b9156e9f295024675b6ec4f160bc`

This archive includes both the [retention/input-preparation batch](result-retention.md)
and this follow-up, built from the working tree on top of `d855e00`. Full log:
`/tmp/version-checker-candidate-lease-full.log`; focused 117-test log:
`/tmp/version-checker-candidate-lease-focused.log`. Current XML and verifier
reports are under `build/`. No native UI/typing acceptance, release or publication
was performed by this follow-up.
