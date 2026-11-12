# Performance findings

This is a working list of performance work in the plugin. It covers the editor's highlighting path (annotators and
inspections), the **Dependency Updates** check, build-file parsing, lockfile reading, version comparison and the HTTP
cache. Each finding says whether it is done, and where, or why it is not worth doing.

The work is in a stack of pull requests, each on top of the one before:

| PR | What |
|----|------|
| #127 | Memoised lockfile children and a cached npm transitive walk |
| #129 | Gradle and Maven declaration, build and POM-chain caches |
| #128 | One per-file snapshot shared by the three annotators |
| #130 | Single-pass version parsing, streamed npm documents, cached inspection scans, HTTP cache keys |

## How the numbers were measured

Timings are medians of 10 runs after 3 warm-up runs, on a MacBook Pro (Apple silicon) with JDK 21, inside a
`BasePlatformTestCase` so that PSI, documents and the VFS are real. They came from a temporary JUnit probe that calls
the production code directly; the probe is not committed. Treat them as relative costs: an IDE adds read actions and
the daemon's own scheduling.

The test projects:

- **npm**: a `package-lock.json` with 8,000 packages, 200 of them declared, each depending on up to four packages
  further down. Each declared dependency brings in about 15 packages, 2,884 in all.
- **Gradle**: a settings script with 60 modules, a root `gradle.properties` with 40 versions, and 61 build scripts of
  about 150 dependencies each, all sharing one `gradle.properties` version so that `related()` reaches every script.
- **Maven**: a parent POM with 40 version properties and 100 imported modules with 30 dependencies each, all using the
  parent's properties.
- **Catalog**: `libs.versions.toml` with 120 versions and 120 libraries, used from 60 scripts.

## Before and after

`main` is `13b46a0` (2.5.0). "After" is the top of the stack.

| What | `main` | After | |
|------|-------:|------:|--|
| All three annotators, Gradle root script, nothing changed | 211 ms | 0.08 ms | the snapshot is reused |
| All three annotators, Gradle root script, after an edit to it | 205 ms | 12 ms | ~17× |
| Gradle `collect()` + `related()` over 61 scripts | 102 ms | 1.0 ms | ~100× |
| Gradle `collect()` of every script (tool window, Update All) | 99 ms | 1.3 ms | ~75× |
| All three annotators, Maven module of 101, nothing changed | 18.8 ms | 0.05 ms | |
| All three annotators, Maven module of 101, after an edit to it | 26 ms | 2.0 ms | ~13× |
| Maven `collect()` + `related()`, after an edit to the parent POM | 15 ms | 21 ms | every module reads the parent, so all are collected again |
| Maven redundant override inspection on all 101 POMs | 19.3 ms | 7.1 ms | |
| All three annotators, `package.json` with 200 dependencies, nothing changed | 6.9 ms | 0.6 ms | |
| npm transitive walk, 200 roots, repeated | 2.3 ms | 0.03 ms | cached |
| npm transitive walk, worst case (every root reaches 3,000 packages) | 631 ms | 279 ms after an edit, 0.05 ms repeated | |
| Unused catalog entries, after a catalog edit | 18.8 ms | 1.3 ms | ~14× |
| `Versions.candidates()` over 5.5k / 20k versions | 6.5 / 16.4 ms | 2.8 / 4.0 ms | |
| `Versions.compare()` ×100k | 31 ms | 17 ms | |
| npm install document with 24k versions (17 MB) | 62 ms (Gson tree + walk) | 24 ms (stream) | |
| `HttpCache.key()` ×50k | 126 ms | 10 ms | |

The Maven parent-edit row is slower because the declarations are now cached with the stamps of the POM's parents, and
filling that cache for 100 modules costs a little more than collecting them did. It is the one case where every module
really has to be read again.

## Problems found in the first pull requests

The first versions of #127, #128 and #129 were reviewed before this list was updated. These were fixed in the same
pull requests:

