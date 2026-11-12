# Performance findings

This is a working list of performance work in the plugin, ordered by expected impact. It covers the
editor's highlighting path (annotators and inspections), the **Dependency Updates** check, build-file
parsing, lockfile deserialisation, version comparison and the HTTP cache.

## How the numbers were measured

Timings are medians of 10 runs after warm-up, on a MacBook Pro with a JDK 21 JVM. They came from a
temporary JUnit probe that called the production code directly; the probe was deleted afterwards and
nothing in this list changed production code before the fixes below. Wall-clock figures in an IDE
include read actions, PSI and the platform's scheduling, so treat them as relative costs, not as a
promise about annotation latency.

The probe used:

```kotlin
measureNanoTime { repeat(runs) { /* call the code under test */ } }
```

## Summary

| # | Area | Finding | Measured effect |
|---|------|---------|-----------------|
| 1 | npm lockfiles | The transitive walk resolves every package's children again for every declared dependency | 868 ms → 213 ms for 200 roots over 8k packages (~4×) |
| 2 | Annotators | `collect()`, `related()` and suppression run once for the outdated annotator and again for the vulnerability annotator | Roughly halves the per-file collection work when both are on |
| 3 | Gradle / Maven | `related()` re-reads and re-parses the whole build for every file, every highlighting pass | Removes repeated parsing of unchanged settings, scripts and POMs |
| 4 | Versions | `candidates()`, `compare()` and `kind()` re-parse the same strings with several regexes | 5k candidates 7.1 ms vs 1.4 ms pre-parsed (~5×) |
| 5 | HTTP cache | Hex formatting with `"%02x".format` dominates cache-key hashing | 50k keys 148 ms vs 0.7 ms with `HexFormat` |
| 6 | npm registry | The install document is materialised as a full Gson tree, then walked again for versions, deprecations and peer data | 24k versions: Gson 10.7 ms + parse 11.6 ms; a streaming key pass is 3.1 ms |

Items 1–3 are implemented in the pull requests linked from this file's commit history. The rest are
candidates for later work.

## High impact

### 1. npm transitive walk repeats child resolution

`NpmProvider.transitive()` runs a breadth-first walk from every declared dependency. Each visited node
calls `Lockfile.dependencies(node)`, which for `PackageLock` looks up `packages[node.id]`, then calls
`resolve()` for every child, walking the `node_modules` parents each time. Two hundred roots over an
8k-package lockfile therefore resolve the same subtrees hundreds of times.

Measured on an 8k-package `package-lock.json` with 200 declared dependencies:

- before: **868 ms**
- with `dependencies(node)` memoised per lockfile object: **213 ms**

`PackageLock.parse()` itself is only **5.4 ms** for the same file, so the walk, not the JSON, is the
cost. The same shape exists in `PnpmLock`, `YarnLock` and `BunLock`; all four can memoise their child
lists by node id.

### 2. Two annotators collect the same file twice

`VersionAnnotator` and `VulnerabilityAnnotator` each call:

- `DeclarationProvider.collect(file)`
- `DeclarationProvider.related(file, declarations)`
- `Suppression.suppressed(...)` for their own inspection, and often for both
- `VulnerabilityService.application(provider, file)`

They are separate external annotators because they have separate inspection switches, so the platform
calls them independently. On a Gradle build where `related()` scans the build, that duplicates the most
expensive part of the pass.

A per-file snapshot keyed by the file's modification stamp (and external inputs such as a lockfile)
lets both annotators share one collection, one related scan and one set of suppression checks. The
`TransitiveVulnerabilityAnnotator` can share the same application lookup.

### 3. Gradle and Maven `related()` rebuild the world

`GradleProvider.related()` calls `CatalogSources.files()` over the build root and then `collect()` for
every other Gradle script. Each `collect()` builds a fresh `GradleBuildFiles.Build`, which re-reads and
re-parses `settings.gradle(.kts)`, the root build script, `gradle.properties` files and init scripts.
`ScriptText` is also rebuilt for every script on every call.

