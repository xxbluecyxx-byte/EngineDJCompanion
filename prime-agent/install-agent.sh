#!/bin/sh
set -eu

SERVICE=engine-companion-agent.service
DATA_ROOT=/data/sc5000-custom
BIN_DIR="$DATA_ROOT/bundle/bin"
INSTALL_DIR="$DATA_ROOT/companion"
BOOT_SCRIPT="$DATA_ROOT/bundle/boot-activate.sh"
RUNTIME_UNIT="/run/systemd/system/$SERVICE"
TARGET="$BIN_DIR/engine-companion-agent"
PREVIOUS="$BIN_DIR/engine-companion-agent.previous"
PERSISTENT_UNIT="$INSTALL_DIR/$SERVICE"
TOKEN_FILE="$DATA_ROOT/state/access-token"
MARKER_BEGIN="# BEGIN ENGINE COMPANION AGENT"
MARKER_END="# END ENGINE COMPANION AGENT"

SOURCE_BINARY=${1:-./engine-companion-agent}
SOURCE_UNIT=${2:-./engine-companion-agent.service}
SOURCE_SHA=${3:-./engine-companion-agent.sha256}
SOURCE_UNINSTALL=${4:-./uninstall-agent.sh}

fail() {
    echo "FEHLER: $*" >&2
    exit 1
}

for command in systemctl sha256sum curl awk grep sh cmp; do
    command -v "$command" >/dev/null 2>&1 || fail "$command fehlt"
done

[ "$(id -u)" = 0 ] || fail "Installer muss als root laufen"
[ -f "$SOURCE_BINARY" ] || fail "Binary fehlt: $SOURCE_BINARY"
[ -f "$SOURCE_UNIT" ] || fail "Service-Datei fehlt: $SOURCE_UNIT"
[ -f "$SOURCE_UNINSTALL" ] || fail "Deinstallationsskript fehlt: $SOURCE_UNINSTALL"
[ -s "$TOKEN_FILE" ] || fail "EngineX-Zugriffscode fehlt: $TOKEN_FILE"
[ -f "$BOOT_SCRIPT" ] || fail "Persistenter Boot-Aktivator fehlt: $BOOT_SCRIPT"

if [ -f "$SOURCE_SHA" ]; then
    expected=$(awk 'NR == 1 { print $1 }' "$SOURCE_SHA")
    actual=$(sha256sum "$SOURCE_BINARY" | awk '{ print $1 }')
    [ -n "$expected" ] && [ "$expected" = "$actual" ] || fail "SHA-256-Prüfung fehlgeschlagen"
else
    actual=$(sha256sum "$SOURCE_BINARY" | awk '{ print $1 }')
    echo "WARNUNG: Keine SHA-Datei gefunden; berechneter Hash: $actual" >&2
fi

mkdir -p "$BIN_DIR" "$INSTALL_DIR" /run/systemd/system
stage="$TARGET.new.$$"
unit_stage="$PERSISTENT_UNIT.new.$$"
boot_before="$INSTALL_DIR/boot-activate.before.$$"
runtime_before="$INSTALL_DIR/runtime-unit.before.$$"
cp "$SOURCE_BINARY" "$stage"
chmod 755 "$stage"
cp "$SOURCE_UNIT" "$unit_stage"
chmod 644 "$unit_stage"
cp "$BOOT_SCRIPT" "$boot_before"

had_binary=0
had_unit=0
had_runtime_unit=0
was_active=0
[ -f "$TARGET" ] && had_binary=1
[ -f "$PERSISTENT_UNIT" ] && had_unit=1
if [ -f "$RUNTIME_UNIT" ]; then
    had_runtime_unit=1
    cp "$RUNTIME_UNIT" "$runtime_before"
fi
systemctl is-active "$SERVICE" >/dev/null 2>&1 && was_active=1

if [ "$had_binary" = 1 ]; then
    cp "$TARGET" "$PREVIOUS"
    chmod 755 "$PREVIOUS"
fi
if [ "$had_unit" = 1 ]; then
    cp "$PERSISTENT_UNIT" "$PERSISTENT_UNIT.previous"
fi

