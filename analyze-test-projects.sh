#!/usr/bin/env bash
#
# Builds the analyzer, then runs it against each integration-test project
# under src/it/projects/. The result and coords files end up next to each
# project's pom.xml, overwriting any existing files there.
#
set -euo pipefail

cd "$(dirname "$0")"

./mvnw -q package

JAR="$(pwd)/target/maven-build-requirements-1.0-SNAPSHOT.jar"

for project in quickstart-example multi-module-example spring-boot-example; do
    echo
    echo "=== $project ==="
    (cd "src/it/projects/$project" && java -jar "$JAR" -p .)
done
