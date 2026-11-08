# Validierung — 8. November 2026

Aktueller Entwicklungsstand: `0.4.0-readonly` (Version Code 10). Der GitHub-CI-Lauf für 0.3.3 war vollständig erfolgreich; 0.4.0 wird nach dem Push erneut geprüft.

```
./gradlew clean assembleDebug testDebugUnitTest lintDebug
```

- Clean-Build: erfolgreich.
- JUnit/Robolectric: 26 Tests, 0 Fehler, 0 Fehlschläge, 0 übersprungen.
- Darunter vier EngineX-Clienttests für Adressvalidierung, Laufwerksantwort, Snapshot-Parsing und native Playlist-Reihenfolge.
- Android Lint: 0 Fehler, 7 Warnungen (optionale neuere Testabhängigkeit, konservative Speicherplatzprüfung und optionale KTX-Schreibweisen).
- APK: `apksigner verify` erfolgreich, APK Signature Scheme v2, ein Android-Debug-Signer.
- Werkzeugstand: JDK 17, Gradle 9.5.0, AGP 9.3.0, Android API 36, Build Tools 36.0.0.

## Änderung 0.2.0

EngineX kann über die vorhandene authentifizierte LAN-API erkannt werden. Host und Zugriffscode werden nicht in Klartext zusammen gespeichert; der Zugriffscode wird mit einem nicht exportierbaren AES-GCM-Schlüssel im Android Keystore verschlüsselt. Eine vom Companion-Endpunkt geladene Bibliothek wird atomar im privaten App-Speicher abgelegt und bleibt offline durchsuchbar.

Der passende EngineX-5.3.0.2-Endpunkt wurde als eng begrenzte, nur lesende Erweiterung gebaut. Er akzeptiert nur erkannte Datenträger, öffnet SQLite mit `mode=ro` und `query_only`, unterstützt ausschließlich das bestätigte Schema 3.0.2 und gibt keine Audiodateipfade aus. Der gezielte Go-Test besteht. Die vollständige EngineX-WebUI-Suite ist im Build-Container nicht vollständig grün, weil zwei bestehende Pro-DJ-Link-Tests Netlink beziehungsweise echte Mounts voraussetzen.

## Änderung 0.2.1

Playlist-Zuordnungen aus der WLAN-Momentaufnahme werden wie beim validierten USB-Adapter anhand von `listId`, `trackId` und `nextEntityId` aufgelöst. Eine abweichende Herkunfts-UUID verwirft den Eintrag nicht mehr. Die Playlistliste zeigt Trackzahlen und Elternnamen. USB- und EngineX-Verbindungsoptionen sind standardmäßig eingeklappt und über **Einstellungen** erreichbar.

## Änderung 0.3.1

Playlist-Baum: Aufklappzustand wird über Prozessneustarts gespeichert. Ordner und echte Playlists sind visuell deutlicher getrennt (Farbe, Symbol, Schriftstärke). Playlist-Suche filtert Titel und hält Eltern sichtbar. Für EngineX-Momentaufnahmen werden Trackzahlen von Ordnern rekursiv zusammengezählt. Ordner mit eigenen Tracks bieten einen expliziten „Öffnen“-Button.

## Änderung 0.3.2

USB- und Backup-Momentaufnahmen erhalten jetzt dieselben direkten und rekursiven Playlist-Trackzahlen wie EngineX-WLAN-Momentaufnahmen. Die Zuordnung wird ausschließlich aus bestätigten `PlaylistEntity`-Einträgen mit passender Datenbank-UUID aufgebaut. Zwei zusätzliche Unit-Tests prüfen rekursive Zahlen, doppelte Einträge und defekte Elternzyklen. Dokumentation und tatsächlich verwendete Android-/Compose-Versionen wurden abgeglichen.

## Änderung 0.3.3

Die bestätigte Engine-Tonartskala 0–23 wird in den Trackkarten als Camelot 1A–12B dargestellt. Die Detailansicht ergänzt dazu die musikalische Tonart und behält den Rohwert für die Diagnose. Tracklisten zeigen jetzt „geladen von insgesamt“; lokale SQLite-Suchen verwenden für die Gesamtzahl dieselben Such- und Playlistfilter wie die eigentliche Abfrage.

