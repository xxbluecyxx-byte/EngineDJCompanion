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
QML_FILE="$DATA_ROOT/ui/packs/en/Skins/Embedded/Views/BrowseView/Components/Craylist.qml"
LEGACY_QML_FILE="$DATA_ROOT/ui/packs/en/Skins/Embedded/EmbeddedMain.qml"
QML_MARKER_BEGIN="    // BEGIN ENGINE COMPANION LIVE PLAYLIST"
QML_MARKER_END="    // END ENGINE COMPANION LIVE PLAYLIST"

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
[ -f "$QML_FILE" ] || fail "EngineX EmbeddedMain.qml fehlt: $QML_FILE"
[ -f "$LEGACY_QML_FILE" ] || fail "EngineX EmbeddedMain.qml fehlt: $LEGACY_QML_FILE"

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
boot_clean="$INSTALL_DIR/boot-clean.$$"
hook_stage="$INSTALL_DIR/boot-hook.$$"
boot_stage="$BOOT_SCRIPT.new.$$"
qml_before="$INSTALL_DIR/EmbeddedMain.before.$$"
qml_clean="$INSTALL_DIR/EmbeddedMain.clean.$$"
qml_hook="$INSTALL_DIR/EmbeddedMain.hook.$$"
qml_stage="$QML_FILE.new.$$"
legacy_qml_before="$INSTALL_DIR/EmbeddedMain.before.$$"
legacy_qml_clean="$INSTALL_DIR/EmbeddedMain.clean.$$"
cp "$SOURCE_BINARY" "$stage"
chmod 755 "$stage"
cp "$SOURCE_UNIT" "$unit_stage"
chmod 644 "$unit_stage"
cp "$BOOT_SCRIPT" "$boot_before"
cp "$QML_FILE" "$qml_before"
cp "$LEGACY_QML_FILE" "$legacy_qml_before"

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
    cp "$qml_before" "$QML_FILE" 2>/dev/null || true
    cp "$legacy_qml_before" "$LEGACY_QML_FILE" 2>/dev/null || true
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
    rm -f "$stage" "$unit_stage" "$boot_before" "$runtime_before" "$boot_clean" "$hook_stage" "$boot_stage" "$qml_before" "$qml_clean" "$qml_hook" "$qml_stage" "$legacy_qml_before" "$legacy_qml_clean"
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

awk -v begin="$MARKER_BEGIN" -v end="$MARKER_END" '
    $0 == begin { skip=1; next }
    $0 == end { skip=0; next }
    !skip { print }
' "$BOOT_SCRIPT" >"$boot_clean"
cat >"$hook_stage" <<'HOOK'

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
awk -v hook="$hook_stage" '
    $0 == "if command -v systemctl >/dev/null 2>&1; then" && !inserted {
        while ((getline line < hook) > 0) print line
        close(hook)
        inserted=1
    }
    { print }
    END { if (!inserted) exit 42 }
' "$boot_clean" >"$boot_stage" || rollback
sh -n "$boot_stage" || rollback
chmod 755 "$boot_stage"
mv "$boot_stage" "$BOOT_SCRIPT"

awk -v begin="$QML_MARKER_BEGIN" -v end="$QML_MARKER_END" '
    $0 == begin { skip=1; next }
    $0 == end { skip=0; next }
    !skip { print }