rollback() {
    echo "Installation fehlgeschlagen – vorheriger Zustand wird wiederhergestellt." >&2
    systemctl stop "$SERVICE" >/dev/null 2>&1 || true
    cp "$boot_before" "$BOOT_SCRIPT" 2>/dev/null || true
    chmod 755 "$BOOT_SCRIPT" 2>/dev/null || true
    if [ "$had_binary" = 1 ] && [ -f "$PREVIOUS" ]; then
        cp "$PREVIOUS" "$TARGET"
        chmod 755 "$TARGET"
    else
        rm -f "$TARGET"
    fi
    if [ "$had_unit" = 1 ] && [ -f "$PERSISTENT_UNIT.previous" ]; then
        cp "$PERSISTENT_UNIT.previous" "$PERSISTENT_UNIT"
    else
        rm -f "$PERSISTENT_UNIT"
    fi
    if [ "$had_runtime_unit" = 1 ] && [ -f "$runtime_before" ]; then
        cp "$runtime_before" "$RUNTIME_UNIT"
    else
        rm -f "$RUNTIME_UNIT"
    fi
    systemctl daemon-reload >/dev/null 2>&1 || true
    if [ "$had_binary" = 1 ] && [ "$was_active" = 1 ]; then
        systemctl restart "$SERVICE" >/dev/null 2>&1 || true
    fi
    rm -f "$stage" "$unit_stage" "$boot_before" "$runtime_before"
    exit 1
}

mv "$stage" "$TARGET"
mv "$unit_stage" "$PERSISTENT_UNIT"
cp "$SOURCE_UNINSTALL" "$INSTALL_DIR/uninstall-agent.sh" || rollback
chmod 755 "$INSTALL_DIR/uninstall-agent.sh"
if ! cmp -s "$0" "$INSTALL_DIR/install-agent.sh" 2>/dev/null; then
    cp "$0" "$INSTALL_DIR/install-agent.sh" || rollback
fi
chmod 755 "$INSTALL_DIR/install-agent.sh"

if [ ! -f "$INSTALL_DIR/boot-activate.original" ]; then
    cp "$boot_before" "$INSTALL_DIR/boot-activate.original"
    chmod 755 "$INSTALL_DIR/boot-activate.original"
fi

boot_stage="$BOOT_SCRIPT.new.$$"
awk -v begin="$MARKER_BEGIN" -v end="$MARKER_END" '
    $0 == begin { skip=1; next }
    $0 == end { skip=0; next }
    !skip { print }
' "$BOOT_SCRIPT" >"$boot_stage"
cat >>"$boot_stage" <<'HOOK'

# BEGIN ENGINE COMPANION AGENT
if [ -f "$dest/companion/engine-companion-agent.service" ] && \
   [ -x "$bin_dir/engine-companion-agent" ] && \
   [ -s "$dest/state/access-token" ]; then
    cp "$dest/companion/engine-companion-agent.service" \
        /run/systemd/system/engine-companion-agent.service
    systemctl daemon-reload || :
    systemctl start --no-block engine-companion-agent.service || :
fi
# END ENGINE COMPANION AGENT
HOOK
sh -n "$boot_stage" || rollback
chmod 755 "$boot_stage"
mv "$boot_stage" "$BOOT_SCRIPT"

cp "$PERSISTENT_UNIT" "$RUNTIME_UNIT" || rollback
chmod 644 "$RUNTIME_UNIT"
systemctl daemon-reload || rollback
systemctl restart "$SERVICE" || rollback

token=$(cat "$TOKEN_FILE")
healthy=0
attempt=1
while [ "$attempt" -le 15 ]; do
    if curl -fsS --max-time 2 -H "Authorization: Bearer $token" \
        http://127.0.0.1:8081/api/companion/v1/health >"$INSTALL_DIR/health.json.new" 2>/dev/null; then
        if grep -q '"service":"engine-companion-agent"' "$INSTALL_DIR/health.json.new"; then
            healthy=1
            break
        fi
    fi
    sleep 1
    attempt=$((attempt + 1))
done
[ "$healthy" = 1 ] || rollback

mv "$INSTALL_DIR/health.json.new" "$INSTALL_DIR/health.json"
{
    echo "sha256=$actual"
    echo "installed_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "service=$SERVICE"
    echo "boot_hook=$BOOT_SCRIPT"
} >"$INSTALL_DIR/install-state"

rm -f "$PERSISTENT_UNIT.previous" "$boot_before" "$runtime_before"
grep -qF "$MARKER_BEGIN" "$BOOT_SCRIPT" || fail "Boot-Hook wurde nicht gespeichert"
systemctl is-active "$SERVICE" >/dev/null || fail "Agent ist nach Healthcheck nicht aktiv"
echo "Engine Companion Agent wurde installiert, geprüft und in boot-activate.sh registriert."
cat "$INSTALL_DIR/health.json"
