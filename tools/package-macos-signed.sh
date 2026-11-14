#!/usr/bin/env bash
# Run on macOS with an installed Developer ID Application identity and notarytool profile.
set -euo pipefail
[[ "$(uname -s)" == Darwin ]] || { printf 'Run this script on macOS.\n' >&2; exit 1; }
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
: "${NEXODB_MAC_SIGN_USER:?Set the team/user portion of your Developer ID Application identity}"
: "${NEXODB_NOTARY_PROFILE:?Set an existing notarytool Keychain profile; never put passwords in this script}"
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
for command in jpackage java xcrun codesign spctl ditto security; do command -v "$command" >/dev/null || { printf 'Missing tool: %s\n' "$command" >&2; exit 1; }; done
security find-identity -v -p codesigning | grep -F -- "Developer ID Application: $NEXODB_MAC_SIGN_USER" >/dev/null || { printf 'Developer ID Application identity not found.\n' >&2; exit 1; }
cpu="$(java -XshowSettings:properties -version 2>&1 | awk '$1 == "os.arch" && $2 == "=" { print $3 }')"
case "$cpu" in aarch64|arm64) arch=arm64 ;; x86_64|amd64) arch=x64 ;; *) printf 'Unsupported JDK architecture: %s\n' "$cpu" >&2; exit 1 ;; esac
version="$(cat "$root/VERSION")"
work="$root/build/mac-sign-$arch"
[[ "$work" == "$root/build/mac-sign-$arch" ]] || exit 1
rm -rf -- "$work"
mkdir -p "$work/input" "$work/image" "$root/dist"
bash "$root/build.sh"
cp "$root/build/nexodb-studio.jar" "$work/input/"
cp -R "$root/build/lib" "$root/build/drivers" "$work/input/"
options=(--type app-image --name 'AdisDB' --app-version "$version"
  --vendor 'Alex Mite Salazar' --input "$work/input" --dest "$work/image"
  --main-jar nexodb-studio.jar --main-class com.nexodb.App
  --mac-package-identifier com.nexodb.studio --mac-package-name 'AdisDB'
  --mac-sign --mac-signing-key-user-name "$NEXODB_MAC_SIGN_USER"
  --mac-package-signing-prefix com.nexodb.studio.
  --add-modules 'java.desktop,java.sql,java.prefs,java.naming,java.net.http,java.security.jgss,jdk.security.auth,jdk.crypto.ec'
  --java-options '-Dfile.encoding=UTF-8' --java-options '-Djava.net.useSystemProxies=true'
  --java-options '-Xms16m' --java-options '-Xmx256m' --java-options '-XX:+UseSerialGC')
if [[ -n "${NEXODB_MAC_SIGN_KEYCHAIN:-}" ]]; then options+=(--mac-signing-keychain "$NEXODB_MAC_SIGN_KEYCHAIN"); fi
# jpackage supplies the hardened-runtime entitlements needed by Java and signs native components.
jpackage "${options[@]}"
app="$work/image/AdisDB.app"
codesign --verify --deep --strict --verbose=2 "$app"
ditto -c -k --keepParent "$app" "$work/notary-submit.zip"
xcrun notarytool submit "$work/notary-submit.zip" --keychain-profile "$NEXODB_NOTARY_PROFILE" --wait --output-format json > "$work/notary-result.json"
python3 -c 'import json,sys; result=json.load(open(sys.argv[1])); sys.exit(0 if result.get("status")=="Accepted" else "Apple did not accept notarization; inspect the local result and notarytool log.")' "$work/notary-result.json"
xcrun stapler staple "$app"
xcrun stapler validate "$app"
codesign --verify --deep --strict --verbose=2 "$app"
spctl --assess --type execute --verbose=2 "$app"
output="$root/dist/AdisDB-$version-macos-$arch.zip"
ditto -c -k --sequesterRsrc --keepParent "$app" "$output"
(cd "$root/dist" && shasum -a 256 "$(basename -- "$output")") > "$output.sha256"
printf 'Signed, notarized and stapled macOS app: %s\n' "$output"
