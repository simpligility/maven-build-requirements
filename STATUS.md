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
