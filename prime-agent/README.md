# Engine Companion Prime Agent

Kleiner, separat gebauter ARMv7-Dienst für EngineX OS auf dem Prime 4+. Der Dienst lauscht auf Port 8081 und verwendet denselben Bearer-Zugriffscode wie `stream-sync` aus `/data/sc5000-custom/state/access-token`.

## Schnittstellen

- `GET /api/companion/v1/health`
- `GET /api/companion/v1/tracks/{id}/analysis?drive=Engine`
- `GET|HEAD /api/companion/v1/tracks/{id}/audio?drive=Engine`

Alle Routen benötigen `Authorization: Bearer <EngineX-Code>`. Es gibt keine frei wählbaren Dateipfade und keine schreibende Route. Trackpfade werden ausschließlich per numerischer Track-ID aus der bestätigten `m.db` gelesen, kanonisiert und auf `/media/Engine` begrenzt. SQLite wird mit `mode=ro` und `query_only` geöffnet; andere Schema-Versionen als 3.0.2 werden abgelehnt.

Der Analyse-Endpunkt liefert `beatData`, `quickCues`, `loops` und `overviewWaveformData` als Base64-kodierte JSON-Felder. Audio verwendet Go `ServeContent` und unterstützt dadurch HTTP-Range-Anfragen für Player-Seeking.

## Build

GitHub Actions führt die Tests auf Linux aus und baut anschließend ohne CGo für das echte Ziel:

```sh
CGO_ENABLED=0 GOOS=linux GOARCH=arm GOARM=7 go build -trimpath -ldflags="-s -w" -o engine-companion-agent .
```

Das Workflow-Artefakt `EngineCompanion-Prime-agent-armv7` enthält Binary, SHA-256-Datei und eine systemd-Servicevorlage. Installation erfolgt erst nach erfolgreichem CI-Build und einer manuellen Hash-Prüfung auf dem Prime.
