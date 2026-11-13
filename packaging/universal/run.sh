#!/usr/bin/env sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ -n "${JAVA_HOME:-}" ]; then java="$JAVA_HOME/bin/java"; else java=java; fi
if ! command -v "$java" >/dev/null 2>&1; then
  printf 'NexoDB Studio requires Java 17 or newer. Set JAVA_HOME or install a Java runtime.\n' >&2
  exit 1
fi
exec "$java" -Dfile.encoding=UTF-8 -Djava.net.useSystemProxies=true -Xms16m -Xmx256m -jar "$root/app/nexodb-studio.jar" "$@"
