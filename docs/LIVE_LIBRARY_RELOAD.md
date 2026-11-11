# Live-Aktualisierung der Engine-Library

## Native Laufzeit-Aktualisierung ab Agent v15

Die Firmware stellt die benötigten Aktualisierungen bereits über ihre
QML-Controller bereit. Nach einer bestätigten Playlist-Transaktion erhöht der
Agent einen ausschließlich auf `127.0.0.1:8082` erreichbaren Revisionszähler.
Der vom Installer eingesetzte EngineX-Hook erkennt die Revision und ruft
`craylistController.reReadStructure()` sowie anschließend
`currentCollectionController.refreshDisplayedCollection()` auf. Dadurch werden
neue oder geänderte Playlists und deren Trackinhalt im laufenden lokalen
Collection-Modell sichtbar. Es wird weder ein Medium simuliert noch die SSD
ausgehängt oder Engine neu gestartet.

## Native Playlist-Befehle ab Agent v16

Playlist-Erstellung, Umbenennung und Löschung werden nicht mehr parallel in die
von Engine geöffnete SQLite-Datenbank geschrieben. Der Agent stellt den
EngineX-QML-Komponenten stattdessen eine ausschließlich lokal erreichbare
Befehlswarteschlange bereit. QML löst damit dieselben `ListsController`-Methoden
aus wie der Bearbeitungsmodus am Prime und bestätigt das Ergebnis an den
wartenden HTTP-Aufruf der App. Dadurch bleiben Engines Modell, Datenbank und
Bestätigungsdialoge die maßgebliche Quelle.

## Playlist-Tracing ab Agent v20

Der Diagnosemodus protokolliert QML-Modellereignisse sowie Commits an der
externen und internen Engine-Datenbank in
`/data/sc5000-custom/companion/playlist-trace.jsonl`. Beim Start werden außerdem
alle SQLite-Trigger und der Ausgangszustand von `Playlist` und
`PlaylistEntity` gespeichert. Während der Aufzeichnung werden Änderungen über
`PRAGMA data_version` erkannt und mit Vorher-/Nachher-Snapshots sowie dem
Zustand von Journal-, WAL- und SHM-Dateien festgehalten.

## Bestätigter Stand

Playlist- und Trackänderungen werden korrekt und dauerhaft in `Database2/m.db`
geschrieben. Die laufende Engine-Oberfläche hält Playlists und Mitgliedschaften
jedoch im Speicher und liest externe SQL-Änderungen nicht automatisch neu ein.

Der EngineX-Quellcode bestätigt, dass es aktuell keinen öffentlichen
Live-Reload-Pfad gibt:

- `rb-bridge --watch` ist ausdrücklich stillgelegt. EngineX importiert im
  `edisksd`-Mount-Hook, bevor Engine das Medium öffnet.
- `enginedb.Sync` erhöht
  `Information.lastRekordBoxLibraryImportReadCounter`, ruft aber weder Engine
  noch D-Bus oder Qt auf.
- Der Web-Trackimport (`analyzer.Library.Import`) schreibt Track, Analyse und
  Playlist-Mitgliedschaft atomar in SQLite, sendet anschließend aber ebenfalls
  keine Aktualisierungsnachricht an Engine.

Damit ist der Zähler kein Live-Signal. Er dokumentiert einen Import für den
nächsten Library-Lesevorgang.

## Ziel

Der Prime soll seine Library im laufenden Betrieb neu einlesen, ohne
`engine.service` oder das Gerät neu zu starten. Der Schreibpfad bleibt bei
Engines nativen Tabellen, Triggern und Verkettungen.

## Nächster Gerätetest

Auf dem Prime ausführen:

```sh
chmod 755 /tmp/diagnose-live-library.sh
/tmp/diagnose-live-library.sh
```

Das Ergebnis liegt in:

```text
/tmp/companion-live-library-diagnose.txt
```

Der Bericht erfasst nur:

- die offenen Engine-Datenbanken und Prozesse,
- D-Bus-Namen und die `edisksd`-Schnittstelle,
- begrenzte Firmware-Strings zu Library-, Playlist- und Import-Controllern,
- verfügbare QML-Einstiegspunkte,
- die jüngsten Engine- und `edisksd`-Logs.

Er verändert weder Datenbank noch Mounts und sendet keine Signale.

## Entscheidung nach der Diagnose

1. Gibt es einen vorhandenen D-Bus- oder QML-Aufruf zum Neuladen, verwendet der
   Agent genau diesen nach dem Commit.
2. Gibt es nur einen internen Qt-Controller, wird der bereits geladene
   EngineX-UI-Hook um einen eng begrenzten lokalen Reload-Aufruf ergänzt.
3. Eine logische Medien-Ab-/Anmeldung wird nur verwendet, wenn Engine dafür eine
   eigene Schnittstelle anbietet. Das physische Dateisystem wird nicht während
   des Betriebs ausgehängt.

Ein Prozessneustart bleibt ausgeschlossen.
