#!/bin/bash
set -euo pipefail

RESULTS_FILE="chainguard-check-results.txt"
DEPS_TEMP=$(mktemp)

# Writes to both stdout and results file
output() {
    echo "$1" | tee -a "$RESULTS_FILE"
}

# Initialize results file
: > "$RESULTS_FILE"

# ── Step 1: Confirm build ────────────────────────────────────────────────────
echo "Has the project been built successfully with 'mvn clean install'? (y/n)"
read -r answer
if [[ "$answer" != "y" && "$answer" != "Y" ]]; then
    echo "Would you like to run 'mvn clean install' now? (y/n)"
    read -r build_answer
    if [[ "$build_answer" == "y" || "$build_answer" == "Y" ]]; then
        mvn clean install
    else
        echo "Please run the build first. Exiting."
        exit 1
    fi
fi

# ── Step 2: Resolve dependency list with absolute paths ──────────────────────
echo ""
echo "Resolving dependencies..."
mvn dependency:list -DoutputAbsoluteArtifactFilename=true -DoutputFile="$DEPS_TEMP" -q

output "Chainguard Coverage Analysis"
output "Date: $(date)"
output "Project: $(pwd)"
output "======================================================================="
output ""

# ── Step 3 & 4: Run chainctl for each artifact, collect by scope ─────────────
declare -A seen_scopes
total=0
covered=0

while IFS= read -r line; do
    # Trim leading/trailing whitespace
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line%"${line##*[![:space:]]}"}"
    [[ -z "$line" ]] && continue

    # Strip ANSI color codes and " -- module ..." suffix appended by Maven
    line=$(echo "$line" | sed 's/\x1b\[[0-9;]*m//g' | sed 's/ -- module .*$//')

    # Expected format: groupId:artifactId:packaging:version:scope:/abs/path
    IFS=':' read -ra parts <<< "$line"
    [[ ${#parts[@]} -lt 6 ]] && continue

    group_id="${parts[0]}"
    artifact_id="${parts[1]}"
    # parts[2] = packaging (jar, pom, etc.)
    version="${parts[3]}"
    scope="${parts[4]}"
    jar_path="${parts[5]}"

    coords="${group_id}:${artifact_id}:${version}"
    total=$((total + 1))

    if [[ ! -f "$jar_path" ]]; then
        result_line="  ${coords} => NOT FOUND in local cache"
    else
        chainctl_out=$(chainctl libraries verify "$jar_path" 2>&1 | sed 's/\x1b\[[0-9;]*m//g' || true)
        percentage=$(echo "$chainctl_out" | grep -oE '[0-9]+\.[0-9]+%' | tail -1)
        if [[ -z "$percentage" ]]; then
            percentage="N/A"
        elif [[ "$percentage" == "100.00%" ]]; then
            covered=$((covered + 1))
        fi
        result_line="  ${coords} => ${percentage}"
    fi

    printf "%s\n" "$result_line" >> "${DEPS_TEMP}.${scope}"
    seen_scopes["$scope"]=1

done < "$DEPS_TEMP"

# ── Step 5: Output results grouped by scope ──────────────────────────────────
# Print well-known scopes first in a logical order, then any others
for scope in compile runtime provided test system import; do
    [[ -z "${seen_scopes[$scope]+x}" ]] && continue
    output "=== ${scope} ==="
    while IFS= read -r line; do
        output "$line"
    done < "${DEPS_TEMP}.${scope}"
    output ""
    rm -f "${DEPS_TEMP}.${scope}"
done

# Any unexpected/custom scopes
for scope in "${!seen_scopes[@]}"; do
    case "$scope" in compile|runtime|provided|test|system|import) continue ;; esac
    output "=== ${scope} ==="
    while IFS= read -r line; do
        output "$line"
    done < "${DEPS_TEMP}.${scope}"
    output ""
    rm -f "${DEPS_TEMP}.${scope}"
done

# ── Summary ──────────────────────────────────────────────────────────────────
output "======================================================================="
output "Summary"
output "  Total artifacts checked : ${total}"
output "  Chainguard covered (100%): ${covered}"
if [[ $total -gt 0 ]]; then
    pct=$(( covered * 100 / total ))
    output "  Overall coverage        : ${pct}%"
fi
output "======================================================================="
output ""
output "Full results saved to: ${RESULTS_FILE}"

rm -f "$DEPS_TEMP"
