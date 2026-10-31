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

- [ ] Phase 0: spike (Maven repos/mirrors/credentials API, ExternalAnnotator)
- [ ] Phase 1: delete the old code; core (Version, Http, MetadataCache, Settings, OutdatedAnnotator, UpdateActions)
- [ ] Phase 2: Maven (declarations, repos, metadata, annotator + fixes, redundant overrides, wrapper)
- [ ] Phase 3: npm (package.json, .npmrc, registry, ranges)
- [ ] Phase 4: Gradle (scripts, TOML, plugin portal, wrapper)
- [ ] Phase 5: README, CHANGELOG, CI, version 2.0.0

## Notes and findings

(append below as you go)