`MavenProvider.related()` iterates every imported Maven project and calls `collect()` on each POM. Each
`collect()` rebuilds the `PomChain`, scans the POM's dependencies, repositories, profiles and parent,
and may consult `MavenSettingsCache`.

Caching the Gradle `Build` per root plus a stamp of its settings/build/properties files, and caching
`ScriptText` per file stamp, makes repeated `related()` calls pay only for the changed file. The Maven
equivalent is a `PomChain` and declaration cache per POM stamp, invalidated when the imported project
set changes. `ScriptText` + `GradleRepositories.parse()` on a 22 KB script was **1.5 ms**, so 60 scripts
is roughly **90 ms** of avoidable parsing per `related()` call.

### 4. Version comparison re-parses strings

`Versions.numbers()`, `suffix()`, `flavor()`, `compare()` and `kind()` run regexes and split suffixes on
every call. `candidates()` calls `flavor()`, `isStable()` and `compare()` for every version, then sorts
with a comparator that parses both sides again.

Measured:

- `candidates()` over 5k versions: **7.1 ms**; the same input with pre-parsed keys: **1.4 ms** (~5×)
- `candidates()` over 20k versions: **18.6 ms**
- `compare()` ×100k: **29 ms**
- `kind()` ×100k: **28 ms**

A parsed representation (numbers, prerelease, build, variant) cached per version string, or a
pre-computed sort key for each release list, removes most of this. It matters most for npm packages with
thousands of versions and for the tool window, which calls `kind()` per offered version.

### 5. HTTP cache key formatting

`HttpCache.key()` builds a string with `headers.toSortedMap().entries.joinToString(...)`, hashes it with
SHA-256, then formats every byte with `"%02x".format(it)`. The format call allocates and parses a format
string per byte.

Measured:

- 50k hex conversions: **148 ms** with `"%02x".format`; **0.7 ms** with `HexFormat`
- `key()` including headers and hashing: **3.3 µs**
- `get()` hit: **45 µs** (meta + body + `setLastModifiedTime`)
- `put()`: **303 µs** (temp file + atomic move)

`HexFormat.of().formatHex(digest)` and avoiding the intermediate string are simple wins. The hit path is
already small; the write path is dominated by the filesystem and is only interesting if a check writes
many fresh responses.

## Medium impact

### 6. npm install documents are materialised twice

`NpmLookup.parse()` takes a full `JsonObject` and walks `versions.entrySet()` to build the stable list,
the deprecation map and the peer/engine map. The HTTP body was already parsed by Gson.

Measured on a 24k-version manifest:

- Gson parse: **10.7 ms**
- `NpmLookup.parse()` on the tree: **+11.6 ms**
- streaming just the version keys: **3.1 ms**

Streaming the keys and only materialising deprecations or peer data for versions the UI may offer would
cut the parse roughly in half. The same walk calls `manifest.asJsonObject` once per version and builds a
`HashMap` keyed by peer maps.

### 7. Maven unused-property check is quadratic in project size

`RedundantOverrides.unusedProperties()` concatenates the text of the current POM, every ancestor and
every imported project POM, then scans that list once per version property. The list is rebuilt for
every POM the inspection visits.

For a project with *n* modules and total POM text *T*, one pass costs O(*n*·*T*), and the inspection does
it per file. A single pass over all project texts that records the referenced property names would make
it O(*T*) per check.

### 8. Unused catalog inspection walks the tree and reads every file

`CatalogSources.find()` recursively visits the build root, then reads every Gradle script and every
`buildSrc`/included-build source file into a `List<String>`. `CatalogEntries.unused()` then runs several
regexes over each text. `UnusedCatalogEntryInspection` does this on every check of
`gradle/libs.versions.toml`.

Measured: `CatalogEntries.unused()` with 60 source files of 32 KB each took **17.6 ms**, on top of the
VFS walk and file reads. Most files can be rejected with a cheap substring check (`libs.` or a quoted
alias) before being read, and the accessor scan can stream line by line instead of building a source
list.

