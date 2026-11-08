#!/bin/sh
set -eu

SERVICE=engine-companion-agent.service
INSTALL_DIR=/data/sc5000-custom/companion

[ "$(id -u)" = 0 ] || { echo "FEHLER: Deinstallation muss als root laufen" >&2; exit 1; }

systemctl disable --now "$SERVICE" >/dev/null 2>&1 || true
rm -f "/etc/systemd/system/$SERVICE"
rm -f "$INSTALL_DIR/$SERVICE" "$INSTALL_DIR/health.json" "$INSTALL_DIR/install-state"
systemctl daemon-reload

echo "Engine Companion Agent wurde gestoppt und aus dem Boot entfernt."
echo "Binary, Zugriffscode und Datenbanksicherungen wurden beibehalten."
