# Engine DJ Companion

Native, unabhängige Android-App in Kotlin / Jetpack Compose Material 3 mit Engine-Library, WLAN-Player und abgesicherten Metadaten-, Hot-Cue- und Beatgrid-Werkzeugen. Der separate ARMv7-Agent läuft direkt auf dem Prime 4+. Keine offizielle Denon-/Engine-Anwendung.

Version 0.18 ergänzt einen Playlist-Editor für die Live-Library. Playlists lassen sich erstellen, umbenennen und sicher löschen; Tracks können hinzugefügt, entfernt und neu sortiert werden. Agent v9 sichert und prüft die Engine-Datenbank vor jeder Schreibtransaktion.

Version 0.18.2 zeigt bei der Trackauswahl die vollständige Library in einer zuverlässigen Tab-Ansicht. Der experimentelle Engine-Service-Reload wurde entfernt, weil er auf dem Prime 4+ einen Geräteneustart auslöst. Playlist-Änderungen werden beim nächsten regulären Neustart sichtbar.

Version 0.18.3 verwendet für Playlist-Änderungen Engines vorhandene native Trigger, lokale Track-IDs, die aktuelle Library-UUID und den Import-Read-Counter. Dadurch übernimmt das laufende Engine-Modell die Änderung über denselben Datenpfad wie EngineX; ein Service- oder Geräteneustart gehört nicht mehr zum Ablauf.

Version 0.18.4 ersetzt die globale Fremdschlüsselprüfung durch eine gezielte Validierung der geänderten Playlist, ihrer Mitgliedschaften und ihrer Verkettung. Bereits vorhandene, unabhängige Datenbankabweichungen blockieren damit keinen Playlist-Commit mehr.

**Der praktisch bestätigte Gesamtstand einschließlich Versionen, Schreibsicherheit und rebootfester Agent-Installation steht in [docs/CURRENT_STATUS.md](docs/CURRENT_STATUS.md).** Ältere Abschnitte dieses Dokuments beschreiben teilweise frühere Entwicklungsphasen.

## USB-Import

1. APK installieren und Prime 4+ im Computer-Modus über USB-OTG verbinden.
2. **USB-Ordner freigeben** antippen, sobald „USB-Speicher erkannt“ erscheint. Falls kein Massenspeicher erkannt wird, bleibt **SSD / Backup wählen** verfügbar. SSD-Wurzel, `Engine Library` oder `Database2` auswählen.
3. Während des Imports Verbindung bestehen lassen. Alternativ vollständigen Database2-Backupordner auf dem Telefon auswählen.
4. **Playlists** öffnen, Playlist antippen und Titel/Reihenfolge mit dem Prime vergleichen.
5. Bei unbekanntem Schema **Diagnose → Diagnose exportieren** verwenden.

Die App fordert nur Lesezugriff auf die Quelle an. Nur der ausdrücklich gewählte Diagnoseexport schreibt ein externes Dokument.

## EngineX über WLAN

Version 0.2.0 kann sich mit EngineX OS im lokalen Netz verbinden. Unter **EngineX verbinden** werden Prime-Adresse (beispielsweise `192.168.178.67`) und der Zugriffscode des EngineX-Webinterfaces eingetragen. Der Code liegt verschlüsselt im Android Keystore. Die App prüft zunächst die vorhandene authentifizierte `/api/drives`-Schnittstelle.

Für **Library über WLAN aktualisieren** benötigt EngineX zusätzlich den mitgelieferten, nur lesenden Companion-Endpunkt `/api/companion/snapshot`. Er unterstützt derzeit ausschließlich das auf dem echten Datenträger bestätigte Schema 3.0.2. Die Antwort enthält Track-, Playlist- und Zuordnungsmetadaten, aber keine Musikpfade oder Audiodateien. Die geladene Momentaufnahme bleibt anschließend im privaten App-Speicher ohne USB und ohne WLAN verfügbar.

