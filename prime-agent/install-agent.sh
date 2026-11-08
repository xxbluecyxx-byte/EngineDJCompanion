#!/bin/sh
set -eu

SERVICE=engine-companion-agent.service
DATA_ROOT=/data/sc5000-custom
BIN_DIR="$DATA_ROOT/bundle/bin"
STATE_DIR="$DATA_ROOT/state"
INSTALL_DIR="$DATA_ROOT/companion"
SYSTEMD_DIR=/etc/systemd/system
TARGET="$BIN_DIR/engine-companion-agent"
PREVIOUS="$BIN_DIR/engine-companion-agent.previous"
PERSISTENT_UNIT="$INSTALL_DIR/$SERVICE"
SYSTEM_UNIT="$SYSTEMD_DIR/$SERVICE"
TOKEN_FILE="$STATE_DIR/access-token"

SOURCE_BINARY=${1:-./engine-companion-agent}
SOURCE_UNIT=${2:-./engine-companion-agent.service}
SOURCE_SHA=${3:-./engine-companion-agent.sha256}
SOURCE_UNINSTALL=${4:-./uninstall-agent.sh}

fail() {
    echo "FEHLER: $*" >&2
    exit 1
}

for command in systemctl sha256sum curl awk grep; do
    command -v "$command" >/dev/null 2>&1 || fail "$command fehlt"
done

[ "$(id -u)" = 0 ] || fail "Installer muss als root laufen"
[ -f "$SOURCE_BINARY" ] || fail "Binary fehlt: $SOURCE_BINARY"
[ -f "$SOURCE_UNIT" ] || fail "Service-Datei fehlt: $SOURCE_UNIT"
[ -f "$SOURCE_UNINSTALL" ] || fail "Deinstallationsskript fehlt: $SOURCE_UNINSTALL"
[ -s "$TOKEN_FILE" ] || fail "EngineX-Zugriffscode fehlt: $TOKEN_FILE"
[ -d "$SYSTEMD_DIR" ] || fail "systemd-Verzeichnis fehlt: $SYSTEMD_DIR"

if [ -f "$SOURCE_SHA" ]; then
    expected=$(awk 'NR == 1 { print $1 }' "$SOURCE_SHA")
    actual=$(sha256sum "$SOURCE_BINARY" | awk '{ print $1 }')
    [ -n "$expected" ] && [ "$expected" = "$actual" ] || fail "SHA-256-Prüfung fehlgeschlagen"
else
    actual=$(sha256sum "$SOURCE_BINARY" | awk '{ print $1 }')
    echo "WARNUNG: Keine SHA-Datei gefunden; berechneter Hash: $actual" >&2
fi

mkdir -p "$BIN_DIR" "$INSTALL_DIR"
stage="$TARGET.new.$$"
unit_stage="$PERSISTENT_UNIT.new.$$"
cp "$SOURCE_BINARY" "$stage"
chmod 755 "$stage"
cp "$SOURCE_UNIT" "$unit_stage"
chmod 644 "$unit_stage"

had_binary=0
had_unit=0
was_active=0
[ -f "$TARGET" ] && had_binary=1
[ -f "$PERSISTENT_UNIT" ] && had_unit=1
systemctl is-active "$SERVICE" >/dev/null 2>&1 && was_active=1

if [ "$had_binary" = 1 ]; then
    cp "$TARGET" "$PREVIOUS"
    chmod 755 "$PREVIOUS"
fi
if [ "$had_unit" = 1 ]; then
    cp "$PERSISTENT_UNIT" "$PERSISTENT_UNIT.previous"
fi

rollback() {
    echo "Healthcheck fehlgeschlagen – vorherige Installation wird wiederhergestellt." >&2
    systemctl stop "$SERVICE" >/dev/null 2>&1 || true
    if [ "$had_binary" = 1 ] && [ -f "$PREVIOUS" ]; then
        cp "$PREVIOUS" "$TARGET"
        chmod 755 "$TARGET"
    else
        rm -f "$TARGET"
    fi
    if [ "$had_unit" = 1 ] && [ -f "$PERSISTENT_UNIT.previous" ]; then
        cp "$PERSISTENT_UNIT.previous" "$PERSISTENT_UNIT"
        cp "$PERSISTENT_UNIT" "$SYSTEM_UNIT"
    else
        rm -f "$PERSISTENT_UNIT" "$SYSTEM_UNIT"
    fi
    systemctl daemon-reload >/dev/null 2>&1 || true
    if [ "$had_binary" = 1 ] && [ "$was_active" = 1 ]; then
        systemctl restart "$SERVICE" >/dev/null 2>&1 || true
    fi
    rm -f "$stage" "$unit_stage"
    exit 1
}

mv "$stage" "$TARGET"
mv "$unit_stage" "$PERSISTENT_UNIT"
cp "$PERSISTENT_UNIT" "$SYSTEM_UNIT" || rollback
chmod 644 "$SYSTEM_UNIT"
cp "$SOURCE_UNINSTALL" "$INSTALL_DIR/uninstall-agent.sh"
chmod 755 "$INSTALL_DIR/uninstall-agent.sh"
cp "$0" "$INSTALL_DIR/install-agent.sh"
chmod 755 "$INSTALL_DIR/install-agent.sh"

systemctl daemon-reload
systemctl enable "$SERVICE" >/dev/null || rollback
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
} >"$INSTALL_DIR/install-state"

rm -f "$PERSISTENT_UNIT.previous"
systemctl is-enabled "$SERVICE" >/dev/null || fail "Service läuft, ist aber nicht für den Boot aktiviert"
echo "Engine Companion Agent wurde installiert, geprüft und für den Boot aktiviert."
cat "$INSTALL_DIR/health.json"