## Änderung 0.3.4

Trackdetails öffnen als eigene vollflächige Ansicht und formatieren Dauer und BPM konsistent. Der Bericht kann als Textdatei exportiert werden. Bei einer lokalen USB-/Backup-Momentaufnahme enthält er für vorhandene `PerformanceData`-BLOBs ausschließlich Spaltenname, Länge und ein auf 64 Byte begrenztes Hex-Präfix. Diese nachweisbaren Rohdaten bilden die Grundlage für die spätere Cue-/Loop-/Beatgrid-Analyse; es findet noch keine Interpretation oder Änderung statt.

## Änderung 0.4.0

Ein eigenständiger, strikt lesender Decoder verarbeitet die von `libdjinterop` dokumentierten Engine-3.x-Strukturen für Hot Cues, gespeicherte Loops und Beatgrids. Komprimierte Daten erhalten ein festes 64-MiB-Limit; Eintragszahlen, Feldgrenzen und unkomprimierte Länge werden geprüft. Sample-Positionen erscheinen anhand der im Beatgrid gespeicherten Sample-Rate in Sekunden. Synthetische Binärfixtures prüfen Big-/Little-Endian-Felder, Qt-/zlib-Dekompression, Cue-/Loop-Zeitumrechnung und die Ablehnung unplausibler Eintragszahlen. Die praktische Übereinstimmung mit bekannten Markern auf dem Prime steht noch aus.

## Änderung 0.3.0

Playlists werden als aufklappbarer Baum aus `parentListId` dargestellt; Geschwister folgen der tatsächlichen `nextListId`-Kette. Drei zusätzliche Unit-Tests prüfen eingeklappte und ausgeklappte Bäume sowie den sicheren Fallback bei ungültigen Ketten. Trackzeilen sind kompakter. Die WLAN-Detailansicht zeigt Dauer, übersetzte Tonart, Bewertung und alle Playlists, in denen der Track vorkommt.

## Bereits bestätigter Gerätestand

USB-Import und EngineX-WLAN-Synchronisierung wurden auf ASUS ROG Phone 9 Pro und Prime 4+ praktisch geprüft. Android erkennt den USB-Speicher; nach SAF-Freigabe zeigt die App reale Tracks und Playlists. Der EngineX-Companion-Endpunkt liefert eine echte Momentaufnahme mit 7401 Tracks und 48 Playlists. Die korrigierte Zuordnung über Herkunfts-UUID und Track-ID sowie die native Playlist-Reihenfolge wurden mit einer Playlist am Prime verglichen.

## Änderung 0.1.1

Die App beobachtet während ihrer Laufzeit USB-Geräte und erkennt Massenspeicher sowohl über die Geräteklasse als auch über Interfaces zusammengesetzter USB-Geräte. Beim Zurückkehren in die App wird erneut geprüft. Die Anzeige trennt jetzt drei Zustände: USB-Massenspeicher erkannt, SAF-Ordnerfreigabe vorhanden und Datenbank importiert. Bei erkanntem Speicher führt **USB-Ordner freigeben** direkt zum Android-Ordnerdialog. **Freigabe prüfen** ist erst nach einer gespeicherten Auswahl aktiv.

Die USB-Erkennung gewährt keinen Dateizugriff und öffnet keine Linux-Pfade. Der Zugriff bleibt vollständig über Android SAF und ausschließlich lesend. Ein generischer USB-SATA-Adapter kann nur als USB-Massenspeicher, nicht sicher als Prime 4+, identifiziert werden.

## Noch nicht geprüft

Unbekannte Datenbankschemata werden nur diagnostiziert. Cues, Loops, Beatgrids und Waveforms werden weiterhin nicht dekodiert; Bearbeitung und bidirektionale Synchronisierung sind nicht implementiert. Langzeitverhalten, sehr große Playlist-Bäume und Wiederanlauf nach WLAN-Abbruch benötigen weitere Gerätetests.
