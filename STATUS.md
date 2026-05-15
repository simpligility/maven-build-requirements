# Status report — 2026-05-15

## Toolchain bumps

- Minimum Java raised to **25** (`maven.compiler.release=25`; README
  requirement updated). Build verified against Temurin 26.
- `mima` `2.4.25` → `2.4.43`. No source changes.
- `toolbox` `0.9.0` → `0.15.9` (large jump). No source changes needed —
  the `shared` API surface we use is stable across the range. Verified by
  running the analyzer end-to-end against `quickstart-example` (435
  artifacts, identical to the pre-bump output).

## Java 25 modernization of `BuildRequirementsAnalyzer`

Surface-level style/feature updates only — no architectural changes
(the monolith-split refactor stays in the memory backlog).

- **JEP 511 module imports**: 13 individual JDK imports replaced with
  `import module java.base;` and `import module java.xml;`. Drops
  `java.io.*`, `java.nio.file.*`, `java.util.*`, `java.util.concurrent.*`,
  `java.util.jar.*`, `javax.xml.parsers.*`, and `org.w3c.dom.*` lines.
- **JEP 467 markdown Javadoc**: class-level + 3 method-level comment
  blocks converted from HTML `<p>`/`<a>`/`{@code …}` to `///` markdown
  with `[text](url)` links and backtick code spans.
- **Modern stdlib factories**: `Paths.get(...)` → `Path.of(...)`,
  `new Date()` → `ZonedDateTime.now().format(DATE_FORMAT)`. Date format
  preserved exactly (`EEE MMM dd HH:mm:ss zzz yyyy`, `Locale.ENGLISH` to
  keep `PDT`-style zone abbreviations — `Locale.ROOT` falls back to
  GMT-offset because it lacks localized zone names).
- **`Map.forEach(this::printArtifactLine)`**: 8 repeated
  `for (Map.Entry<String, Artifact> e : map.entrySet())` blocks collapsed
  to one-liners using the `BiConsumer` overload.
- **JEP 456 unnamed variable**: `catch (Exception ignored)` →
  `catch (Exception _)`.
- **FQN cleanups** now covered by module imports:
  `java.util.jar.JarFile/JarEntry`, `java.io.InputStream`,
  `java.net.URI.create`, `java.util.Arrays.copyOfRange` — all bare.

File shrank from 1060 → 1028 lines.

### Verified

- `./mvnw clean package` succeeds, no preview-feature warnings (every
  feature used is final in JDK 25).
- `java -jar target/maven-build-requirements-1.0-SNAPSHOT.jar -p
  src/it/projects/quickstart-example` produces the same 435-artifact
  count and the same `Fri May 15 ... PDT 2026` date format as before
  the changes.

### Notes / non-changes

- `SCOPE_ORDER` is still declared but unused. Not removed here — it
  belongs with the upcoming monolith split.
- `for (int i = 0; i < nodeList.getLength(); i++)` over W3C `NodeList`
  is unchanged: no clean stdlib iterator/stream exists without a
  helper, and that's refactoring territory.
- Mass `var` conversion and a text-block summary builder skipped —
  stylistic, not clear modernization wins.

## Monolith split: per-task analyzers

The pending memory note "Refactor analyzer into modules" — split
the ~900-line `BuildRequirementsAnalyzer` into a thin CLI
orchestrator plus per-task helpers — is now done. Memory note
deleted.

End state: `BuildRequirementsAnalyzer.java` went from **1060 to
129 lines** (~88% reduction). All analysis lives in two new
packages:

