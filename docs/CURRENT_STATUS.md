# Aktueller Projektstand — 9. November 2026


## Playlist-Editor 0.18 / Agent v9

Der Agent schreibt jetzt Playlists auf Basis des bestätigten Engine-Schemas 3.0.2. Er verwendet die verketteten `nextListId`- und `nextEntityId`-Felder, speichert Track-Zuordnungen mit `originDatabaseUuid` und `originTrackId`, setzt `membershipReference` auf `0` und führt vor jeder Änderung das bestehende geprüfte Backup aus. Die App kann Playlists erstellen, umbenennen und löschen sowie Tracks hinzufügen, entfernen und sortieren. Nach einer erfolgreichen Änderung wird die Live-Library neu eingelesen.
Dieses Dokument beschreibt den praktisch bestätigten Stand. Ältere Abschnitte in `VALIDATION.md` bleiben als Entwicklungshistorie erhalten.

## Ausgerollte Versionen

| Komponente | Stand | Praktisch bestätigt |
|---|---:|---|
| Android-App | `0.16.2-grid-state` · Version Code 31 | Installation als Update, WLAN-Library, Player und Editor |
| Prime-Agent | API-Version 6 · ARMv7 | Healthcheck, Audio, Analyse und Schreibendpunkte |
| Prime-Agent-Binary | SHA-256 `ed4f3f4fdbbfd4a08081f7c4675b26330f56a9cffa4c1e4cc8b39a4381a733cb` | Installation vom 9. November 2026 |
| Engine-Datenbank | Schema 3.0.2 | echte Prime-4+-Library |

Referenzgeräte sind ein Denon DJ Prime 4+ und ein ASUS ROG Phone 9 Pro.

## Bestätigte Funktionen

### Library und Player

- Authentifizierte WLAN-Momentaufnahme mit 7401 Tracks; offline in der App verfügbar.
- Playlist-Baum, Suche, Sortierung, native Reihenfolge und Camelot-Tonarten.
- HTTP-Range-Audiostream direkt vom Prime.
- Übersichtswaveform und mitlaufende Detailwaveform mit Drei-Band-Darstellung.
- Antippen beziehungsweise Ziehen in der Übersicht und Jog-Geste in der Detailwaveform.
- Beatgrid-, Hot-Cue- und Loop-Markierungen in der Waveform.

### Bearbeitung auf dem Prime

- Trackfelder und Sternebewertungen einzeln oder über eine persistente Warteschlange.
- Hot Cues setzen, quantisieren, löschen und auf den Prime übertragen.
- Beatgrid-BPM und Offset bearbeiten und auf den Prime übertragen.
- Änderungen sind während des laufenden Engine-OS-Betriebs am Prime sichtbar.
- Cue- und Beatgrid-Schreibvorgänge bewahren unbekannte Felder der bestätigten BLOB-Strukturen.

Gespeicherte Loops werden gelesen und im Player abgespielt. Ein Loop-Editor und Loop-Synchronisierung sind bewusst zurückgestellt.

### Schreibsicherheit

- Der Agent akzeptiert nur feste Endpunkte, numerische Track-IDs und bekannte Felder.
- Vor der ersten Änderung einer Agent-Sitzung wird `m.db` mit SQLite `VACUUM INTO` gesichert.
- Die Sicherung muss `integrity_check` bestehen.
- Eine Sitzung verwendet dieselbe Ausgangssicherung; maximal fünf Sicherungen bleiben erhalten.
- Sicherungen liegen unter `Engine Library/Database2/CompanionBackups`.
- Der Android-Editor hält lokale Entwürfe und eine Metadaten-Warteschlange persistent.

## Persistenter Prime-Agent

Der Prime verwendet ein schreibgeschütztes Root-Dateisystem:

- `/` und damit `/etc` sind read-only.
- `/data` ist persistent und beschreibbar.
- `/run` ist ein flüchtiges tmpfs.

Darum kann die Unit nicht regulär in `/etc/systemd/system` aktiviert werden. Der Installer verwendet den vorhandenen EngineX-Startweg:

1. Binary und Unit werden unter `/data/sc5000-custom` gespeichert.
2. Ein klar markierter Block wird vor dem abschließenden Neustart von `engine.service` in `bundle/boot-activate.sh` eingefügt.
3. Beim Boot kopiert dieser Block die Unit nach `/run/systemd/system`.
4. Systemd lädt die Unit und startet den Agenten mit `--no-block`.

`systemctl` zeigt die dynamische Unit deshalb als `disabled; preset: disabled`. Das ist auf diesem Gerät der erwartete Zustand und bedeutet nicht, dass die Persistenz fehlt.

Der Start ist asynchron. Unmittelbar nach dem Hochfahren kann die Unit kurz `inactive (dead)` erscheinen. Nach Abschluss der EngineX-Boot-Abhängigkeiten startet sie selbstständig; praktisch wurde eine Verzögerung von ungefähr ein bis zwei Minuten beobachtet.

### Installation und Update

Das GitHub-Actions-Artefakt `EngineCompanion-Prime-agent-armv7` enthält fünf Dateien. Diese gemeinsam nach `/tmp/engine-companion-agent-install/` kopieren:

```sh
cd /tmp/engine-companion-agent-install
chmod 755 install-agent.sh uninstall-agent.sh
./install-agent.sh
```

Der Installer prüft SHA-256, tauscht das Binary atomar, sichert den vorherigen Zustand, aktualisiert den Boot-Hook und führt einen authentifizierten Healthcheck aus. Bei einem Fehler werden Boot-Skript, Unit und Binary zurückgesetzt.

### Prüfung nach einem Neustart

Nach vollständigem Hochfahren bis zu zwei Minuten warten:

```sh
systemctl status engine-companion-agent --no-pager -l

TOKEN=$(cat /data/sc5000-custom/state/access-token)
curl -sS \
  -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:8081/api/companion/v1/health
```

Bestätigte Antwort:

```json
{"metadataWrite":true,"readOnly":false,"service":"engine-companion-agent","version":6}
```

Installationszustand und letzter erfolgreicher Healthcheck:

```sh
cat /data/sc5000-custom/companion/install-state
cat /data/sc5000-custom/companion/health.json
```

### Wiederherstellung und Entfernung

Das vorherige Binary bleibt als `bundle/bin/engine-companion-agent.previous` erhalten. Der Boot-Hook wird vor der ersten Änderung zusätzlich gesichert.

Die Registrierung kann entfernt werden, ohne Zugriffscode, Binary oder Datenbanksicherungen zu löschen:

```sh
/data/sc5000-custom/companion/uninstall-agent.sh
```

## Nächste technische Schritte

1. Langzeittest von Metadaten-, Cue- und Beatgrid-Änderungen über mehrere Engine-Neustarts.
2. Cue-Namen, Cue-Farben und direkt verschiebbare Cue-Marker.
3. Sofortige Aktualisierung geänderter Ratings und Metadaten in allen Tracklisten.
4. Mehrfachauswahl für Tags und Bewertungen.
5. Später optional Loop-Editor, Playlist-Schreibfunktionen und Konfliktauflösung.