### 9. `Lockfile.find()` parses every candidate lockfile

`Lockfile.find()` looks in up to eight parent directories, and in each directory parses every lockfile
format it finds, even when `packageManager` names the one in use. The per-file parse cache prevents
re-parsing an unchanged file, but a directory with npm and pnpm files still pays both `parse()` calls on
first use, and `hasWorkspace()` is evaluated on the wrong one first.

Selecting by `packageManager`, then by mtime, before parsing would avoid the losing parse. The same
lookup is repeated by `collect()`, `transitive()` and `pin()`; remembering the located lockfile and
workspace per `package.json` stamp avoids the directory walk.

### 10. npm registry configuration is rebuilt per call

`NpmPackages.registries(vfile)` calls `RegistryConfig.forDirectory()`, which walks parent directories for
`.npmrc`/`.yarnrc.yml`, merges them and expands environment placeholders. `collect()` and `transitive()`
each build their own lazy instance, and the `InstalledPnpm` file is read separately. The per-file text
cache in `Npmrc`/`YarnRc` helps, but the directory walk and merge are repeated.

Cache the merged config per directory and stamp the `.npmrc`/`.yarnrc.yml` files, or share the
`RegistryConfig` through the per-file snapshot.

### 11. `nodeVersion()` walks the tree per collection

`NpmProvider.nodeVersion()` walks from `package.json` up to the content root, checking `.nvmrc`,
`.node-version` and `.tool-versions` in every directory and reading each file it finds. It runs on every
`collect()` for every package in a workspace.

The result only depends on the package's directory and those files, so cache it per directory with file
stamps. In a monorepo this is one read per package per pass that becomes one read per pass.

### 12. Maven `PomChain` is rebuilt for every consumer

`MavenPoms.chain()` is called from `collect()`, `application()`, `RedundantOverrides` and
`MavenProvider.related()`. It walks parent POMs, calls `MavenProjectsManager.findProject()` and may fall
back to `MavenSettingsCache` and the local repository.

The chain is a list of live `XmlFile`s and only changes when the POM's parent declaration or the
imported project set changes. A cache keyed by the POM stamp plus the imported-project stamp removes
repeated local-repository lookups and `findProject()` calls.

### 13. Maven repositories and credentials are rebuilt per POM

`MavenRepos.forPom()` merges settings, mirrors, profiles and POM repositories, and `MavenPasswords`
decrypts credentials. `MavenProvider.collect()` constructs two lazy repository lists per POM
(dependencies and plugins), and `related()` collects every POM.

A per-POM or per-settings-stamp cache, shared with the declaration cache, avoids repeating the merge and
decryption.

### 14. `GradleBuildFiles.propertyFiles()` re-reads properties for every script

For each script, `forScript()` walks from the script's directory to the build root, finds every
`gradle.properties`, parses it and builds a `Map<String, GradleVariable>`. A build with 40 subprojects
and 3 property files does this 40 times per pass.

Cache parsed `gradle.properties` per file stamp, as `GradleEnvironment.UserFiles` already does for files
outside the project.

### 15. Gradle `Build` is not shared between scripts

Even with no `related()` call, opening a subproject script and the root script builds two independent
`Build` objects. Settings, root build script, init scripts and root properties are read and parsed
twice. Caching `Build` per root directory and stamp (as in item 3) also fixes this.

### 16. Catalog parsing recompiles field regexes

`GradleCatalog.field(name)` builds `Regex("(?:^|[{,\\s])$name\\s*=\\s*\"([^\"]*)\"")` for every lookup.
Measured cost is low (**0.43 µs** per call), so this is mostly an allocation and readability issue.
Pre-compiling the three common names (`version`, `version.ref`, `module`) is enough; it is not worth a
general regex cache.

## Low impact

### 17. `HttpCache.get()` touches metadata on every hit