```
com.simpligility.maven/
├── BuildRequirementsAnalyzer.java       ← picocli @Command, orchestration, main()
├── analysis/
│   ├── AnalysisContext.java             ← mima Context + ModelReader + logger bundle
│   ├── AnalysisResult.java              ← aggregate of all resolved artifact buckets
│   ├── ProgressLogger.java              ← print() — stdout + report file tee
│   ├── ProjectStructure.java            ← record returned by ProjectStructureLoader
│   ├── MavenEnvironmentDetector.java    ← Step 0
│   ├── ProjectStructureLoader.java      ← Step 1
│   ├── EffectiveModelBuilder.java       ← Step 2a (MMR per module + sub-module scan)
│   ├── DependencyResolver.java          ← Step 2b
│   ├── LifecyclePluginLoader.java       ← Step 2c0 (maven-core bindings)
│   ├── PluginAnalyzer.java              ← Steps 2c + 2d
│   ├── ExtensionAnalyzer.java           ← Steps 2e-2g
│   ├── MavenDistributionResolver.java   ← Step 2h
│   └── ReportWriter.java                ← Step 3 + summary + coords file write
└── util/
    ├── Dom.java                         ← directText/directElement + DocumentBuilder factory
    └── Coords.java                      ← artifactCoords + artifactFromMavenUrl
```

Delivered as **9 commits** against `master`
(`f6b9af7..123fcc6`), each independently buildable and verified
against the `quickstart-example` IT fixture for byte-identical
output (date/path lines aside):

1. Extract `ProgressLogger`, `Dom`, `Coords` utilities (pure
   extraction).
2. Add `AnalysisContext` + `AnalysisResult` skeleton (types only,
   no callers).
3. Extract `MavenEnvironmentDetector` (Step 0).
4. Extract `ProjectStructureLoader` (Step 1).
5. Extract `EffectiveModelBuilder` + `DependencyResolver`
   (Steps 2a + 2b).
6. Extract `LifecyclePluginLoader` + `PluginAnalyzer`
   (Steps 2c0 + 2c + 2d).
7. Extract `ExtensionAnalyzer` + `MavenDistributionResolver`
   (Steps 2e-2h).
8. Extract `ReportWriter` (Step 3 + summary + coords file).
9. Slim `BuildRequirementsAnalyzer` to the CLI orchestrator —
   drop dead locals, `print`/`printArtifactLine`/`isBlank` wrappers,
   move report banner into `printHeader`, replace inline
   `DocumentBuilderFactory` setup with `Dom.newDocumentBuilder()`,
   wrap `ProgressLogger` in try-with-resources, trim unused imports.

### Design choices that landed

- **Analyzers thread products through return values** rather than
  sharing mutable analysis state. `AnalysisContext` only holds
  *infrastructure* (mima `Context`, `MavenModelReader`,
  `ProgressLogger`, shared `DocumentBuilder`, `projectDir`);
  results travel as `EffectiveModelBuilder.Result`,
  `ProjectStructure`, etc.
- **`AnalysisResult` is the single aggregate.** Mutators
  (`addDependency`, `addPlugin`, …) auto-update the sorted
  `allCoords` set so the coords-file write is a one-liner.
- **`AnalysisContext` holds the mima `Context` directly** rather
  than wrapping it behind narrow interfaces. Pragmatic — every
  resolver needs `remoteRepositories()` /
  `repositorySystem()` / `repositorySystemSession()` anyway, and
  this isn't a library where leakage matters.
- **`MavenEnvironmentDetector` takes `(ProgressLogger, Path)`**
  rather than `AnalysisContext`. Skips opening mima for Step 0,
  which doesn't need it — and avoids re-indenting 200 lines of
  Steps 1-3 just to move them inside an earlier
  try-with-resources.

### Verified

Final sweep against all three IT fixtures — counts match the
documented 2026-05-14 baselines exactly:

- `quickstart-example`: **435** artifacts
- `multi-module-example`: **394** artifacts
- `spring-boot-example`: **822** artifacts

## Helper script: `analyze-test-projects.sh`

Top-level one-shot that packages the analyzer with `./mvnw -q
package` and then runs it against each fixture under
`src/it/projects/`, `cd`-ing into each project so the result and
coords files land next to that project's `pom.xml` (overwriting
whatever's there). This is the same sweep used to validate every
commit in today's modernization and refactor work — now reusable
from one command.

To stay version-agnostic, the script `cp`s
`target/maven-build-requirements-*.jar` to a stable
`target/maven-build-requirements.jar` after the package phase and
invokes that. The glob only matches the shaded jar (the
`original-*` pre-shade copy and the stable copy itself both have
different prefixes), so a future `pom.xml` version bump won't
require a script edit.

