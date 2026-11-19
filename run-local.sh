#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

# This checkout can use a project-local toolchain or a system installation.
shopt -s nullglob
if [[ -z "${JAVA_HOME:-}" ]]; then
    for java_home in "$ROOT"/.tools/zulu*/Contents/Home "$ROOT"/.tools/zulu*/zulu*.jdk/Contents/Home; do
        if [[ -x "$java_home/bin/java" ]]; then
            export JAVA_HOME="$java_home"
            break
        fi
    done
fi
maven=mvn
if ! command -v "$maven" >/dev/null 2>&1; then
    for local_maven in "$ROOT"/.tools/apache-maven-*/bin/mvn; do
        if [[ -x "$local_maven" ]]; then
            maven="$local_maven"
            break
        fi
    done
fi
if ! command -v "$maven" >/dev/null 2>&1; then
    echo "Maven is required. Install it and use JDK 21 or newer." >&2
    exit 1
fi

export LIBRARY_PORT="${LIBRARY_PORT:-8080}"
export LIBRARY_DB="${LIBRARY_DB:-$ROOT/data/library.db}"
api_url="http://127.0.0.1:$LIBRARY_PORT/api"
export LIBRARY_API_URL="${LIBRARY_API_URL:-$api_url}"

if curl --silent --max-time 1 --output /dev/null "$api_url/health"; then
    echo "Port $LIBRARY_PORT is already serving HTTP. Choose another LIBRARY_PORT." >&2
    exit 1
fi

"$maven" --batch-mode -q package
"$maven" --batch-mode -q -pl backend exec:java &
backend_pid=$!
trap 'kill "$backend_pid" 2>/dev/null || true; wait "$backend_pid" 2>/dev/null || true' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

for ((attempt = 0; attempt < 300; attempt++)); do
    if ! kill -0 "$backend_pid" 2>/dev/null; then
        echo "The library API stopped before becoming ready." >&2
        exit 1
    fi
    if curl --silent --fail --max-time 1 --output /dev/null "$api_url/health"; then
        "$maven" --batch-mode -q -pl frontend javafx:run
        exit
    fi
    sleep 0.2
done

echo "The library API did not become ready at $api_url." >&2
exit 1