' "$QML_FILE" >"$qml_clean"
cat >"$qml_hook" <<'QMLHOOK'
    // BEGIN ENGINE COMPANION LIVE PLAYLIST
    property int companionLastCommand: 0
    property var companionPendingCommand: null

    function companionTrace(event, details) {
        const request = new XMLHttpRequest()
        request.open("POST", "http://127.0.0.1:8082/playlist-trace/event", true)
        request.setRequestHeader("Content-Type", "application/json")
        request.send(JSON.stringify({event: event, details: details || {}, selectedPath: controller.selectedPath,
                                     currentParentIndex: String(currentListModel.sourceParentIndex)}))
    }

    Component.onCompleted: companionTrace("craylist-component-ready", {})

    Connections {
        target: craylists.model
        function onRowsAboutToBeInserted(parent, first, last) { companionTrace("rows-about-to-insert", {parent:String(parent), first:first, last:last}) }
        function onRowsInserted(parent, first, last) { companionTrace("rows-inserted", {parent:String(parent), first:first, last:last}) }
        function onRowsAboutToBeRemoved(parent, first, last) { companionTrace("rows-about-to-remove", {parent:String(parent), first:first, last:last}) }
        function onRowsRemoved(parent, first, last) { companionTrace("rows-removed", {parent:String(parent), first:first, last:last}) }
        function onDataChanged(topLeft, bottomRight, roles) { companionTrace("list-data-changed", {topLeft:String(topLeft), bottomRight:String(bottomRight), roles:String(roles)}) }
        function onModelAboutToBeReset() { companionTrace("model-about-to-reset", {}) }
        function onModelReset() { companionTrace("model-reset", {}) }
    }

    Connections {
        target: craylists.controller
        function onListMoved() { companionTrace("controller-list-moved", {}) }
        function onListRemoved() { companionTrace("controller-list-removed", {}) }
        function onListRenamed() { companionTrace("controller-list-renamed", {}) }
        function onTracksAdded(index) { companionTrace("controller-tracks-added", {index:String(index)}) }
        function onSelectedPathChanged() { companionTrace("controller-selected-path", {path:String(controller.selectedPath)}) }
        function onListModelChanged() { companionTrace("controller-list-model-changed", {}) }
    }

    function companionRoles(index) {
        try { return Planck.librarianController.craylistController.listModel.getRoles(index) }
        catch (error) { return null }
    }

    function companionRole(roles, names) {
        if (!roles) return undefined
        for (var i = 0; i < names.length; ++i) {
            if (roles[names[i]] !== undefined) return roles[names[i]]
        }
        return undefined
    }

    function companionFindList(parent, uniqueId, title, depth) {
        if (depth > 20) return null
        const model = Planck.librarianController.craylistController.listModel
        const rows = model.rowCount(parent)
        for (var row = 0; row < rows; ++row) {
            const index = model.index(row, 0, parent)
            const roles = companionRoles(index)
            const id = companionRole(roles, ["uniqueListId", "listUniqueId", "uniqueId", "id"])
            const name = companionRole(roles, ["titleRole", "title", "name"])
            if ((uniqueId > 0 && Number(id) === Number(uniqueId)) || (title && String(name) === title)) return index
            const child = companionFindList(index, uniqueId, title, depth + 1)
            if (child) return child
        }
        return null
    }

    function companionCompleteCommand(ok, error) {
        const request = new XMLHttpRequest()
        request.open("POST", "http://127.0.0.1:8082/native-command/complete", true)
        request.setRequestHeader("Content-Type", "application/json")
        request.send(JSON.stringify({id: companionPendingCommand.id, ok: ok, error: error || ""}))
        companionLastCommand = companionPendingCommand.id
        companionPendingCommand = null
    }

    function companionExecuteCommand(command) {
        const lists = Planck.librarianController.craylistController
        const rootIndex = lists.rootModelIndex
        var index
        if (command.action === "create") {
			index = command.parentId > 0 ? companionFindList(rootIndex, command.parentId, "", 0) : currentListModel.sourceParentIndex
            if (!index) throw new Error("parent playlist was not found in Engine")
            lists.createList(index, command.title)
        } else {
            index = companionFindList(rootIndex, command.playlistId, "", 0)
            if (!index) throw new Error("playlist was not found in Engine")
            if (command.action === "delete") lists.removeList(index)
            else if (command.action === "rename") lists.renameList(index, command.title)
            else if (command.action === "replaceTracks") throw new Error("native track replacement is not enabled yet")
            else throw new Error("unsupported native playlist action")
        }
    }

    function companionPollNativeCommand() {
        if (!craylists.visible || !craylists.enabled || companionPendingCommand) return
        const request = new XMLHttpRequest()
        request.onreadystatechange = function() {
            if (request.readyState !== XMLHttpRequest.DONE || request.status !== 200) return
            try {
                companionPendingCommand = JSON.parse(request.responseText)
                companionTrace("native-command-received", {id:companionPendingCommand.id, action:companionPendingCommand.action,
                                                            title:companionPendingCommand.title, visible:craylists.visible,
                                                            rootIndex:String(rootModelIndex), parentIndex:String(currentListModel.sourceParentIndex)})
                companionExecuteCommand(companionPendingCommand)
                if (companionPendingCommand.action === "delete") companionCommandCheck.restart()
                else companionCompleteCommand(true, "")
            } catch (error) {
                if (companionPendingCommand) companionCompleteCommand(false, String(error))
            }
        }
        request.open("GET", "http://127.0.0.1:8082/native-command?after=" + companionLastCommand, true)
        request.send()
    }

    Timer {
        interval: 750
        running: true
        repeat: true
        onTriggered: craylists.companionPollNativeCommand()
    }

    Timer {
        id: companionCommandCheck
        interval: 500
        repeat: false
        onTriggered: {
            if (!companionPendingCommand) return
            const command = companionPendingCommand
            const rootIndex = Planck.librarianController.craylistController.rootModelIndex
            const byId = command.playlistId > 0 ? companionFindList(rootIndex, command.playlistId, "", 0) : null
            const byTitle = command.title ? companionFindList(rootIndex, 0, command.title, 0) : null
            if (command.action === "create" && byTitle) companionCompleteCommand(true, "")
            else if (command.action === "delete" && !byId) companionCompleteCommand(true, "")
            else if (command.action === "rename" && byTitle) companionCompleteCommand(true, "")
            else if (command.action === "replaceTracks") companionCompleteCommand(true, "")
            else restart()
        }
    }
    // END ENGINE COMPANION LIVE PLAYLIST
