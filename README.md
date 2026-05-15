# maven-build-requirements

A tool that analyzes a Maven project's dependencies, plugins, and parent POMs
and writes the resolved artifact coordinates and a human-readable report to
disk. Compared to a simple dependency list, the resulting list includes all
dependencies necessary to build the project.

Designed to better understand your requirements to build a specific Maven
project from source and feed downstream tools such as the coverage checker for
[Chainguard Libraries for Java](https://www.chainguard.dev/libraries) for
further analysis.

The scope is *everything* needed to build the project from source — not
just the runtime/compile dependency tree:

- The project's own transitive dependency tree across all reactor modules
  (the same set `mvn dependency:tree` would produce).
- Every plugin used by the build — explicitly declared *and* implicitly
  bound to a packaging's default lifecycle via Maven core — together
  with each plugin's parent POM lineage and full transitive dependency
  tree.
- Build extensions from `.mvn/extensions.xml`, again with parent POMs
  and transitive dependencies.
- The project's own parent POM lineage.
- The Maven binary distribution declared by the project's wrapper.

## Requirements

- JDK 25+ to build and run
- A successfully built Maven project with dependencies resolved to the local cache

## Building

```bash
./mvnw package
```

Produces an executable fat JAR at `target/maven-build-requirements-1.0-SNAPSHOT.jar`.

## Usage

From the root directory of the Maven project you want to analyze:

```bash
java -jar /path/to/maven-build-requirements-1.0-SNAPSHOT.jar
```

Flags:

- `-p, --project <dir>` — project directory containing `pom.xml` (default: current directory)
- `-o, --output <file>` — human-readable report (default: `maven-build-requirements-results.txt`)

The tool also writes a sorted, deduplicated list of artifact coordinates to
`maven-build-requirements-coords.txt` alongside the report.

## Limitations

The analyzer covers the project's full transitive dependency tree and then
goes further — plugins, their parent POMs and transitive deps,
lifecycle-bound plugins, build extensions and their deps, parent POM
lineage, and the Maven distribution itself. A few corners of Maven's
model aren't (yet) honoured:

- **Plugin `<dependencies>` overrides in the effective POM** are not
  applied when computing a plugin's transitive tree. The analyzer walks
  each plugin's published POM — so a project that injects an extra
  dependency into, say, `maven-surefire-plugin` via
  `<plugin><dependencies>` will see the *published* tree only.
- **Cross-version super POM**: the analyzer now consults the
  wrapper-specified Maven version's super POM for plugin defaults, but
  doesn't yet override the defaults that mima's bundled model-builder
  applied while building the effective model. Plugins declared
  *without* a version may report the mima-bundled default instead of the
  wrapper-version's default.
- **Local repository must be populated**. The analyzer reads from your
  local Maven cache; if an artifact isn't there yet, run a Maven build
  of the target project first (e.g. `mvn -DskipTests install`) so
  everything is resolved.
- **Profile activation reflects the analyzer's JVM/OS**, not necessarily
  the build machine. Profiles activated by JDK version, OS, or file
  presence are evaluated where the analyzer runs.
- **Maven 4 not yet validated**. The lifecycle-binding and super POM
  loaders target the 3.9.x layout. Pointing the analyzer at a Maven-4
  project may work for the resolution flow but the version-specific
  defaults won't be applied correctly.

## Test projects

The `src/it/projects/` directory contains example Maven projects for trying the tool:

- `quickstart-example` — minimal Maven quickstart project
- `multi-module-example` — multi-module project with compile, runtime, and test dependencies
- `spring-boot-example` — Spring Boot application with web, JPA, security, and actuator

To build the analyzer and run it against all three test projects in one go:

```bash
./analyze-test-projects.sh
```

The script packages the analyzer with `./mvnw package` and then invokes it
in each test project's directory, leaving `maven-build-requirements-results.txt`
and `maven-build-requirements-coords.txt` next to that project's `pom.xml`
(overwriting any existing files there).

## Thanks

This tool leans heavily on [mima](https://maveniverse.eu/docs/mima/) from the
[Maveniverse](https://maveniverse.eu/) project. mima bootstraps Maven Resolver
from a standalone context, and its MMR extension builds effective POM models
with parent inheritance and plugin management already applied. Without mima
this analyzer would essentially be reimplementing Maven's model-builder by
hand — many thanks to its authors and maintainers.
