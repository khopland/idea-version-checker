# v2 rewrite progress

Plan: `rewrite-plan.html` (open in a browser). Branch: `v2`. Target release: 2.0.0.

Decisions (approved 2026-10-31):
1. Bulk update: no dialog. One undoable write command, then a notification.
2. Keep the Maven and Gradle wrapper checks.
3. Drop "ignore this version".
4. Include unused version-property detection for Maven, limited to the POM and its child modules in the project.

## How to resume

Read this file, then `git log --oneline main..v2`. Continue with the first unchecked item.
Run `./gradlew test` after each step. Commit after each step with a message that names the step.

## Status

- [x] Phase 0: spike (Maven repos/mirrors/credentials API, ExternalAnnotator)
- [x] Phase 1: delete the old code; core (`core/Versions.kt`, `Http.kt`, `Model.kt`, `VersionService.kt`, `Edits.kt`, `Fixes.kt`, `Updater.kt`, `VersionAnnotator.kt`; `Actions.kt`, `VersionCheckerSettings.kt`). Unit tests are in `core/VersionsTest`.
- [ ] Phase 2: Maven (declarations, repos, metadata, annotator + fixes, redundant overrides, wrapper)
- [ ] Phase 3: npm (package.json, .npmrc, registry, ranges)
- [ ] Phase 4: Gradle (scripts, TOML, plugin portal, wrapper)
- [ ] Phase 5: README, CHANGELOG, CI, version 2.0.0

## Notes and findings

(append below as you go)

### Phase 0 findings (2026-10-31)
- `MavenProject.getRemoteRepositories()` / `getRemotePluginRepositories()` return the imported, effective repositories. Maven's project builder injects mirrors, so ids are mirror ids and match `settings.xml` server ids.
- Credentials: v2 parses `<servers>` itself from `MavenSettingsCache.getInstance(project).getEffectiveSettingsFiles()`, with `${env.X}` expansion. Encrypted `{...}` passwords are not supported yet; they show a warning.
- Proxy: `com.intellij.util.net.JdkProxyProvider.getInstance().proxySelector` / `.authenticator` into `java.net.http.HttpClient`.
- `ExternalAnnotator` + `ExternalAnnotatorBatchInspection` (paired short name) exist in 2026.1.4.
- Bundled plugin ids: `org.jetbrains.idea.maven`, `com.intellij.modules.json`, `org.toml.lang`, `com.intellij.properties`.
- Baseline timings on the user's work projects were NOT recorded; I have no access to them. Ask the user to compare 1.4.0 and 2.0 on a work project.

### Design notes (keep consistent)
- One `ExternalAnnotator` (`VersionAnnotator`), registered for XML, JSON, kotlin, Groovy, TOML and Properties. It dispatches by file to `DeclarationProvider` extensions. One paired inspection, `OutdatedDependencyVersion`, owns severity and enabled state.
- Declaration = a name, the highlight range, the current version, a `Lookup` (coalesced by key), a formatter for new text, and an optional `Edit` target (file + range + expected text). A null edit means a warning without a fix, plus a reason.
- Several declarations that share one edit target (a property or a TOML version.ref) are fixable only when they agree on the target for that mode.
- Stable filter: one regex for qualifiers. A candidate must have the same non-stable-equivalent qualifier as the current version (`-jre` stays `-jre`); `.Final`, `.RELEASE` and `.GA` count as no qualifier.
- Quarantine: when cutoff > 0, an undated version counts as too new. Maven uses metadata `<lastUpdated>` <= cutoff as a shortcut, then POM HEAD Last-Modified. Release times persist on disk forever.
- Settings are application level: enabled, minReleaseAgeHours.