README's "Test projects" section updated to point at the script.
Top-level H1 lowercased to match the artifactId, and the redundant
"Apache Maven 3.9+ on `PATH`" line dropped from "Requirements"
(the project ships a wrapper, and the analyzer only shells out to
`mvn` as a fallback when no wrapper is configured).

## Super POM loader

New `SuperPomLoader` in `com.simpligility.maven.analysis`, wired
into `BuildRequirementsAnalyzer.call()` between `DependencyResolver`
and `LifecyclePluginLoader`. Mirrors the `LifecyclePluginLoader`
pattern: resolve
`org.apache.maven:maven-model-builder:<wrapper-version>`, open the
jar, parse `org/apache/maven/model/pom-4.0.0.xml`, and merge any
plugins from the super POM's `<pluginManagement>` into the plugin
candidate map via `putIfAbsent` (so explicit project versions
always win).

The super POM in 3.9.x's `<pluginManagement>` currently has just
four plugins — `maven-antrun-plugin`, `maven-assembly-plugin`,
`maven-dependency-plugin`, `maven-release-plugin` — and a comment
notes they're being phased out (MNG-4453). None of the three IT
fixtures declares any of these without an explicit version, so the
loader reports `Added 0 super POM plugin default(s).` for all
three and artifact counts are unchanged (435 / 394 / 822).

This narrowly closes the "Cross-version super POM" item from the
prior status entry: the analyzer now consults the wrapper-version's
super POM rather than mima's bundled 3.9.x default. The fully
correct fix — *overriding* plugin versions that mima's bundled
super POM filled in for explicitly-declared-but-version-less
plugins — would still require deeper mima plumbing; left as a
follow-up.

## Outstanding tasks

Carried forward from prior entries plus a few items surfaced
during today's work. Nothing here is blocking.

### Open

- **Super POM override semantics**: `SuperPomLoader` now merges
  super POM plugins into the candidate map for the wrapper-version,
  but doesn't *override* mima's bundled-super-POM defaults that
  were applied during effective-model construction. A project that
  declares e.g. `maven-assembly-plugin` without a version still
  ends up with whatever default mima's bundled 3.9.x super POM
  picked, not the wrapper-version's default. The fully correct fix
  needs the analyzer to also re-parse each raw `pom.xml` to find
  no-version plugin declarations and only override those.
- **No `--version` output**: `-V, --version` is enabled via
  picocli's `mixinStandardHelpOptions` but no `IVersionProvider`
  is wired up, so the flag prints an empty line. Should at least
  print the project's `pom.xml` version, ideally also the mima/
  toolbox versions it was built against.
- **No unit tests**: the three IT fixtures serve as integration
  smoke tests but there's no JUnit suite. Lowest-friction targets
  for unit coverage now that the refactor's landed: `Dom`
  (`directText`/`directElement`/`elements`/`newDocumentBuilder`)
  and `Coords` (especially `artifactFromMavenUrl`, which has
  enough URL-parsing branches to be worth pinning down).
- **maven-invoker-plugin not wired up**: `src/it/projects/` is the
  standard invoker layout but the plugin isn't configured. Today
  the fixtures are validated by hand or via
  `analyze-test-projects.sh`. Wiring up invoker would let CI run
  them.

### Nice-to-have

