# Beleglage und erforderliche Testdaten

Keine Gerätekompatibilität behauptet. Unterstützte Referenzprofile: 2.18.0, 2.20.1–2.20.3, 2.21.0–2.21.2 sowie 3.0.0–3.0.2, jeweils mit zusätzlicher Prüfung aller benötigten Spalten. Andere Versionen und fehlende Pflichtspalten führen ausschließlich zur Diagnose. Schema-Version und Engine-OS-Version sind verschieden.

Die Adapter lesen vorhandene Track-Spalten. PlaylistEntity.listId/trackId verknüpfen Listen und Tracks; nextEntityId gibt die Reihenfolge vor. Fremde/fehlende Datenbank-UUIDs werden nicht lokal zugeordnet. Ungültige Verkettungen und fehlende Track-IDs erzeugen Warnungen.

Referenz: libdjinterop, Commit 597be49121abf175f5347662e425ae28a463d922. Eigene Kotlin-Implementierung; keine C++-Bibliothek eingebunden und kein fremder Implementierungscode übernommen.

- https://github.com/xsco/libdjinterop/blob/597be49121abf175f5347662e425ae28a463d922/src/djinterop/engine/schema/schema_3_0_0.cpp
- https://github.com/xsco/libdjinterop/blob/597be49121abf175f5347662e425ae28a463d922/src/djinterop/engine/schema/schema_2_20_3.cpp
- https://github.com/xsco/libdjinterop/blob/597be49121abf175f5347662e425ae28a463d922/src/djinterop/engine/v2/playlist_entity_table.cpp
- https://github.com/xsco/libdjinterop/blob/597be49121abf175f5347662e425ae28a463d922/src/djinterop/engine/v2/playlist_impl.cpp
- https://github.com/xsco/libdjinterop/blob/597be49121abf175f5347662e425ae28a463d922/src/djinterop/engine/v2/convert_track.hpp
- https://github.com/xsco/libdjinterop/blob/597be49121abf175f5347662e425ae28a463d922/include/djinterop/musical_key.hpp
- https://support.enginedj.com/support/solutions/articles/69000834165

Herstellerdokumentation und neuere Schema-Referenz verwenden unterschiedliche Schreibweisen der Origin-Felder. Die App erhält diese Rohwerte unverändert. Synchronisierungsschlüssel werden erst anhand echter Daten festgelegt. Track.id allein ist dafür nicht ausreichend.

Key 0–23 wird als dokumentierte Tonart dargestellt; reguläres Rating 0–100 in 20er-Schritten als Sterne plus Rohwert. Unbekannte Werte werden nicht geklemmt oder umgedeutet.

## Bitte bereitstellen

Den vollständigen Ordner `Engine Library/Database2` aus EINEM konsistenten Backup als ZIP, mit allen vorhandenen .db-, -wal-, -shm- und -journal-Dateien. Keine Musikdateien nötig. Sidecars nicht separat löschen. Alternative: kleine, von Engine selbst erzeugte Testbibliothek mit einer Playlist und zwei Tracks.

Dazu Engine-OS-Version, Playlistname, erwartete Trackanzahl und Reihenfolge nennen. Für spätere Cue-/Loop-/Beatgrid-Decoder einen bekannten Track mit passenden Screenshots und Analyse-Dateien ergänzen. Es werden keine Änderungen an proprietären Strukturen auf unbestätigter Grundlage vorgenommen.
