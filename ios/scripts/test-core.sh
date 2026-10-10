#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
task_swift="${MILKYWAY_SWIFT:-swift}"
task_cache="${MILKYWAY_SWIFT_CACHE:-/tmp/milkyway-swift-cache}"
mkdir -p "$task_cache"
export XDG_CACHE_HOME="$task_cache"
export CLANG_MODULE_CACHE_PATH="$task_cache/modules"
"$task_swift" test --cache-path "$task_cache/packages" --config-path "$task_cache/config" --security-path "$task_cache/security"
