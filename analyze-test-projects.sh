#!/usr/bin/env bash
#
# Builds the analyzer, then runs it against each integration-test project
# under src/it/projects/. The result and coords files end up next to each
# project's pom.xml, overwriting any existing files there.
#
set -euo pipefail

cd "$(dirname "$0")"

./mvnw -q package

# Copy the version-stamped shaded jar to a stable name so this script
# doesn't have to track the project's version.
cp target/maven-build-requirements-*.jar target/maven-build-requirements.jar
JAR="$(pwd)/target/maven-build-requirements.jar"

for project in quickstart-example multi-module-example spring-boot-example; do
    echo
    echo "=== $project ==="
    (cd "src/it/projects/$project" && java -jar "$JAR" -p .)
done
