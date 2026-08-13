#!/usr/bin/env bash
#
# Render the dependency hairball for an analyzed Maven project.
#
# Usage: graph/render.sh <project-dir>
#
# Runs build-graph.py to build the DOT from the project's
# maven-build-requirements-results.txt and pom.xml, then renders it with
# Graphviz. Outputs land next to the results and coords files:
#
#   maven-build-requirements-graph.dot   always
#   maven-build-requirements-graph.svg   when Graphviz (fdp) is on PATH
#   maven-build-requirements-graph.png   when Graphviz (fdp) is on PATH
#
# The PNG is sized to 1340px on its binding dimension, aimed at a blog-width
# hero, and shrunk to a small palette when pngquant or ImageMagick is present.
# Missing Graphviz is not an error: the DOT is still written and the script
# exits cleanly so a batch run is never blocked.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"

if [[ $# -lt 1 ]]; then
    echo "usage: $(basename "$0") <project-dir>" >&2
    exit 2
fi
project_dir="$1"
base="$project_dir/maven-build-requirements-graph"

# Always build the DOT.
python3 "$here/build-graph.py" "$project_dir"

if ! command -v fdp >/dev/null 2>&1; then
    echo "  Graphviz (fdp) not on PATH; wrote DOT only, skipping SVG/PNG." >&2
    echo "  Install it, for example with: brew install graphviz" >&2
    exit 0
fi

# 13.4in * 100dpi = 1340px on the binding dimension.
fdp -Tsvg "$base.dot" -o "$base.svg"
fdp -Tpng -Gdpi=100 -Gsize=13.4,13.4 "$base.dot" -o "$base.png"

# The hairball is flat color on white, so a small palette compresses it hard
# with no visible loss, taking the PNG from several hundred KB to under 100KB.
if command -v pngquant >/dev/null 2>&1; then
    pngquant --force --skip-if-larger --quality=70-90 --strip \
        --output "$base.png" "$base.png" || true
elif command -v magick >/dev/null 2>&1; then
    magick "$base.png" -colors 64 -strip "PNG8:$base.png"
fi

echo "  wrote $base.svg and $base.png"