- **#127** The lockfile child memo was a `HashMap` on a lockfile that every annotator thread shares; it is a
  `ConcurrentHashMap` now. The cached walk was stamped with the document's modification stamp but returned text ranges
  from PSI, so an uncommitted edit could hand out ranges for the old text; it is keyed on the PSI file's stamp now.
  The cache ignored the registry settings, which every `NpmLookup` in the result names, so a changed `.npmrc` kept the
  old registry; it is part of the key now. One `NpmLookup` is shared by every copy of a package name.
- **#129** `GradleProvider` kept one `GradleEnvironment` for its whole life, so a changed `~/.gradle/gradle.properties`
  or init script was never read again; the environment is replaced when those files change. The `Build` cache was
  keyed on the stamps of the settings script, root script and root properties but not on which files they were, so a
  `build.gradle` replaced by `build.gradle.kts` with an equal stamp kept the old build. The Maven caches were keyed on
  `PsiModificationTracker.MODIFICATION_COUNT`, which changes on every keystroke in any file, so typing in one POM
  discarded every other POM's declarations and `related()` collected all modules again. They are keyed on the PSI
  stamps of the POM and its parents now, and `related()` stamps the settings files once for all POMs.
- **#128** `FileSnapshot` stamped a file's lockfile and build files through a new `inputs()` hook, but missed files a
  provider reads outside the IDE, such as `settings.xml` or the user `.npmrc`, and it cached the Nexus IQ application
  without the server setting, so turning Nexus IQ on or off had no effect until the file changed. The hook is
  `DeclarationProvider.stamp()` now, for what is outside the IDE's files; the snapshot also checks the VFS modification
  count, and the application is gated on the server at every call. **Check Now** discards every cache through
  `FileStamps.invalidate()`.

## Findings

### Done

