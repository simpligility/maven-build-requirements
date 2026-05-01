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
