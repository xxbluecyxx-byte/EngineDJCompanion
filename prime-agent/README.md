# Engine Companion Prime Agent

Kleiner, separat gebauter ARMv7-Dienst für EngineX OS auf dem Prime 4+. Der Dienst lauscht auf Port 8081 und verwendet denselben Bearer-Zugriffscode wie `stream-sync` aus `/data/sc5000-custom/state/access-token`.

## Schnittstellen

- `GET /api/companion/v1/health`
- `GET /api/companion/v1/tracks/{id}/analysis?drive=Engine`
- `GET|HEAD /api/companion/v1/tracks/{id}/audio?drive=Engine`
- `GET|POST /api/companion/v1/tracks/{id}/metadata?drive=Engine`
- `POST /api/companion/v1/tracks/{id}/cues?drive=Engine`
- `POST /api/companion/v1/tracks/{id}/beatgrid?drive=Engine`

Alle Routen benötigen `Authorization: Bearer <EngineX-Code>`. Trackpfade werden ausschließlich per numerischer Track-ID aus der bestätigten `m.db` gelesen. Die Metadatenroute darf nur feste Track-Spalten ändern und erstellt vor dem ersten Schreibvorgang jeder Agent-Sitzung mit SQLite `VACUUM INTO` eine konsistente, per `integrity_check` geprüfte Sicherung unter `Database2/CompanionBackups`. Eine Sitzung verwendet dieselbe Ausgangssicherung weiter; aufbewahrt werden höchstens fünf Sicherungen. Alle normalen Lesezugriffe verwenden weiterhin `mode=ro` und `query_only`; andere Schema-Versionen als 3.0.2 werden abgelehnt.

Der Analyse-Endpunkt liefert `beatData`, `quickCues`, `loops` und `overviewWaveformData` als Base64-kodierte JSON-Felder. Audio verwendet Go `ServeContent` und unterstützt dadurch HTTP-Range-Anfragen für Player-Seeking.
Die Cue-Route ersetzt ausschließlich die acht Hot-Cue-Slots im vorhandenen `quickCues`-BLOB. Main Cue und unbekannte Formatfelder bleiben erhalten; anschließend wird Engines Performance-Änderungsflag gesetzt.
Die Beatgrid-Route bewahrt Standardmarker, Beatnummern und unbekannte Felder. Sie schreibt angepasste Marker mit dem gewünschten BPM und Offset und setzt ebenfalls Engines Performance-Änderungsflag.

## Build

GitHub Actions führt die Tests auf Linux aus und baut anschließend ohne CGo für das echte Ziel:

```sh
CGO_ENABLED=0 GOOS=linux GOARCH=arm GOARM=7 go build -trimpath -ldflags="-s -w" -o engine-companion-agent .
```

Das Workflow-Artefakt `EngineCompanion-Prime-agent-armv7` enthält Binary, SHA-256-Datei, die systemd-Unit sowie Installations- und Deinstallationsskript. Der Installer prüft den Hash, ersetzt das Binary atomar, aktiviert den Dienst dauerhaft für `multi-user.target` und prüft anschließend den authentifizierten Health-Endpunkt. Scheitert Start oder Healthcheck, wird die vorige Installation automatisch wiederhergestellt.

Alle fünf Dateien aus dem Artefakt zusammen nach `/tmp/engine-companion-agent-install/` auf den Prime kopieren und dort ausführen:

```sh
cd /tmp/engine-companion-agent-install
chmod 755 install-agent.sh uninstall-agent.sh
./install-agent.sh
systemctl status engine-companion-agent --no-pager -l
systemctl is-enabled engine-companion-agent
```

Dasselbe Kommando aktualisiert eine bestehende Installation. Installer und Deinstaller werden dabei dauerhaft unter `/data/sc5000-custom/companion/` abgelegt. Das zuletzt ersetzte Binary bleibt als `/data/sc5000-custom/bundle/bin/engine-companion-agent.previous` erhalten. Installationsstatus und letzter erfolgreicher Healthcheck liegen ebenfalls im Companion-Verzeichnis.

Der Boot-Start kann ohne Löschen von Binary, Zugriffscode oder Datenbanksicherungen wieder entfernt werden:

```sh
/data/sc5000-custom/companion/uninstall-agent.sh
```