QMLHOOK
awk -v hook="$qml_hook" '
    /objectName: "Craylist"/ && !inserted {
        while ((getline line < hook) > 0) print line
        close(hook)
        inserted=1
    }
    { print }
    END { if (!inserted) exit 42 }
' "$qml_clean" >"$qml_stage" || rollback
chmod 644 "$qml_stage"
mv "$qml_stage" "$QML_FILE"

awk -v begin="$QML_MARKER_BEGIN" -v end="$QML_MARKER_END" '
    $0 == begin { skip=1; next }
    $0 == end { skip=0; next }
    !skip { print }
' "$LEGACY_QML_FILE" >"$legacy_qml_clean"
chmod 644 "$legacy_qml_clean"
mv "$legacy_qml_clean" "$LEGACY_QML_FILE"

cp "$PERSISTENT_UNIT" "$RUNTIME_UNIT" || rollback
chmod 644 "$RUNTIME_UNIT"
systemctl daemon-reload || rollback
systemctl restart "$SERVICE" || rollback
systemctl restart engine.service || rollback

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

rm -f "$PERSISTENT_UNIT.previous" "$boot_before" "$runtime_before" "$boot_clean" "$hook_stage" "$qml_before" "$qml_clean" "$qml_hook" "$legacy_qml_before"
grep -qF "$MARKER_BEGIN" "$BOOT_SCRIPT" || fail "Boot-Hook wurde nicht gespeichert"
grep -qF "$QML_MARKER_BEGIN" "$QML_FILE" || fail "Live-Playlist-Hook wurde nicht gespeichert"
systemctl is-active "$SERVICE" >/dev/null || fail "Agent ist nach Healthcheck nicht aktiv"
echo "Engine Companion Agent wurde installiert, geprüft und in boot-activate.sh registriert."
cat "$INSTALL_DIR/health.json"