Every hit rewrites the `.meta` file's last-modified time so the pruner keeps it. That is a filesystem
write per cache hit. A cheaper scheme is to track last use in memory and touch at most once per run, or
prune by access time recorded in the metadata.

### 18. `HttpCache.put()` writes a temp file and renames

Each entry creates a temporary file, writes it and atomically moves it. That is the right durability
trade-off for a cache, but it costs **303 µs** per write. Batching writes or writing directly with a
partial-file marker would reduce this; only worth doing if profiles show many fresh responses per check.

### 19. `HttpCache.prune()` lists the directory on first access

Pruning happens lazily once per `HttpCache` instance and walks every entry. With a large cache this is a
one-off pause on the first lookup. It can run in the background or be amortised across writes.

### 20. `Versions.candidates()` calls `distinct()` before sorting

The sequence does `versions.asSequence().distinct()`, then filters and sorts. For a registry list that is
already unique, `distinct()` allocates a `HashSet` and hashes every string. If the caller guarantees
uniqueness (npm keys are unique), the distinct step can be skipped or folded into the parsed-key map.

### 21. `Versions.compare()` does not short-circuit equal strings

Comparing a version with itself still parses both sides. `if (left == right) return 0` at the top is a
cheap win in sort comparators where the same version appears on both sides.

### 22. `NpmProvider.collect()` resolves registries for declarations that are skipped

`NpmLookup(name, registries.registry(name))` is built inside `add()` after `NpmSpec.parse()`, so a
malformed or unsupported spec does not pay for it. However every valid declaration pays for a registry
lookup even when the declaration is suppressed or has no updates. The registry lookup is cheap after
caching (item 10), so this is only relevant once that cache exists.

### 23. `DependencyUpdatesPanel` refreshes the whole report after a debounce

A document change in a watched file cancels pending work and schedules a full `DependencyReport.refresh()`
after the typing delay. The report re-reads every build file, even when only one changed. A per-file
diff (reuse unchanged declarations and outcomes) would help large projects, but the debounce and the
version cache already limit the damage.

### 24. `RedundantOverrides.normalized()` compiles a regex per tag

`normalized()` calls `text.replace(Regex("""\s+"""), " ")` for every duplicate dependency comparison. Move
the regex to a `private val` next to the other patterns.

### 25. `KeptVersions.keeps()` walks to the content root per declaration

The JSON suppressor calls `KeptVersions.keeps()` for every declaration, which walks parent directories
and loads each `package.json` through PSI. The root's `versionChecker.keep` list is the same for every
declaration in a workspace. Cache the applicable names per file stamp.

### 26. `GradleRepositories.parse()` rescans unchanged scripts

`Build.settings`, `rootScript` and `init` are lazy within one `Build`, but a new `Build` per `collect()`
throws that away. Item 15 covers the fix. Separately, `parse()` runs several regexes over the whole
script; `ScriptText.code` already blanks comments, so the scans are over a stable string and can be
cached per script stamp.

## Refuted or already cheap

- **`GradleCatalog.field()` regex compilation** looked suspicious but measured at **0.43 µs** per call.
  Not a priority (item 16).
- **`DependencyUpdatesPanel` document listener leak** is not a leak: the listener is registered with the
  panel as its disposable.
- **`HttpCache.get()` at 45 µs** is already small compared with a network check; do not optimise it
  before the key/write path.
- **`PackageLock.parse()` at 5.4 ms** for 8k packages is reasonable for Gson. The walk was the problem,
  not the parse (item 1).

## Suggested order

1. Lockfile child memoisation and the per-file transitive cache (item 1).
2. Shared per-file snapshot for the annotators (item 2).
3. Gradle/Maven `related()` and build-model caches (items 3, 12–15).
4. Parsed version keys (item 4) and HTTP cache key formatting (item 5).
5. Maven unused-property and catalog inspection scans (items 7–8).
6. Streaming npm install documents (item 6).
7. The remaining low-impact items, as profiles justify them.