- **Failure mode when local cache is empty**: the analyzer
  surfaces raw mima resolver errors when the user hasn't run
  `mvn install` yet. A clearer up-front message ("no artifacts in
  local cache; run `mvn dependency:resolve` first") would
  improve onboarding.
- **maven-shade-plugin overlapping-resource warnings**: cosmetic
  but loud at build time. Could be silenced with explicit
  filter/transformer config in `pom.xml`.

---

# Status report — 2026-05-14

## Refactor: project layout and chainctl removal

### Layout

- Maven project lifted from `maven-chainguard-checker/` to the repo root.
  `pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/`, and `src/main/java/dev/chainguard/`
  are now at the top level; the `maven-chainguard-checker/` directory is gone.
- Test projects moved from `test-projects/` to `src/it/projects/<project>/`.
  This is the standard maven-invoker-plugin layout but the plugin is **not**
  wired up yet — the directory is just a clean home that doesn't interfere with
  the root Maven build (sub-poms are not declared as modules, so the reactor
  ignores them).

### chainctl removal

- `ChainguardChecker.java` no longer shells out to `chainctl`. Removed
  the `--verify`/`-y` flags, the `runChainctl`/`confirmProceed`/`askYesNo`
  helpers, the `PERCENTAGE_PATTERN`/`ANSI_PATTERN` constants, the Step 4
  per-scope verification block, and the summary/coverage output. The tool
  now always writes a human-readable report plus the sorted coords file,
  then exits. `SCOPE_ORDER` is kept for later use.
- `chainguard-check.sh` and its helper `updatescriptintests.sh` deleted —
  without chainctl, the script just wrapped `mvn dependency:list` and didn't
  add enough over the Java tool to justify keeping. `.gitignore` updated to
  drop the now-irrelevant globs.

### Rename

- `groupId` `dev.chainguard` → `com.simpligility.maven`
- `artifactId` `maven-chainguard-checker` → `maven-build-requirements`
- Java package `dev.chainguard` → `com.simpligility.maven` (class
  `ChainguardChecker` kept for now)
- Shade plugin `mainClass`, pom `<name>`/`<description>`, and README title
  updated to match. Output JAR is now
  `target/maven-build-requirements-1.0-SNAPSHOT.jar`.
- Class renamed `ChainguardChecker` → `BuildRequirementsAnalyzer`. picocli
  `@Command(name = …)` is now `build-requirements-analyzer`.
- Test projects' own coordinates updated (per-project namespace):
  - `quickstart-example`: groupId/package `com.simpligility.maven.quickstart`
  - `multi-module-example` + sub-modules: `com.simpligility.maven.multimodule`
    (sub-modules share the package, matching the pre-refactor split-package
    layout)
  - `spring-boot-example`: `com.simpligility.maven.springboot`
- End-to-end smoke tests pass: `quickstart-example` (434 artifacts) and
  `multi-module-example` (251 artifacts) both build and analyze cleanly.

### Verified

- `./mvnw clean compile` succeeds.

## Output and analysis improvements

Iterative additions during the same session (commits `eef6a67` →
`abbd00d`):

- Output filenames changed to `maven-build-requirements-{results,coords}.txt`
  (no more `chainguard-check-java-*`).
- Report header now includes the analyzed project's `Group ID`,
  `Artifact ID`, and `Version` (parent fallback applied). Retitled
  to "Maven build requirements analysis".
- Parse `.mvn/extensions.xml`. Each declared extension is treated
  like a plugin: extension JAR + parent POM lineage + transitive
  deps all flow into the report. Three new sections + three new
  summary counters.
- Added `mimir 0.11.3` (`eu.maveniverse.maven.mimir:extension3`) to
  `spring-boot-example/.mvn/extensions.xml` so the new code path is
  exercised by an example.
- Maven wrapper distribution: if `.mvn/wrapper/maven-wrapper.properties`
  is present, parse the `distributionUrl` and add the binary
  distribution as a build requirement.
- New Step 0 "Maven environment" detects the Maven version to use
  for lifecycle and reports the source: wrapper distributionUrl
  preferred, `mvn --version` on PATH as fallback, warning otherwise.
- Effective POM models now built for **every** reactor pom (root +
  sub-modules), not just the root. Critical for multi-module
  projects with `packaging=pom` roots.
- Lifecycle-bound plugins (compiler/jar/surefire/resources/install/
  deploy/...) loaded by resolving `org.apache.maven:maven-core:<ver>`
  and parsing its `META-INF/plexus/default-bindings.xml`, then
  injected into the plugin candidate set per module packaging.
- Tightened report layout: one line per artifact, no blank lines
  between entries. New `--paths` flag (default false) optionally
  appends the local repo path on the same line as the GAV.

### Verified

- All three example projects run end-to-end via the shaded JAR:
  - `quickstart-example`: 435 artifacts
  - `multi-module-example`: 394 artifacts (was 252 before lifecycle
    plugins; added 6 lifecycle plugins → +12 plugin parent POMs and
    +124 plugin transitive deps)
  - `spring-boot-example`: 822 artifacts (incl. 1 extension + 2
    extension parent POMs + 40 extension deps + 1 Maven distribution)
- Local-`mvn` fallback for Maven version detection verified by
  temporarily hiding the wrapper config: tool used `mvn 3.9.15` from
  PATH and reported the source correctly.

### Known limitations / next steps

- Super POM is still loaded from whatever Maven model-builder mima
  bundles (3.9.x), not from the wrapper-specified Maven version.
  Cross-version super POM swapping would require deeper plumbing.
- `BuildRequirementsAnalyzer.java` is now ~900 lines as one class.
  Captured in a project memory as the next refactor: split the
  monolith into a small CLI orchestrator plus per-task helpers
  (env detection, effective models, lifecycle bindings, plugin/
  extension resolvers, report writer).

---

# Status report — 2026-05-01

## Java implementation: `maven-chainguard-checker/`

### Completed this session

**Fix: BOM-managed dependency resolution (commit `f73e25f`)**

The DOM-based Pass 2 could not follow `scope=import` BOM entries in
`<dependencyManagement>`, leaving projects like `quickstart-example` (uses
`junit-bom`) and `spring-boot-example` (uses `spring-boot-dependencies`) with
zero resolved dependencies.

Fix: build the effective POM model *before* the transitive resolution step.
`effectiveModel.getDependencies()` returns direct deps with BOM-managed versions
already resolved. These are used as the `CollectRequest` seeds; the DOM-parsed
`uniqueDeps` is kept as a fallback for multi-module projects where the root
effective model has no direct deps of its own.

Results: `quickstart-example` 0 → 5 deps; `spring-boot-example` 0 → 100 deps.

---

**`--verify` flag makes chainctl opt-in (commit `f73e25f`)**

Default run now just resolves and prints the full artifact list, then exits.
`chainctl libraries verify` is only triggered with `--verify`. `--yes` still
skips the two-step confirmation prompt when `--verify` is active.

---

**Artifact coordinates include type/extension and classifier (commit `97b0bc3`)**

Added `artifactCoords(Artifact)` helper. Format is `g:a:type:version` (or
`g:a:type:classifier:version` when a classifier is present). All five artifact
maps and every line of output now use this format.

---

**Plugin full dependency tree and parent POMs (commit `bb55501`)**

For each resolved plugin, call `MavenModelReader.readModel()` with
`ModelRequest.builder().setArtifact(pluginPomArtifact)` to load the plugin's
effective model from the repository (no local pom.xml needed). From the
response:
- `getLineage()` → plugin parent POMs (deduplicated against project parent POMs)
- `getEffectiveModel().getDependencies()` → seeds a per-plugin `CollectRequest`
  for the full transitive dependency tree

Results appear as two new sections: **Plugin parent POMs** and **Plugin
dependencies**. Plugin JARs already listed in Plugins are excluded from Plugin
dependencies to avoid double-counting.

---

**Sorted deduplicated artifact coordinates output file (commit `8516ace`)**

A `TreeSet<String>` is populated on the fly as each artifact is resolved across
all five categories. Written to `chainguard-check-java-coords.txt` (alongside
the main results file) after all resolution is complete — one coordinate per
line, alphabetically sorted, deduplicated.

---

### Test results (2026-05-01)

| Project | Project deps | Project parent POMs | Plugins | Plugin parent POMs | Plugin deps | Total |
|---------|-------------|---------------------|---------|-------------------|-------------|-------|
| `quickstart-example` | 5 | 0 | 13 | 17 | 399 | 434 |
| `multi-module-example` | 19 | 0 | 4 | 12 | 216 | 251 |
| `spring-boot-example` | 100 | 2 | 30 | 28 | 618 | 778 |

Output files per project:
- `chainguard-check-java-results.txt` — human-readable full report
- `chainguard-check-java-coords.txt` — sorted, deduplicated coordinate list

### Next steps

- Sub-module effective models (requires reactor root installed to local repo)
- `--verbose` / `--detailed` chainctl passthrough flags

---

# Status report — 2026-04-30

## Java implementation: `maven-chainguard-checker/`

### Completed this session

**Effective POM model for plugin and parent POM discovery**

Replaced the DOM-based Pass 3 (which could only see plugins declared with explicit versions in
local pom.xml files) with a proper effective POM computation using the
`eu.maveniverse.maven.mima.extensions:mmr` extension (`MavenModelReader`).

**How it works:**
- After the mima `Context` is opened, `MavenModelReader(context)` is instantiated.
- `reader.readModel(ModelRequest.builder().setPomFile(rootPom).build())` builds the full
  effective model for the root pom — resolving the entire parent chain exactly as
  `mvn help:effective-pom` does (via `ModelBuilder` + `ModelResolverImpl` wired through mima).
- Plugins are collected from `effectiveModel.getBuild().getPluginManagement().getPlugins()`
  (the full merged plugin management from all parents — always has resolved versions) and
  supplemented by `effectiveModel.getBuild().getPlugins()` for any with explicit versions.
- Parent POMs are taken from `response.getLineage()`, filtering out reactor module coords and
  the empty-string super POM entry.
- Sub-module pom files are still DOM-scanned for any explicitly-versioned plugins unique to
  those modules (DOM fallback uses `putIfAbsent` so effective model entries take priority).

**New compile dependencies added to `maven-chainguard-checker/pom.xml`:**
- `eu.maveniverse.maven.mima.extensions:mmr:2.4.25` — was already transitive, now explicit
- `org.apache.maven:maven-model:3.9.9` — needed to compile against `Build`, `Model`,
  `Plugin`, `PluginManagement` from the Maven model API

**Test results:**

| Project | Deps | Parent POMs | Plugins | Total | Coverage |
|---------|------|-------------|---------|-------|----------|
| `spring-boot-example` | 0 (BOM limitation) | 2 | 30 | 32 | 65% (21/32) |
| `multi-module-example` | 19 | 0 | 4 | 23 | ~21% |

`spring-boot-example` parent lineage resolved: `spring-boot-starter-parent:3.5.0` →
`spring-boot-dependencies:3.5.0`. The 30 plugins include `maven-compiler-plugin`,
`maven-surefire-plugin`, `spring-boot-maven-plugin`, `native-maven-plugin`, and the full
set of plugins managed by the Spring Boot parent — all with resolved versions.

### Known limitations

- **BOM imports** (`scope=import` in `<dependencyManagement>`): dependency versions that come
  solely from imported BOMs (e.g., spring-boot-dependencies) are still unresolvable in the
  DOM-based dependency pass (Pass 2). The `spring-boot-example` therefore shows 0 resolved
  dependencies despite declaring several. This is the primary remaining gap.
- Effective model is only built for the root pom. Sub-modules whose parent is a reactor
  module cannot be passed through `MavenModelReader` without the parent being installed to the
  local repo first.

### Next steps

- Replace the DOM-based dependency pass (Pass 2) with mima/resolver-based model loading so
  BOM-managed dependency versions are resolved correctly (this is the major remaining gap).
- Consider reading sub-module effective models when the reactor root is already installed.
- Add `--verbose` / `--detailed` chainctl passthrough flags.

---

# Status report — 2026-04-24

## Java implementation: `maven-chainguard-checker/`

### Completed this session

- **Two-step confirmation gate** (commit `698fbb3`) — splits execution into analysis and
  chainctl verification phases. Between phases, the user is prompted twice before chainctl
  runs begin. If the user declines at either prompt, analysis results are saved and the tool
  exits cleanly. `--yes` / `-y` flag skips both prompts for scripted/CI use.
  - When no interactive terminal is detected (`System.console() == null`), both prompts
    auto-proceed so CI pipelines are not blocked.
  - Tested against `multi-module-example`: 19 artifacts, 4 at 100%, 21% overall.

### Next step planned (not yet started)

Expand the analysis phase to also collect all Maven plugins declared in the project.

- Use `MavenXpp3Reader` (from `org.apache.maven:maven-model`) to parse each pom.xml into a
  typed `Model` object — this enables walking `model.getBuild().getPlugins()` and
  `model.getBuild().getPluginManagement().getPlugins()` instead of raw DOM.
- Collect unique plugins (by G:A) across all reactor modules, resolving version property
  expressions using the existing `properties` map.
- Print plugin list in the analysis phase output.
- **Second later step**: resolve each plugin's transitive dependencies and add them to the
  artifact list for chainctl verification.
- `maven-model` is likely already transitively available via mima/toolbox; add as explicit
  compile dependency to be safe.

---

# Status report — 2026-04-01

## Java implementation: `maven-chainguard-checker/`

Working first version. Compiles and runs end-to-end.

**Dependencies:**
- `eu.maveniverse.maven.mima:context:2.4.25` — bootstraps Maven Resolver
- `eu.maveniverse.maven.mima.runtime:standalone-static:2.4.25` — standalone runtime
- `eu.maveniverse.maven.toolbox:shared:0.9.0` — dependency resolution utilities
- `info.picocli:picocli:4.7.7` — CLI (`--project`, `--output` flags)
- `org.codehaus.plexus:plexus-xml:3.0.1` — required by standalone-static runtime
- `org.slf4j:slf4j-simple:2.0.17` — logging backend

**Packaged as:** executable fat JAR via Maven Shade plugin

```bash
java -jar maven-chainguard-checker/target/maven-chainguard-checker-1.0-SNAPSHOT.jar
```

**Flow:**
1. Discovers all `pom.xml` files in the reactor (handles multi-module)
2. Collects reactor module coordinates for filtering
3. Reads `<properties>` and `<dependencyManagement>` from all poms for version resolution
4. Resolves dependency declarations (including property expressions and managed versions)
5. Bootstraps mima `Context` (reads `~/.m2/settings.xml` automatically)
6. Resolves transitive dependencies via maven-resolver `CollectRequest` + `DependencyRequest`
7. Logs resolved GAV + local path per artifact
8. Runs `chainctl libraries verify` per artifact, grouped by effective scope
9. Outputs summary to stdout and `chainguard-check-java-results.txt`

**Tested against:** `test-projects/multi-module-example` — 19 artifacts resolved, 5 at 100.00%

## Git log additions since last status

```
b63a686 Add Java implementation of the Chainguard dependency checker
ecfec09 Add status report
93ccb09 Add dependency list log before chainctl verification
ffc1315 Skip project's own reactor modules from chainctl verification
8279006 Fix bash version compatibility for macOS
```

## Next steps

- Replace manual pom.xml DOM parsing with mima/toolbox resolver-based model loading
  (handles BOM imports, complex property chains, without using DefaultModelBuilder directly)
- Run Java implementation against `spring-boot-example`
- Add `--verbose` / `--detailed` chainctl passthrough flags
- Investigate Spring Boot test failure (currently needs `-DskipTests`)

---

# Status report — 2026-03-27

## Script: `chainguard-check.sh`

Working and committed. 5 commits on `master`:

| Commit | Description |
|--------|-------------|
| `93ccb09` | Log all resolved GAV + paths before verification |
| `ffc1315` | Skip project's own reactor modules from chainctl |
| `8279006` | Fix bash version compat (env shebang + version check) |
| `4c7cdc8` | Add Maven wrapper to test projects |
| `35b5858` | Initial implementation |

## Script flow (current)

1. Confirm build / offer to run `mvn clean install`
2. Collect reactor module coordinates to exclude
3. Resolve full dependency list with absolute paths
4. Log all resolved dependencies (GAV + cache path)
5. Run `chainctl libraries verify` per artifact, grouped by scope
6. Print summary + write `chainguard-check-results.txt`

## Test results

- **quickstart-example** — 5 test deps, all `0.00%` (expected)
- **multi-module-example** — 10/20 artifacts at `100.00%` (commons-lang3, slf4j, logback, guava family)
- **spring-boot-example** — run abandoned (large tree); test failure unresolved

## Next steps

- Investigate Spring Boot test failure
- Consider `--detailed`/`--verbose` chainctl flags
- Start Java rewrite using `maven-repository-tools` / `maveniverse/mima` / `toolbox`
