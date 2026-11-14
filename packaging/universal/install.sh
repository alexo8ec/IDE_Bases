#!/usr/bin/env sh
# Per-user installation; no root privileges or passwords are needed.
set -eu
source_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
case "$(uname -s)" in
  Darwin)
    destination="$HOME/Applications/AdisDB.app"
    mkdir -p "$destination/Contents/MacOS" "$destination/Contents/Resources/NexoDB"
    cp -R "$source_dir/app" "$source_dir/run.sh" "$destination/Contents/Resources/NexoDB/"
    cp "$source_dir/Info.plist" "$destination/Contents/Info.plist"
    cat > "$destination/Contents/MacOS/AdisDB" <<'LAUNCHER'
#!/usr/bin/env sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/../Resources/NexoDB" && pwd)
if [ -z "${JAVA_HOME:-}" ]; then JAVA_HOME=$(/usr/libexec/java_home -v '17+' 2>/dev/null || true); export JAVA_HOME; fi
exec sh "$root/run.sh" "$@"
LAUNCHER
    chmod +x "$destination/Contents/MacOS/AdisDB" "$destination/Contents/Resources/NexoDB/run.sh"
    printf 'Installed: %s\n' "$destination"
    ;;
  Linux)
    data_home=${XDG_DATA_HOME:-"$HOME/.local/share"}
    destination="$data_home/nexodb-studio"
    mkdir -p "$destination" "$data_home/applications"
    cp -R "$source_dir/app" "$source_dir/run.sh" "$source_dir/adisdb-icon.png" "$destination/"
    chmod +x "$destination/run.sh"
    # Escape characters significant in Desktop Entry double-quoted Exec fields.
    escaped=$(printf '%s' "$destination" | sed 's/\\/\\\\/g; s/"/\\"/g; s/`/\\`/g; s/\$/\\$/g; s/%/%%/g')
    cat > "$data_home/applications/com.nexodb.studio.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=AdisDB
Comment=Universal JDBC database client
Exec=sh "$escaped/run.sh"
Icon=$destination/adisdb-icon.png
Terminal=false
Categories=Development;Database;
DESKTOP
    printf 'Installed: %s\n' "$destination"
    ;;
  *) printf 'This installer supports Linux and macOS. On Windows use run.cmd or the native installer.\n' >&2; exit 1 ;;
esac
