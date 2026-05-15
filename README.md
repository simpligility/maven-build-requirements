# Maven Build Requirements

A tool that analyzes a Maven project's dependencies, plugins, and parent POMs
and writes the resolved artifact coordinates and a human-readable report to
disk. Compared to a simple dependency list, the resulting list includes all
dependencies necessary to build the project.

Designed to better understand your requirements to build a specific Maven
project from source and feed downstream tools such as the coverage checker for
[Chainguard Libraries for Java](https://www.chainguard.dev/libraries) for
further analysis.

Uses [mima](https://maveniverse.eu/docs/mima/) and the MMR extension to build effective
POM models, walk the full dependency tree, and capture plugins and parent POMs, including
each plugin's own transitive dependencies and parent lineage.

## Requirements

- [Apache Maven](https://maven.apache.org/) 3.9+ on your `PATH`
- JDK 21+ to build and run
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

## Test projects

The `src/it/projects/` directory contains example Maven projects for trying the tool:

- `quickstart-example` — minimal Maven quickstart project
- `multi-module-example` — multi-module project with compile, runtime, and test dependencies
- `spring-boot-example` — Spring Boot application with web, JPA, security, and actuator
