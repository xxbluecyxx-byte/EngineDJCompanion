#!/bin/sh
set -eu

SERVICE=engine-companion-agent.service
DATA_ROOT=/data/sc5000-custom
INSTALL_DIR="$DATA_ROOT/companion"
BOOT_SCRIPT="$DATA_ROOT/bundle/boot-activate.sh"
RUNTIME_UNIT="/run/systemd/system/$SERVICE"
MARKER_BEGIN="# BEGIN ENGINE COMPANION AGENT"
MARKER_END="# END ENGINE COMPANION AGENT"
QML_FILES="$DATA_ROOT/ui/packs/en/Skins/Embedded/EmbeddedMain.qml
$DATA_ROOT/ui/packs/en/Skins/Embedded/Views/BrowseView/Components/Craylist.qml"
QML_MARKER_BEGIN="    // BEGIN ENGINE COMPANION LIVE PLAYLIST"
QML_MARKER_END="    // END ENGINE COMPANION LIVE PLAYLIST"

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

for QML_FILE in $QML_FILES; do
  if [ -f "$QML_FILE" ]; then
    qml_stage="$QML_FILE.new.$$"
    awk -v begin="$QML_MARKER_BEGIN" -v end="$QML_MARKER_END" '
        $0 == begin { skip=1; next }
        $0 == end { skip=0; next }
        !skip { print }
    ' "$QML_FILE" >"$qml_stage"
    chmod 644 "$qml_stage"
    mv "$qml_stage" "$QML_FILE"
  fi
done
systemctl restart engine.service >/dev/null 2>&1 || true

rm -f "$INSTALL_DIR/$SERVICE" "$INSTALL_DIR/health.json" "$INSTALL_DIR/install-state"
systemctl daemon-reload

echo "Engine Companion Agent wurde gestoppt und aus boot-activate.sh entfernt."
echo "Binary, Zugriffscode und Datenbanksicherungen wurden beibehalten."
