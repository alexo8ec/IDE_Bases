#!/usr/bin/env bash
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
classes="$root/build/classes"
[[ "$classes" == "$root/build/classes" ]] || exit 1
rm -rf -- "$classes"
mkdir -p "$classes" "$root/build/lib" "$root/build/drivers"
for dependency in \
  flatlaf-3.7.2/flatlaf-3.7.2.jar \
  flatlaf-extras-3.7.2/flatlaf-extras-3.7.2.jar \
  jsvg-2.1.0/jsvg-2.1.0.jar \
  waffle-jna-3.6.0/waffle-jna-3.6.0.jar \
  windows-auth-dependencies/jna-5.18.1.jar \
  windows-auth-dependencies/jna-platform-5.18.1.jar \
  windows-auth-dependencies/slf4j-api-2.0.17.jar \
  windows-auth-dependencies/caffeine-3.2.3.jar \
  windows-auth-dependencies/checker-qual-3.53.1.jar; do
  cp "$root/third-party/$dependency" "$root/build/lib/"
done
for dependency in \
  mysql-connector-j-26.10.0/mysql-connector-j-26.10.0.jar \
  mariadb-java-client-4.0.0/mariadb-java-client-4.0.0.jar \
  mariadb-java-client-3.5.11/mariadb-java-client-3.5.11.jar; do
  cp "$root/third-party/$dependency" "$root/build/drivers/"
done
sources=()
while IFS= read -r -d '' source; do sources+=("$source"); done < <(find "$root/src" -name '*.java' -print0)
javac --release 17 -encoding UTF-8 -cp "$root/build/lib/*" -d "$classes" "${sources[@]}"
cp -R "$root/resources/." "$classes/"
printf '%s\n' 'Manifest-Version: 1.0' 'Main-Class: com.nexodb.App' \
  'Class-Path: lib/flatlaf-3.7.2.jar lib/flatlaf-extras-3.7.2.jar lib/jsvg-2.1.0.jar lib/waffle-jna-3.6.0.jar lib/jna-5.18.1.jar lib/jna-platform-5.18.1.jar lib/slf4j-api-2.0.17.jar lib/caffeine-3.2.3.jar lib/checker-qual-3.53.1.jar' '' > "$root/build/MANIFEST.MF"
jar --create --file "$root/build/nexodb-studio.jar" --manifest "$root/build/MANIFEST.MF" -C "$classes" .
printf 'Built: %s\n' "$root/build/nexodb-studio.jar"