1. **npm transitive walk repeated child resolution** (#127). Every root's breadth-first walk resolved each package's
   children again, walking `node_modules` parents each time. The children are memoised per parsed lockfile, and the
   whole walk is cached on the `package.json` PSI until it, the lockfile or the registry settings change.

2. **Three annotators collected the same file** (#128). `VersionAnnotator`, `VulnerabilityAnnotator` and
   `TransitiveVulnerabilityAnnotator` each ran `collect()`, `related()`, the suppression checks and the Nexus IQ
   lookup. The original note said two annotators and "roughly halves"; there are three, and on a Gradle root script
   the shared snapshot turns a 205 ms pass into 12 ms after an edit and 0.08 ms when nothing changed.

3. **Gradle and Maven `related()` rebuilt the world** (#129). Gradle scripts and `gradle.properties` are parsed once
   per text stamp, a build is cached on its root directory, and a script's declarations are cached with the values its
   context gives it (repositories, root extra properties, property files), so an edit to the root script below a
   property does not collect the modules again. Maven declarations and POM chains are cached with the PSI stamps of
   the POM and its parents.

4. **Version comparison re-parsed strings** (#130). `Versions` reads a version in one pass instead of several regular
   expressions, `compare()` returns early for equal strings (item 21), and `candidates()` parses each version once for
   the filter and the sort. A differential test against the old parser on 3,400 generated versions, including
   malformed ones, agreed on `isStable`, `numbers`, `flavor`, `compare`, `kind` and `candidates`.

5. **HTTP cache key formatting** (#130). `HexFormat` instead of `"%02x".format` per byte. The key is unchanged, which
   a test checks, so existing cache entries are still found.

6. **npm install documents were materialised twice** (#130). The document is read with a `JsonReader`, keeping only
   versions, deprecations, peers and `engines.node`. This also avoids holding a full Gson tree of a 20 MB document.

7. **Maven unused-property check was quadratic** (#130). The names in `${…}` are cached per POM and for the project,
   invalidated by PSI and Maven import changes. Inspect Code over 101 POMs went from 19 ms to 7 ms; the gap grows with
   module count and POM size.

8. **Unused catalog inspection walked and read every file** (#130). The script and source listings are cached by VFS
   structure, and what each file uses (quoted words and `libs.` accessors) is cached per text stamp.

12, 13, 14, 15, 26. **Maven `PomChain`, Maven repositories, Gradle `propertyFiles()`, shared `Build`, and
   `GradleRepositories.parse()` on unchanged scripts** are all covered by the caches in #129: the chain and the
   declarations (which hold the merged repositories) are cached per POM, and `gradle.properties`, scripts and their
   repositories per text stamp.

17. **`HttpCache.get()` wrote metadata on every hit** (#130). The last-use time only protects the entry from the
   30-day pruner, so it is written at most once a day.

21. **`Versions.compare()` did not short-circuit equal strings** (#130). Done with item 4.

24. **`RedundantOverrides.normalized()` compiled a regex per tag** (#130). Moved to a field.

25. **`KeptVersions.keeps()` walked to the content root per declaration** (#130). The kept names of a `package.json`
   and the ones above it are a `CachedValue`.

27. **`LockfileListener` asked every project for its open files on every VFS event** (#130, new). It now checks the
   changed file names first, so ordinary saves return at once.

### Not needed or false

9. **`Lockfile.find()` parses every candidate lockfile.** False. The candidates are sorted by `packageManager` and
   modification time first, and `firstNotNullOfOrNull` parses only until one reads. The parse is also cached per file
   stamp. With #128 the lookup runs once per change, not once per pass.

16. **Catalog field regexes.** Measured at 0.43 µs per call. Not worth a change.

18. **`HttpCache.put()` writes a temp file and renames.** That is the right durability trade-off, and only fresh
   responses pay for it.

19. **`HttpCache.prune()` lists the directory on first access.** A one-off per IDE session, on a background thread.

20. **`Versions.candidates()` calls `distinct()`.** Callers do not all guarantee unique versions (Maven metadata can
   repeat one), and with parsed keys the hash set is a small share of the cost.

22. **`NpmProvider.collect()` resolves registries for skipped declarations.** A registry lookup is a map read once
   the configuration is built, and `collect()` now runs once per change.

23. **`DependencyUpdatesPanel` refreshes the whole report.** With the per-file caches, the collection part of a
   refresh is a few milliseconds on the test projects; the rest is version and vulnerability checks, which are cached
   in memory. A per-file diff would add complexity for little gain.

- **`DependencyUpdatesPanel` document listener leak.** Not a leak: the listener is registered with the panel as its
  disposable.
- **`PackageLock.parse()` at 5.4 ms** for 8k packages. The walk was the cost, not the parse.

### Still open

10. **npm registry configuration is rebuilt per call.** `RegistryConfig.forDirectory()` walks up to the file system
    root checking for `.npmrc` or `.yarnrc.yml`; the file contents are cached, but the walk and merge are not. Since
    #128 it is also the npm provider's `stamp()`, so it runs once per annotator per pass. On the test project that is
    part of the 0.6 ms a pass costs, so it is cheap, but a cache keyed on the directory and the files' modification
    times would remove it from the hot path.

11. **`nodeVersion()` walks the tree per collection.** It now runs once per `package.json` change rather than once
    per pass. Low priority.

28. **The npm transitive walk is still linear in its output.** On the worst-case project, where every declared
    dependency reaches 3,000 packages, an edit to `package.json` costs 279 ms for 610k results. Real lockfiles are much
    shallower (the realistic project takes 2.4 ms), and repeated passes are cached. If it matters, the walk could
    stop at packages that another declared dependency already reported, or cache each root's sub-walk separately.

29. **A Maven parent POM edit collects every module again.** That is correct, since each module reads the parent's
    properties, but a module whose declarations do not use any changed property could keep its result. It would need
    property-level dependency tracking; not worth it at 21 ms for 100 modules.

## Suggested order for what is left

1. Cache `RegistryConfig` per directory (item 10), since it is now on every pass.
2. Item 28 only if a real project shows it in a profile.
