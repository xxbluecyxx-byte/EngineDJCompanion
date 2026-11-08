# Entwicklungsplan

## 1. Stabile Entwicklungsbasis

- Fester Debug-Signaturschlüssel für aktualisierbare APKs.
- GitHub CI: Unit-Tests, Lint, APK und Fehlerberichte.
- Reale USB- und EngineX-WLAN-Library mit Offline-Momentaufnahme.
- Bestätigte Playlist-Herkunftszuordnung sowie Hot-Cue-, Loop- und Beatgrid-Decoder.

## 2. EngineX-WLAN-Datenkanal

- Gezielt genau einen Track statt sämtlicher Analyse-BLOBs übertragen.
- Authentifizierter, nur lesender Analyse-Endpunkt für `beatData`, `quickCues`, `loops` und Waveform-Metadaten.
- Audio-Endpunkt mit HTTP-Range-Unterstützung, damit der Player suchen kann, ohne komplette Tracks vorab zu laden.
- Laufwerks- und Pfadprüfung ausschließlich anhand bereits erkannter Engine-Datenträger und Track-IDs; keine frei wählbaren Serverpfade.
- Größenlimits, Timeouts und Abbruch bei getrenntem WLAN.

## 3. Mobile Track- und Performance-Ansicht

- Trackkopf mit Titel, Interpret, BPM, Camelot-Key, Rating und Tags.
- Übersichtswaveform mit aktueller Position, Cue-/Loop-Markern und Zoom-/Scroll-Gesten.
- Media3-Player mit Play/Pause, Seek, Vorhören und klarer Ladeanzeige.
- Hot-Cue- und Loop-Bänke als große Touch-Flächen; zunächst im lokalen Änderungsentwurf, ohne Prime-Datenbank zu verändern.
- Beatgrid-Werkzeuge für Verschieben, BPM-Korrektur, Halbieren/Verdoppeln und Tap-Tempo.

## 4. Sicherer lokaler Editor

- Änderungen als getrenntes, nachvollziehbares Change-Set im privaten App-Speicher.
- Sterne, Tags, Playlist-Operationen, Hot Cues, Loops und Beatgrid zunächst gegen die Offline-Momentaufnahme bearbeiten.
- Undo/Redo und Vorher-/Nachher-Vergleich.
- Keine proprietären Felder schreiben, bevor Encoder und Rundlauf mit echten Testdaten bestätigt sind.

## 5. Bidirektionale Synchronisierung

- Prime- und Android-Änderungen anhand stabiler Herkunftsschlüssel und Revisionen vergleichen.
- Konflikte einzeln anzeigen und auflösen.
- Vor jedem Schreibvorgang vollständiges Backup der betroffenen Database2-Dateien.
- Änderungen nur in einer Arbeitskopie anwenden, SQLite-Integrität prüfen und anschließend kontrolliert ersetzen.
- Schreiben ausschließlich in einem Betriebszustand, in dem Engine OS die Datenbank nicht gleichzeitig geöffnet hat.

## 6. Optionale intelligente Analyse

- Camelot-Farben als eigenständiges, rechtlich unkritisches Farbsystem definieren.
- Spätere Integration externer Analyse- und Playlist-Vorschläge über eine dokumentierte Schnittstelle.
- Übernahme von Ergebnissen nur nach Vorschau; Herkunft und Konfidenz jedes Vorschlags sichtbar halten.

Die Bedienlogik orientiert sich an DJ-Arbeitsabläufen mit Waveform, Performance-Pads und Grid-Werkzeugen. Bestehende geschützte Produktoberflächen werden nicht pixelgenau kopiert.
