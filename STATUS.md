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
