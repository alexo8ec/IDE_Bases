#!/usr/bin/env bash
# Build native packages on the target OS with JDK 17+ (no cross-compilation).
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
version="$(cat "$root/VERSION")"
format="${1:-app-image}"
system="$(uname -s)"
case "$system:$format" in
  Darwin:app-image|Darwin:dmg|Darwin:pkg|Linux:app-image|Linux:deb|Linux:rpm) ;;
  *) printf 'Use app-image, deb/rpm on Linux, or dmg/pkg on macOS.\n' >&2; exit 1 ;;
esac
bash "$root/build.sh"
staging="$root/build/native-input"
[[ "$staging" == "$root/build/native-input" ]] || exit 1
rm -rf -- "$staging"
mkdir -p "$staging" "$root/dist/native"
cp "$root/build/nexodb-studio.jar" "$staging/"
cp -R "$root/build/lib" "$root/build/drivers" "$staging/"
options=(--type "$format" --name 'NexoDB Studio' --app-version "$version" \
  --vendor 'Alex Mite Salazar' --description 'Universal JDBC database client' \
  --input "$staging" --dest "$root/dist/native" --main-jar nexodb-studio.jar --main-class com.nexodb.App \
  --add-modules 'java.desktop,java.sql,java.prefs,java.naming,java.net.http,java.security.jgss,jdk.security.auth' \
  --java-options '-Dfile.encoding=UTF-8' --java-options '-Djava.net.useSystemProxies=true' \
  --java-options '-Xms16m' --java-options '-Xmx256m' --java-options '-XX:+UseSerialGC')
if [[ "$system" == Linux ]]; then
  options+=(--icon "$root/resources/com/nexodb/assets/nexodb-icon.png")
  if [[ "$format" != app-image ]]; then options+=(--linux-shortcut --linux-package-name nexodb-studio); fi
else
  options+=(--mac-package-identifier com.nexodb.studio)
fi
jpackage "${options[@]}"
