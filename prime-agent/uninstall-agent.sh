#!/bin/sh
set -eu

SERVICE=engine-companion-agent.service
DATA_ROOT=/data/sc5000-custom
INSTALL_DIR="$DATA_ROOT/companion"
BOOT_SCRIPT="$DATA_ROOT/bundle/boot-activate.sh"
RUNTIME_UNIT="/run/systemd/system/$SERVICE"
MARKER_BEGIN="# BEGIN ENGINE COMPANION AGENT"
MARKER_END="# END ENGINE COMPANION AGENT"

[ "$(id -u)" = 0 ] || { echo "FEHLER: Deinstallation muss als root laufen" >&2; exit 1; }

systemctl stop "$SERVICE" >/dev/null 2>&1 || true
rm -f "$RUNTIME_UNIT"

if [ -f "$BOOT_SCRIPT" ]; then
    stage="$BOOT_SCRIPT.new.$$"
    awk -v begin="$MARKER_BEGIN" -v end="$MARKER_END" '
        $0 == begin { skip=1; next }
        $0 == end { skip=0; next }
        !skip { print }
    ' "$BOOT_SCRIPT" >"$stage"
    sh -n "$stage"
    chmod 755 "$stage"
    mv "$stage" "$BOOT_SCRIPT"
fi

rm -f "$INSTALL_DIR/$SERVICE" "$INSTALL_DIR/health.json" "$INSTALL_DIR/install-state"
systemctl daemon-reload

echo "Engine Companion Agent wurde gestoppt und aus boot-activate.sh entfernt."
echo "Binary, Zugriffscode und Datenbanksicherungen wurden beibehalten."
