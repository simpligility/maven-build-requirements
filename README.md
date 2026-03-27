# Maven Chainguard Checker

A tool that analyzes a Maven project's dependencies and checks which ones are provided
and verified by [Chainguard Libraries for Java](https://www.chainguard.dev/libraries).

## Requirements

- [Apache Maven](https://maven.apache.org/) 3.9+ on your `PATH`
- [chainctl](https://edu.chainguard.dev/chainguard/chainctl-docs/) installed and authenticated
- A successfully built Maven project with dependencies resolved to the local cache

## Usage

Run the script from the root directory of the Maven project you want to analyze:

```bash
/path/to/chainguard-check.sh
```

The script performs the following steps:

1. Asks whether the project has already been built, and offers to run `mvn clean install` if not.
2. Resolves all dependencies via `mvn dependency:list`.
3. Runs `chainctl libraries verify` against each artifact in the local Maven cache (`~/.m2/repository`).
4. Prints results grouped by scope (compile, runtime, provided, test, ...).
5. Prints a summary with the total artifact count and Chainguard coverage percentage.

Results are written to both stdout and `chainguard-check-results.txt` in the project directory.

## Example output

```
Chainguard Coverage Analysis
Date: Thu 26 Mar 2026
Project: /path/to/your/project
=======================================================================

=== compile ===
  org.apache.commons:commons-lang3:3.17.0 => 100.00%
  org.slf4j:slf4j-api:2.0.17 => 100.00%
  com.google.guava:guava:33.4.0-jre => 100.00%
  org.checkerframework:checker-qual:3.43.0 => 0.00%

=== runtime ===
  ch.qos.logback:logback-classic:1.5.18 => 100.00%

=== test ===
  org.junit.jupiter:junit-jupiter:5.11.4 => 0.00%

=======================================================================
Summary
  Total artifacts checked : 7
  Chainguard covered (100%): 4
  Overall coverage        : 57%
=======================================================================
```

## Test projects

The `test-projects/` directory contains example Maven projects for testing the script:

- `quickstart-example` — minimal Maven quickstart project
- `multi-module-example` — multi-module project with compile, runtime, and test dependencies
- `spring-boot-example` — Spring Boot application with web, JPA, security, and actuator
