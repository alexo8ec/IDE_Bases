#!/usr/bin/env bash
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
bash "$root/build.sh"
exec java -Dfile.encoding=UTF-8 -Djava.net.useSystemProxies=true -jar "$root/build/nexodb-studio.jar"