## Bauen und Installieren

Android Studio mit Unterstützung für AGP 9.3, JDK 17, SDK API 36 und Build Tools 36.0.0. Gradle 9.5.0 Wrapper liegt nach Projektvorbereitung bei. USB-Massenspeicher wird während der App-Nutzung erkannt; der Dateizugriff erfolgt weiterhin ausschließlich nach ausdrücklicher SAF-Ordnerfreigabe.

Versionierte Abhängigkeiten: Kotlin Compose-Compiler 2.3.21, Compose BOM 2025.10.00, Activity 1.10.1, Lifecycle 2.10.0, DocumentFile 1.1.0, Coroutines 1.10.2.

Projekt in Android Studio öffnen, SDK installieren und Gradle Sync ausführen.

```
./gradlew assembleDebug testDebugUnitTest lintDebug
```

Windows PowerShell: `.\gradlew.bat assembleDebug testDebugUnitTest lintDebug`.
APK: `app/build/outputs/apk/debug/app-debug.apk`.
Installation: APK auf dem Telefon öffnen, Installation für den verwendeten Dateimanager erlauben. Alternativ `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
`local.properties` wird durch Android Studio erzeugt; keine externen Laufwerkspfade sind fest codiert.

Ab Version 0.5.1 verwendet jeder Debug-Build denselben eingecheckten Debug-Schlüssel. Beim einmaligen Wechsel von älteren, durch wechselnde GitHub-Runner signierten APKs muss die vorhandene App noch einmal deinstalliert werden. Danach lassen sich neue Debug-APKs direkt als Update installieren; private App-Daten und der verschlüsselt gespeicherte EngineX-Zugang bleiben erhalten. Der Schlüssel ist ausdrücklich öffentlich und darf niemals für einen Release-Build oder eine Veröffentlichung im Play Store verwendet werden.

## Funktionen

- SAF-Ordnerwahl, dauerhafte Lesefreigabe soweit vom Provider erlaubt, manuelle Erreichbarkeitsprüfung.
- DB-/WAL-Import in privaten Speicher; SHA-256 über zwei vollständige Lesevorgänge, wiederholte Dateilistenprüfung, Abbruch bei Änderungen.
- Unveränderte Originalkopie plus Arbeitskopie. SQLite-WAL-Recovery, Checkpoint und integrity_check ausschließlich lokal.
- Vorheriger erfolgreicher Import bleibt bei Fehlern erhalten. Erfolgreiche Momentaufnahme offline nach App-Neustart verfügbar.
- sqlite_master-, Spalten-, PK-, FK- und Versionsanalyse aller importierten DB-Dateien. Diagnosedateiexport.
- Getrennte Schema-2-/Schema-3-Adapter, Trackliste, Suche, Sortierung, Playlist-Zuordnung und verkettete Reihenfolge.
- Lokale Playlist-Einträge werden wie beim EngineX-Endpunkt über `databaseUuid + trackId` gegen `originDatabaseUuid + originTrackId` auf die tatsächliche lokale Track-ID abgebildet.
- Titel, Interpret, Album, Dauer, BPM, Tonart, Sterne bei dokumentierten Werten; unbekannte Werte bleiben als Rohwert sichtbar.
- Tonarten erscheinen in der Trackliste als Camelot-Code; Details zeigen zusätzlich die musikalische Schreibweise und den unveränderten Rohwert.
- Tracklisten zeigen geladene und vollständige Trefferzahl getrennt; weitere Ergebnisse werden in 200er-Schritten geladen.
- Trackdetails einschließlich tatsächlicher Rohmetadaten und PerformanceData-BLOB-Längen.
- Vollflächige Trackdetails mit exportierbarem, trackbezogenem Analysebericht; BLOBs werden nur mit Länge und maximal 64 Byte Hex-Präfix erfasst.
- Strikt lesender Decoder für die bestätigten Engine-3.x-Strukturen `quickCues`, `loops` und `beatData`; Sample-Positionen werden mit der gespeicherten Sample-Rate als Sekunden dargestellt.
- Smartphone-optimierte Performance-Ansicht mit echten Cue-/Loop-Farben, Main Cue, Zeitpositionen und aus den äußeren Beatgrid-Markern berechnetem Kontroll-BPM.
- Authentifizierte EngineX-LAN-Erkennung, verschlüsselte Zugangsdaten und private Offline-Momentaufnahme.
- Wechsel zwischen lokaler USB-Momentaufnahme und EngineX-WLAN-Momentaufnahme.
- Aufklappbarer Playlist-Baum mit gespeichertem Zustand, visueller Ordner-/Playlist-Unterscheidung, Playlist-Suche und rekursiven Trackzahlen für EngineX- und USB-Momentaufnahmen.

## Grenzen

USB-Import und EngineX-WLAN-Synchronisierung wurden auf dem ASUS ROG Phone 9 Pro mit der internen SSD des Prime 4+ praktisch bestätigt. Echte Tracks, Playlist-Baum, Zuordnungen und native Reihenfolge werden angezeigt; die WLAN-Momentaufnahme mit 7401 Tracks und 48 Playlists bleibt offline verfügbar.

Hot Cues, gespeicherte Loops, Beatgrids und Waveforms sind mit echten Prime-Daten praktisch bestätigt. WLAN-Wiedergabe sowie abgesicherte Schreibzugriffe für Tags, Bewertungen, Hot Cues und Beatgrids sind implementiert. Loop-Bearbeitung, Playlist-Schreibfunktionen, vollständige Konfliktauflösung und Smartlists stehen noch aus.

SAF bietet keine atomare Sperre mehrerer Dateien. Doppelte Hash-Prüfung erkennt beobachtete Änderungen, ersetzt aber keine ruhende Quelle. Nicht während paralleler Schreibzugriffe importieren. Im Prime-Computer-Modus wird kein gleichzeitiger Engine-OS-Zugriff unterstellt.

Alle unmittelbaren .db-Dateien und zugehörigen WAL-Dateien werden kopiert. SHM wird lokal neu aufgebaut. Ein nichtleeres Rollback-Journal führt zu erklärtem Abbruch; Sidecars niemals manuell entfernen. Andere Analyse-Dateibäume werden noch nicht importiert.

Maximal 4 GiB Import, mindestens doppelte DB-Größe plus Arbeitsraum intern nötig. Listen werden in 200er-Schritten bis 100.000 Ergebnissen angezeigt. Die echte WLAN-Bibliothek mit 7401 Tracks wurde auf dem Telefon geprüft; weitere Langzeit- und Speicherverbrauchsmessungen stehen aus. Ein Abbruch kann eine laufende SQLite-Prüfung abwarten. USB-Geräte werden während der App-Laufzeit und beim Zurückkehren in die App erneut geprüft.

## Architektur

- storage/SnapshotImporter: SAF, Suche, Kopieren, Hash-Prüfung, Abbruch.
- data/Schema: Introspektion, Modelle, Playlist-Verkettung.
- data/EngineAdapter: versionierte Leseadapter und Registry.
- data/LibraryRepository: private SQLite-Dateien, Aktivierung, Integrität, Abfragen.
- ui/ExplorerViewModel: StateFlow, Coroutines, Mutex, Fehlerzustände.
- MainActivity: native Compose-Oberfläche und expliziter Diagnosedateiexport.

Siehe docs/COMPATIBILITY.md und docs/VALIDATION.md.
Der weitere Weg vom Explorer zum WLAN-Player und sicheren Editor steht in `docs/ROADMAP.md`.
Der separate ARMv7-Dienst für gezielte Analyse-, Audio- und abgesicherte Schreibzugriffe liegt unter `prime-agent/` und wird als eigenes GitHub-Actions-Artefakt gebaut.
