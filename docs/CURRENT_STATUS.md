# Aktueller Projektstand — 12. November 2026

Dieses Dokument ist die maßgebliche Zusammenfassung des praktisch bestätigten Projektstands. `VALIDATION.md` enthält die ältere Entwicklungshistorie. Aussagen zu Live-Playlist-Änderungen in älteren Dokumenten gelten nur, soweit sie hier bestätigt werden.

## Ziel und Referenzsystem

Engine DJ Companion ist eine native Android-App für die Library eines Denon DJ Prime 4+. Ein separater ARMv7-Agent läuft auf dem Gerät und stellt ausschließlich authentifizierte, eng begrenzte Endpunkte bereit. Die Referenzumgebung besteht aus:

- Denon DJ Prime 4+ mit EngineX OS und Engine-Library-Schema `3.0.2`;
- interner SSD-Library unter `/media/Engine/Engine Library/Database2`;
- ASUS ROG Phone 9 Pro mit der Companion-App;
- EngineX-Zugriffscode als Bearer-Token;
- schreibgeschütztem Root-Dateisystem, persistentem `/data` und flüchtigem `/run`.

Die Arbeit an Playlists hat Priorität. Trackupload und ein späteres DVS-Experiment folgen erst nach einem stabilen, nativen Playlistpfad.

## Ausgerollter Stand

| Komponente | Stand | Status |
|---|---:|---|
| Prime-Agent | API-Version `23` | installiert und Healthcheck bestätigt |
| GitHub `main` | `0e5544d` | erfolgreicher Actions-Build |
| Agent-Artefakt | `EngineCompanion-Prime-agent-armv7` | SHA-256 des ZIP: `3f95ca3bc2042b7d9f5c973b51a5c1912166b69000fa9740017b11ae5cb7ea92` |
| Agent-Binary v23 | ARMv7, 11.272.352 Byte | SHA-256: `a61c06f4663623c8d185ce5f33479b1181f6026a8a5ad85e74451b89bd81c07a` |
| Engine-Library | Schema `3.0.2` | 7401 Tracks im Referenz-Snapshot |

Der bestätigte v23-Healthcheck meldet `metadataWrite`, `playlistWrite`, `playlistLiveNotify`, `playlistNativeController` und `playlistTrace` als aktiv.

## Bestätigte Android- und Agent-Funktionen

### Library, Player und Analyse

- Authentifizierter WLAN-Snapshot mit Offline-Kopie auf dem Telefon.
- Playlist-Baum, Suche, Sortierung, Herkunftsauflösung und native Reihenfolge.
- HTTP-Range-Audiostream direkt vom Prime.
- Übersichtswaveform und mitlaufende Detailwaveform.
- Lesen und Anzeigen von Hot Cues, gespeicherten Loops und Beatgrid.
- Camelot-Tonarten, BPM, Rating, Trackfelder und technische Analyseberichte.

### Schreiben von Trackdaten

- Titel, Interpret, Album, Genre, Kommentar, Label, Remixer und Bewertung.
- Einzeländerungen und mehrere vorgemerkte Änderungen in einer Warteschlange.
- Hot Cues setzen, quantisieren und löschen.
- Beatgrid-BPM und Offset ändern.
- Änderungen an Metadaten, Rating, Cues und Beatgrid wurden auf dem laufenden Prime praktisch bestätigt.
- Unbekannte Felder der Cue- und Beatgrid-BLOBs bleiben erhalten.

Vor einer Schreibsitzung erzeugt der Agent eine konsistente Sicherung mit `VACUUM INTO`, prüft sie mit `integrity_check` und bewahrt höchstens fünf Sicherungen unter `Database2/CompanionBackups` auf.

## Persistenter Agent auf dem Prime

Eine regulär aktivierte Unit unter `/etc/systemd/system` ist wegen des schreibgeschützten Root-Dateisystems nicht möglich. Der Installer verwendet den vorhandenen EngineX-Bootpfad:

1. Binary, Unit und Installationszustand liegen persistent unter `/data/sc5000-custom`.
2. Ein markierter Block in `bundle/boot-activate.sh` kopiert die Unit beim Boot nach `/run/systemd/system`.
3. Nach `daemon-reload` wird der Agent asynchron gestartet.
4. Der Installer prüft Hash, atomaren Austausch und authentifizierten Healthcheck und stellt bei einem Fehler den vorherigen Zustand wieder her.

Die Runtime-Unit erscheint deshalb als `disabled`; das ist beabsichtigt. Nach einem Kaltstart kann der Agent wegen der EngineX-Bootreihenfolge erst nach ein bis zwei Minuten aktiv werden.

## Playlist-Schema und bestätigte Datenbankregeln

Die relevanten Tabellen sind `Playlist` und `PlaylistEntity`.

- `Playlist` bildet Ordner und Playlists über `parentListId` und eine verkettete Geschwisterreihenfolge über `nextListId` ab.
- `PlaylistEntity` bildet Trackmitgliedschaften und deren Reihenfolge über `nextEntityId` ab.
- Die Trackreferenz besteht aus `databaseUuid + trackId` und wird gegen `Track.originDatabaseUuid + Track.originTrackId` aufgelöst.
- `membershipReference` ist bei den untersuchten Einträgen `0`.
- Native SQLite-Trigger pflegen Verkettungen, Persistenzfelder und Löschfolgen.

## Native Playlist-Untersuchung

### Manuell bestätigter Referenzlauf

Agent v20 protokollierte einen vollständigen manuellen Ablauf im sichtbaren Playlist-Editor des Prime: Playlist erstellen, einen Track hinzufügen und die Playlist wieder löschen.

| Schritt | QML-Ereignis | Datenbankwirkung |
|---|---|---|
| Erstellen | `rows-inserted` | neue `Playlist`-Zeile, vorheriger Listenknoten zeigt per `nextListId` darauf |
| Track hinzufügen | `controller-tracks-added` | neue `PlaylistEntity` mit Herkunfts-UUID und Track-ID |
| Löschen | `controller-list-removed` | Playlist und Entity entfernt, vorherige Kette wieder auf `0` gesetzt |

Der Lauf bestätigte, dass der native Prime-Editor direkt in die externe SSD-DB schreibt und seine QML-Modelle gleichzeitig aktualisiert. Beobachtete Trigger waren unter anderem `trigger_before_insert_List`, `trigger_after_insert_List`, `trigger_before_delete_PlaylistEntity` und `trigger_after_delete_List`.

### Erfolgreicher nativer Create-Test

Agent v21 leitete einen Create-Befehl an die sichtbare `Craylist`-Instanz weiter. Die Playlist `Companion v21 Live Test` erschien ohne Neustart sofort am Prime und wurde dauerhaft als Playlist-ID `115` gespeichert. Entscheidend war, dass nur die sichtbare und aktivierte `Craylist` Befehle abholt; Engine hält mehrere Instanzen dieser Komponente vor.

### Delete-Versuche v22 und v23

v22 brach sicher mit HTTP 409 ab, weil die von QML gemeldeten Modellrollen weder ID noch Titel zuverlässig lieferten. Es wurde nichts gelöscht.

v23 berechnet deshalb den sichtbaren Zeilenindex aus der bestätigten `nextListId`-Kette und übergibt `targetRow` an QML. Der Test am 11. November erhielt dennoch keine Bestätigung der Engine-Oberfläche und endete mit HTTP 409: `Engine UI did not acknowledge the command`. Danach wurde ein unerwarteter Neustart des Prime beobachtet. Ursache und zeitlicher Zusammenhang sind noch nicht geklärt.

## Sicherheitsentscheidung für weitere Playlist-Tests

Bis die Neustartursache geklärt ist, gelten folgende Regeln:

1. Vor jedem möglichen Engine- oder Geräteneustart wird der Bediener ausdrücklich informiert.
2. Vor weiteren Mutationen werden Boot-, Systemd-, Engine-, QML- und Companion-Logs nur lesend gesichert.
3. Kein `systemctl restart engine.service`, kein physisches Unmount der SSD und keine Medien-Simulation als regulärer Synchronisationspfad.
4. Native QML-Controller bleiben der bevorzugte Weg, weil derselbe Pfad im manuellen Prime-Editor nachweislich live funktioniert.
5. Ein HTTP 409 gilt als fehlgeschlagener Vorgang; es wird nicht automatisch auf einen DB-Direktzugriff oder Neustart ausgewichen.
6. Löschen wird erst weiter getestet, wenn Zielindex, sichtbare Modellinstanz, Bestätigungsdialog, Timeout und Watchdog-Verhalten vollständig protokolliert werden.

## Was bei Playlists noch fehlt

- Zuverlässiges natives Umbenennen und Löschen ohne Neustart.
- Tracks nativ hinzufügen, entfernen und neu sortieren.
- Sofortige Anzeige neuer Tracks in einer bereits geöffneten Playlist.
- Robuste Bestätigung zwischen Android-App, Agent und sichtbarer QML-Instanz.
- Konfliktbehandlung, wenn die Ansicht gewechselt oder ein Dialog nicht bestätigt wird.
- Behebung eines bekannten Randfalls: `targetRow == 0` darf im JSON nicht durch `omitempty` verschwinden.

## EngineX OS 5.3.0.4

Das neue EngineX-Release basiert auf Engine OS 5.3.0+ und bietet für Geräte der zweiten Generation ein aktualisiertes `/data`-Bundle. Es dokumentiert keinen öffentlichen Live-Reload lokaler Playlists. Ein Update wird deshalb nicht als Playlist-Lösung vorausgesetzt und erst nach Prüfung der Auswirkungen auf `boot-activate.sh`, QML-Hooks und Agent-Persistenz vorgenommen.

Für ein späteres DVS-Experiment sind zwei neue EngineX-Bestandteile interessant: das SC-LIVE-4-AUX-Routing und der optionale MIDI-Controller-Mapper mit einem CDJ-400-Preset für Deck 3/4. Das Prime-4+-System stellt außerdem ein ALSA-Capture-Gerät `pcmC0D0c` bereit. Diese Spur bleibt dokumentiert, wird aber erst nach dem Playlist-Editor bearbeitet.

## Nächster Arbeitsschritt

1. Logs des unerwarteten Neustarts sichern und zwischen Engine-Crash, Watchdog und vollständigem Systemneustart unterscheiden.
2. v23-Command-, QML- und Systemereignisse zeitlich zusammenführen.
3. Den Delete-Pfad mit rein lesender Instrumentierung korrigieren.
4. Erst danach einen angekündigten, beaufsichtigten Test mit der vorhandenen Playlist-ID `115` durchführen.
5. Nach stabilem CRUD den Trackmitgliedschafts- und Sortierpfad fertigstellen.
6. Anschließend Playlist-Editor für den Alltag abschließen und Trackupload vom Telefon entwickeln.

## Diagnosepfade

- Playlist-Trace: `/data/sc5000-custom/companion/playlist-trace.jsonl`
- Installationszustand: `/data/sc5000-custom/companion/install-state`
- letzter Healthcheck: `/data/sc5000-custom/companion/health.json`
- EngineX-Bootlog: `/data/sc5000-custom/boot-activate.log`
- Agent-Binary: `/data/sc5000-custom/bundle/bin/engine-companion-agent`
- QML-Hook: `/data/sc5000-custom/ui/packs/en/Skins/Embedded/Views/BrowseView/Components/Craylist.qml`

Zugriffstoken, SSH-Schlüssel, Passphrasen und private Sicherungsinhalte gehören nicht in Logs, Issues oder Repository-Dateien.
