package main

import (
	"bytes"
	"compress/zlib"
	"context"
	"crypto/subtle"
	"database/sql"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"math"
	"mime"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	_ "modernc.org/sqlite"
)

const (
	listenAddress = ":8081"
	tokenPath     = "/data/sc5000-custom/state/access-token"
	agentVersion  = 15
	localNotifyAddress = "127.0.0.1:8082"
)

type drive struct {
	root string
	db   string
}

var allowedDrives = map[string]drive{
	"Engine": {
		root: "/media/Engine/Engine Library",
		db:   "/media/Engine/Engine Library/Database2/m.db",
	},
}

type server struct {
	drives    map[string]drive
	tokenFile string
	semaphore chan struct{}
	backupMu  sync.Mutex
	backups   map[string]string
	playlistRevision atomic.Uint64
}

type analysisResponse struct {
	TrackID              int64  `json:"trackId"`
	SchemaVersion        string `json:"schemaVersion"`
	BeatData             []byte `json:"beatData,omitempty"`
	QuickCues            []byte `json:"quickCues,omitempty"`
	Loops                []byte `json:"loops,omitempty"`
	OverviewWaveformData []byte `json:"overviewWaveformData,omitempty"`
}

type metadataResponse struct {
	TrackID int64 `json:"trackId"`
	Title string `json:"title"`
	Artist string `json:"artist"`
	Album string `json:"album"`
	Genre string `json:"genre"`
	Comment string `json:"comment"`
	Label string `json:"label"`
	Remixer string `json:"remixer"`
	Rating int `json:"rating"`
	Backup string `json:"backup,omitempty"`
}

type cueInput struct {
	Number int `json:"number"`
	Label string `json:"label"`
	SampleOffset float64 `json:"sampleOffset"`
	Color string `json:"color"`
}

type cuesRequest struct { Cues []cueInput `json:"cues"` }
type beatgridRequest struct { BPM float64 `json:"bpm"`; OffsetMS int64 `json:"offsetMs"` }
type playlistRequest struct {
	Action string `json:"action"`
	PlaylistID int64 `json:"playlistId"`
	Title string `json:"title"`
	ParentID int64 `json:"parentId"`
	TrackIDs []int64 `json:"trackIds"`
}

var safeDriveName = regexp.MustCompile(`^[A-Za-z0-9_-]+$`)
var safeCueColor = regexp.MustCompile(`^#[0-9A-Fa-f]{6}$`)

func main() {
	s := &server{drives: allowedDrives, tokenFile: tokenPath, semaphore: make(chan struct{}, 2), backups: make(map[string]string)}
	mux := http.NewServeMux()
	mux.HandleFunc("/api/companion/v1/health", s.authorize(s.health))
	mux.HandleFunc("/api/companion/v1/playlists/schema", s.authorize(s.playlistSchema))
	mux.HandleFunc("/api/companion/v1/playlists", s.authorize(s.playlists))
	mux.HandleFunc("/api/companion/v1/tracks/", s.authorize(s.track))
	localMux := http.NewServeMux()
	localMux.HandleFunc("/playlist-revision", s.playlistRevisionStatus)
	go func() {
		log.Printf("Engine Companion local UI notification listening on %s", localNotifyAddress)
		if err := http.ListenAndServe(localNotifyAddress, localMux); err != nil {
			log.Fatalf("local UI notification failed: %v", err)
		}
	}()
	httpServer := &http.Server{
		Addr:              listenAddress,
		Handler:           mux,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      2 * time.Minute,
		IdleTimeout:       30 * time.Second,
	}
	log.Printf("Engine Companion Agent listening on %s", listenAddress)
	log.Fatal(httpServer.ListenAndServe())
}

func (s *server) authorize(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet && r.Method != http.MethodHead && r.Method != http.MethodPost {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		expected, err := os.ReadFile(s.tokenFile)
		if err != nil {
			http.Error(w, "access token unavailable", http.StatusServiceUnavailable)
			return
		}
		provided := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
		want := strings.TrimSpace(string(expected))
		if len(provided) != len(want) || subtle.ConstantTimeCompare([]byte(provided), []byte(want)) != 1 {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		select {
		case s.semaphore <- struct{}{}:
			defer func() { <-s.semaphore }()
			next(w, r)
		default:
			http.Error(w, "busy", http.StatusServiceUnavailable)
		}
	}
}

func (s *server) health(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, map[string]any{"service": "engine-companion-agent", "version": agentVersion, "readOnly": false, "metadataWrite": true, "playlistWrite": true, "playlistLiveNotify": true})
}

func (s *server) playlistRevisionStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, map[string]any{"revision": s.playlistRevision.Load()})
}

func (s *server) playlists(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost { http.Error(w, "method not allowed", http.StatusMethodNotAllowed); return }
	driveName := r.URL.Query().Get("drive")
	d, ok := s.drives[driveName]
	if !ok { http.Error(w, "unknown drive", http.StatusBadRequest); return }
	r.Body = http.MaxBytesReader(w, r.Body, 2<<20)
	var input playlistRequest
	if err := json.NewDecoder(r.Body).Decode(&input); err != nil { http.Error(w, "invalid playlist request", http.StatusBadRequest); return }
	input.Title = strings.TrimSpace(input.Title)
	if len([]byte(input.Title)) > 512 || input.ParentID < 0 || input.PlaylistID < 0 || len(input.TrackIDs) > 100000 {
		http.Error(w, "invalid playlist request", http.StatusBadRequest); return
	}
	switch input.Action {
	case "create": if input.Title == "" || input.PlaylistID != 0 { http.Error(w, "invalid create request", http.StatusBadRequest); return }
	case "rename": if input.Title == "" || input.PlaylistID <= 0 { http.Error(w, "invalid rename request", http.StatusBadRequest); return }
	case "delete": if input.PlaylistID <= 0 { http.Error(w, "invalid delete request", http.StatusBadRequest); return }
	case "replaceTracks":
		if input.PlaylistID <= 0 { http.Error(w, "invalid track request", http.StatusBadRequest); return }
		seen := make(map[int64]bool, len(input.TrackIDs)); for _, id := range input.TrackIDs { if id <= 0 || seen[id] { http.Error(w, "invalid or duplicate track id", http.StatusBadRequest); return }; seen[id] = true }
	default: http.Error(w, "unknown playlist action", http.StatusBadRequest); return
	}
	backup, err := s.backupLibrary(r, d.db, driveName)
	if err != nil { http.Error(w, "backup failed: "+err.Error(), http.StatusServiceUnavailable); return }
	dsn := (&url.URL{Scheme: "file", Path: d.db, RawQuery: "mode=rw&_pragma=busy_timeout(5000)&_pragma=foreign_keys(1)"}).String()
	db, err := sql.Open("sqlite", dsn)
	if err != nil { http.Error(w, "library write open failed", http.StatusServiceUnavailable); return }
	defer db.Close(); db.SetMaxOpenConns(1)
	tx, err := db.BeginTx(r.Context(), nil)
	if err != nil { http.Error(w, "library busy", http.StatusConflict); return }
	if err = requireNativePlaylistTriggers(r.Context(), tx); err != nil { _ = tx.Rollback(); http.Error(w, err.Error(), http.StatusConflict); return }
	playlistID := input.PlaylistID
	if err = mutatePlaylist(r.Context(), tx, &playlistID, input); err != nil {
		_ = tx.Rollback(); status := http.StatusConflict
		if errors.Is(err, sql.ErrNoRows) { status = http.StatusNotFound }
		http.Error(w, err.Error(), status); return
	}
	if _, err = tx.ExecContext(r.Context(), `UPDATE Information SET lastRekordBoxLibraryImportReadCounter=COALESCE(lastRekordBoxLibraryImportReadCounter,0)+1`); err != nil {
		_ = tx.Rollback(); http.Error(w, "library change notification failed", http.StatusConflict); return
	}
	if err = validatePlaylistMutation(r.Context(), tx, playlistID, input.Action); err != nil {
		_ = tx.Rollback(); http.Error(w, "playlist validation failed: "+err.Error(), http.StatusConflict); return
	}
	if err = tx.Commit(); err != nil { http.Error(w, "playlist commit failed", http.StatusInternalServerError); return }
	s.playlistRevision.Add(1)
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, map[string]any{"action": input.Action, "playlistId": playlistID, "backup": backup})
}

func validatePlaylistMutation(ctx context.Context, tx *sql.Tx, playlistID int64, action string) error {
	var exists int
	if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM Playlist WHERE id=?`, playlistID).Scan(&exists); err != nil { return err }
	if action == "delete" {
		if exists != 0 { return errors.New("deleted playlist still exists") }
		return nil
	}
	if exists != 1 { return errors.New("changed playlist missing") }
	var badNext int
	if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM PlaylistEntity e LEFT JOIN PlaylistEntity n ON n.id=e.nextEntityId AND n.listId=e.listId WHERE e.listId=? AND coalesce(e.nextEntityId,0)<>0 AND n.id IS NULL`, playlistID).Scan(&badNext); err != nil { return err }
	if badNext != 0 { return fmt.Errorf("%d broken membership links", badNext) }
	return nil
}

func requireNativePlaylistTriggers(ctx context.Context, tx *sql.Tx) error {
	required := []string{"trigger_before_insert_List", "trigger_after_insert_List", "trigger_after_delete_List", "trigger_before_delete_PlaylistEntity"}
	for _, name := range required {
		var count int
		if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM sqlite_master WHERE type='trigger' AND name=?`, name).Scan(&count); err != nil { return err }
		if count != 1 { return fmt.Errorf("required Engine playlist trigger missing: %s", name) }
	}
	return nil
}

func mutatePlaylist(ctx context.Context, tx *sql.Tx, playlistID *int64, input playlistRequest) error {
	switch input.Action {
	case "create":
		if input.ParentID != 0 { var exists int; if err := tx.QueryRowContext(ctx, `SELECT 1 FROM Playlist WHERE id=?`, input.ParentID).Scan(&exists); err != nil { return fmt.Errorf("parent playlist not found: %w", err) } }
		_, err := tx.ExecContext(ctx, `INSERT INTO Playlist(title,parentListId,isPersisted,nextListId,isExplicitlyExported,lastEditTime) VALUES(?,?,1,0,1,strftime('%s'))`, input.Title, input.ParentID)
		if err != nil { return fmt.Errorf("playlist already exists: %w", err) }
		var id int64
		if err := tx.QueryRowContext(ctx, `SELECT id FROM Playlist WHERE title=? AND parentListId=?`, input.Title, input.ParentID).Scan(&id); err != nil {
			return fmt.Errorf("created playlist id lookup failed: %w", err)
		}
		*playlistID = id
		return nil
	case "rename":
		result, err := tx.ExecContext(ctx, `UPDATE Playlist SET title=?,lastEditTime=strftime('%s') WHERE id=?`, input.Title, input.PlaylistID)
		if err != nil { return fmt.Errorf("playlist title already exists: %w", err) }; return requireOne(result)
	case "delete":
		var exists int
		if err := tx.QueryRowContext(ctx, `SELECT 1 FROM Playlist WHERE id=?`, input.PlaylistID).Scan(&exists); err != nil { return err }
		var children int; if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM Playlist WHERE parentListId=?`, input.PlaylistID).Scan(&children); err != nil { return err }
		if children != 0 { return errors.New("playlist contains child playlists") }
		result, err := tx.ExecContext(ctx, `DELETE FROM Playlist WHERE id=?`, input.PlaylistID); if err != nil { return err }; return requireOne(result)
	case "replaceTracks":
		var exists int; if err := tx.QueryRowContext(ctx, `SELECT 1 FROM Playlist WHERE id=?`, input.PlaylistID).Scan(&exists); err != nil { return err }
		var databaseUUID string; if err := tx.QueryRowContext(ctx, `SELECT coalesce(uuid,'') FROM Information LIMIT 1`).Scan(&databaseUUID); err != nil || databaseUUID == "" { return errors.New("library uuid unavailable") }
		for _, localID := range input.TrackIDs { var found int; if err := tx.QueryRowContext(ctx, `SELECT 1 FROM Track WHERE id=?`, localID).Scan(&found); err != nil { return fmt.Errorf("track %d not found: %w", localID, err) } }
		if _, err := tx.ExecContext(ctx, `DELETE FROM PlaylistEntity WHERE listId=?`, input.PlaylistID); err != nil { return err }
		var previous int64
		for _, localID := range input.TrackIDs {
			result, err := tx.ExecContext(ctx, `INSERT INTO PlaylistEntity(listId,trackId,databaseUuid,nextEntityId,membershipReference) VALUES(?,?,?,0,0)`, input.PlaylistID, localID, databaseUUID); if err != nil { return err }
			entityID, err := result.LastInsertId(); if err != nil { return err }
			if previous != 0 { if _, err = tx.ExecContext(ctx, `UPDATE PlaylistEntity SET nextEntityId=? WHERE id=?`, entityID, previous); err != nil { return err } }
			previous = entityID
		}
		_, err := tx.ExecContext(ctx, `UPDATE Playlist SET lastEditTime=strftime('%s') WHERE id=?`, input.PlaylistID); return err
	}
	return errors.New("unsupported action")
}

func requireOne(result sql.Result) error { changed, err := result.RowsAffected(); if err != nil { return err }; if changed != 1 { return sql.ErrNoRows }; return nil }

func (s *server) playlistSchema(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	d, ok := s.drives[r.URL.Query().Get("drive")]
	if !ok {
		http.Error(w, "unknown drive", http.StatusBadRequest)
		return
	}
	db, version, err := openLibrary(r, d.db)
	if err != nil {
		http.Error(w, err.Error(), http.StatusServiceUnavailable)
		return
	}
	defer db.Close()
	queries := map[string]string{
		"tables": `SELECT name,sql FROM sqlite_master WHERE type='table' AND name IN ('Playlist','PlaylistEntity') ORDER BY name`,
		"playlistColumns": `PRAGMA table_info('Playlist')`,
		"playlistForeignKeys": `PRAGMA foreign_key_list('Playlist')`,
		"playlistIndexes": `PRAGMA index_list('Playlist')`,
		"entityColumns": `PRAGMA table_info('PlaylistEntity')`,
		"entityForeignKeys": `PRAGMA foreign_key_list('PlaylistEntity')`,
		"entityIndexes": `PRAGMA index_list('PlaylistEntity')`,
		"idRanges": `SELECT (SELECT max(id) FROM Playlist) AS maxPlaylistId,(SELECT max(id) FROM PlaylistEntity) AS maxEntityId`,
		"sequences": `SELECT name,seq FROM sqlite_sequence WHERE name IN ('Playlist','PlaylistEntity') ORDER BY name`,
		"playlistSamples": `SELECT id,title,parentListId,isPersisted,nextListId,lastEditTime,isExplicitlyExported FROM Playlist ORDER BY id LIMIT 10`,
		"playlistTailSamples": `SELECT id,title,parentListId,isPersisted,nextListId,lastEditTime,isExplicitlyExported FROM Playlist ORDER BY id DESC LIMIT 10`,
		"entitySamples": `SELECT pe.id,pe.listId,pe.trackId,pe.databaseUuid,pe.nextEntityId,pe.membershipReference,t.id AS localTrackId,t.originDatabaseUuid,t.originTrackId FROM PlaylistEntity pe LEFT JOIN Track t ON (t.originDatabaseUuid=pe.databaseUuid AND t.originTrackId=pe.trackId) OR (pe.databaseUuid=(SELECT uuid FROM Information LIMIT 1) AND t.id=pe.trackId) LIMIT 10`,
		"entityTailSamples": `SELECT pe.id,pe.listId,pe.trackId,pe.databaseUuid,pe.nextEntityId,pe.membershipReference,t.id AS localTrackId,t.originDatabaseUuid,t.originTrackId FROM PlaylistEntity pe LEFT JOIN Track t ON (t.originDatabaseUuid=pe.databaseUuid AND t.originTrackId=pe.trackId) OR (pe.databaseUuid=(SELECT uuid FROM Information LIMIT 1) AND t.id=pe.trackId) ORDER BY pe.id DESC LIMIT 10`,
	}
	result := map[string]any{"schemaVersion": version}
	for name, query := range queries {
		rows, queryErr := queryObjects(r.Context(), db, query)
		if queryErr != nil {
			http.Error(w, "playlist schema query failed: "+name, http.StatusInternalServerError)
			return
		}
		result[name] = rows
	}
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, result)
}

func queryObjects(ctx context.Context, db *sql.DB, query string, args ...any) ([]map[string]any, error) {
	rows, err := db.QueryContext(ctx, query, args...)
	if err != nil { return nil, err }
	defer rows.Close()
	columns, err := rows.Columns()
	if err != nil { return nil, err }
	result := make([]map[string]any, 0)
	for rows.Next() {
		values := make([]any, len(columns))
		destinations := make([]any, len(columns))
		for index := range values { destinations[index] = &values[index] }
		if err := rows.Scan(destinations...); err != nil { return nil, err }
		item := make(map[string]any, len(columns))
		for index, name := range columns {
			value := values[index]
			if bytesValue, ok := value.([]byte); ok { value = string(bytesValue) }
			item[name] = value
		}
		result = append(result, item)
	}
	return result, rows.Err()
}

func (s *server) track(w http.ResponseWriter, r *http.Request) {
	relative := strings.TrimPrefix(r.URL.Path, "/api/companion/v1/tracks/")
	parts := strings.Split(relative, "/")
	if len(parts) != 2 {
		http.NotFound(w, r)
		return
	}
	trackID, err := strconv.ParseInt(parts[0], 10, 64)
	if err != nil || trackID <= 0 {
		http.Error(w, "invalid track id", http.StatusBadRequest)
		return
	}
	d, ok := s.drives[r.URL.Query().Get("drive")]
	if !ok {
		http.Error(w, "unknown drive", http.StatusBadRequest)
		return
	}
	switch parts[1] {
	case "analysis":
		s.analysis(w, r, d, trackID)
	case "audio":
		s.audio(w, r, d, trackID)
	case "metadata":
		s.metadata(w, r, d, trackID)
	case "cues":
		s.cues(w, r, d, trackID)
	case "beatgrid":
		s.beatgrid(w, r, d, trackID)
	default:
		http.NotFound(w, r)
	}
}

func (s *server) beatgrid(w http.ResponseWriter, r *http.Request, d drive, trackID int64) {
	if r.Method != http.MethodPost { http.Error(w, "method not allowed", http.StatusMethodNotAllowed); return }
	r.Body = http.MaxBytesReader(w, r.Body, 16<<10)
	var input beatgridRequest
	if err := json.NewDecoder(r.Body).Decode(&input); err != nil || math.IsNaN(input.BPM) || math.IsInf(input.BPM, 0) || input.BPM < 20 || input.BPM > 400 || input.OffsetMS < -30000 || input.OffsetMS > 30000 {
		http.Error(w, "invalid beatgrid", http.StatusBadRequest); return
	}
	backup, err := s.backupLibrary(r, d.db, r.URL.Query().Get("drive"))
	if err != nil { http.Error(w, "backup failed: "+err.Error(), http.StatusServiceUnavailable); return }
	dsn := (&url.URL{Scheme: "file", Path: d.db, RawQuery: "mode=rw&_pragma=busy_timeout(5000)"}).String()
	db, err := sql.Open("sqlite", dsn)
	if err != nil { http.Error(w, "library write open failed", http.StatusServiceUnavailable); return }
	defer db.Close(); db.SetMaxOpenConns(1)
	tx, err := db.BeginTx(r.Context(), nil)
	if err != nil { http.Error(w, "library busy", http.StatusConflict); return }
	var current []byte
	if err = tx.QueryRowContext(r.Context(), `SELECT beatData FROM PerformanceData WHERE trackId=?`, trackID).Scan(&current); err != nil { _ = tx.Rollback(); http.Error(w, "beatgrid unavailable", http.StatusNotFound); return }
	updated, err := replaceBeatgrid(current, input.BPM, input.OffsetMS)
	if err != nil { _ = tx.Rollback(); http.Error(w, "beatgrid format rejected: "+err.Error(), http.StatusUnprocessableEntity); return }
	if _, err = tx.ExecContext(r.Context(), `UPDATE PerformanceData SET beatData=? WHERE trackId=?`, updated, trackID); err == nil {
		_, err = tx.ExecContext(r.Context(), `UPDATE Track SET isPerfomanceDataOfPackedTrackChanged=1,lastEditTime=? WHERE id=?`, time.Now().Unix(), trackID)
	}
	if err != nil { _ = tx.Rollback(); http.Error(w, "beatgrid update failed", http.StatusInternalServerError); return }
	if err = tx.Commit(); err != nil { http.Error(w, "beatgrid commit failed", http.StatusInternalServerError); return }
	writeJSON(w, map[string]any{"trackId": trackID, "bpm": input.BPM, "offsetMs": input.OffsetMS, "backup": backup})
}

type rawMarker struct { offset float64; beat int64; beats int32; unknown int32 }

func replaceBeatgrid(blob []byte, bpm float64, offsetMS int64) ([]byte, error) {
	raw, err := inflateQtBlob(blob, 1<<20)
	if err != nil || len(raw) < 17 { return nil, errors.New("invalid beatgrid blob") }
	rate := math.Float64frombits(binary.BigEndian.Uint64(raw[:8]))
	if !isFinite(rate) || rate <= 0 { return nil, errors.New("invalid sample rate") }
	position := 17
	readMarkers := func() ([]rawMarker, error) {
		if position+8 > len(raw) { return nil, errors.New("truncated marker count") }
		count := int(binary.BigEndian.Uint64(raw[position:position+8])); position += 8
		if count < 0 || count > 10000 || position+count*24 > len(raw) { return nil, errors.New("invalid marker count") }
		markers := make([]rawMarker, count)
		for index := range markers {
			markers[index] = rawMarker{math.Float64frombits(binary.LittleEndian.Uint64(raw[position:position+8])), int64(binary.LittleEndian.Uint64(raw[position+8:position+16])), int32(binary.LittleEndian.Uint32(raw[position+16:position+20])), int32(binary.LittleEndian.Uint32(raw[position+20:position+24]))}
			position += 24
		}
		return markers, nil
	}
	defaults, err := readMarkers(); if err != nil { return nil, err }
	adjusted, err := readMarkers(); if err != nil { return nil, err }
	tail := append([]byte(nil), raw[position:]...)
	selected := adjusted; if len(selected) == 0 { selected = defaults }
	if len(selected) == 0 { return nil, errors.New("no markers") }
	anchor := selected[0]
	newAnchor := anchor.offset + float64(offsetMS)*rate/1000.0
	newAdjusted := make([]rawMarker, len(selected))
	for index, marker := range selected {
		marker.offset = newAnchor + float64(marker.beat-anchor.beat)*60.0/bpm*rate
		newAdjusted[index] = marker
	}
	var output bytes.Buffer
	header := append([]byte(nil), raw[:17]...); header[16] = 1; output.Write(header)
	writeMarkers := func(markers []rawMarker) {
		_ = binary.Write(&output, binary.BigEndian, uint64(len(markers)))
		for _, marker := range markers {
			_ = binary.Write(&output, binary.LittleEndian, marker.offset); _ = binary.Write(&output, binary.LittleEndian, marker.beat)
			_ = binary.Write(&output, binary.LittleEndian, marker.beats); _ = binary.Write(&output, binary.LittleEndian, marker.unknown)
		}
	}
	writeMarkers(defaults); writeMarkers(newAdjusted); output.Write(tail)
	return compressQtBlob(output.Bytes()), nil
}

func isFinite(value float64) bool { return !math.IsNaN(value) && !math.IsInf(value, 0) }

func (s *server) cues(w http.ResponseWriter, r *http.Request, d drive, trackID int64) {
	if r.Method != http.MethodPost { http.Error(w, "method not allowed", http.StatusMethodNotAllowed); return }
	r.Body = http.MaxBytesReader(w, r.Body, 64<<10)
	var input cuesRequest
	if err := json.NewDecoder(r.Body).Decode(&input); err != nil { http.Error(w, "invalid cues", http.StatusBadRequest); return }
	seen := make(map[int]bool)
	for _, cue := range input.Cues {
		if cue.Number < 1 || cue.Number > 8 || seen[cue.Number] || len([]byte(cue.Label)) > 255 || cue.SampleOffset < 0 || !safeCueColor.MatchString(cue.Color) {
			http.Error(w, "invalid cue", http.StatusBadRequest); return
		}
		seen[cue.Number] = true
	}
	backup, err := s.backupLibrary(r, d.db, r.URL.Query().Get("drive"))
	if err != nil { http.Error(w, "backup failed: "+err.Error(), http.StatusServiceUnavailable); return }
	dsn := (&url.URL{Scheme: "file", Path: d.db, RawQuery: "mode=rw&_pragma=busy_timeout(5000)"}).String()
	db, err := sql.Open("sqlite", dsn)
	if err != nil { http.Error(w, "library write open failed", http.StatusServiceUnavailable); return }
	defer db.Close(); db.SetMaxOpenConns(1)
	tx, err := db.BeginTx(r.Context(), nil)
	if err != nil { http.Error(w, "library busy", http.StatusConflict); return }
	var current []byte
	if err = tx.QueryRowContext(r.Context(), `SELECT quickCues FROM PerformanceData WHERE trackId=?`, trackID).Scan(&current); err != nil { _ = tx.Rollback(); http.Error(w, "quick cues unavailable", http.StatusNotFound); return }
	updated, err := replaceQuickCues(current, input.Cues)
	if err != nil { _ = tx.Rollback(); http.Error(w, "quick cues format rejected: "+err.Error(), http.StatusUnprocessableEntity); return }
	if _, err = tx.ExecContext(r.Context(), `UPDATE PerformanceData SET quickCues=? WHERE trackId=?`, updated, trackID); err == nil {
		_, err = tx.ExecContext(r.Context(), `UPDATE Track SET isPerfomanceDataOfPackedTrackChanged=1,lastEditTime=? WHERE id=?`, time.Now().Unix(), trackID)
	}
	if err != nil { _ = tx.Rollback(); http.Error(w, "cue update failed", http.StatusInternalServerError); return }
	if err = tx.Commit(); err != nil { http.Error(w, "cue commit failed", http.StatusInternalServerError); return }
	writeJSON(w, map[string]any{"trackId": trackID, "cueCount": len(input.Cues), "backup": backup})
}

type rawCue struct { label []byte; offset float64; unknown byte; red, green, blue byte }

func inflateQtBlob(blob []byte, limit int64) ([]byte, error) {
	if len(blob) < 6 { return nil, errors.New("blob too short") }
	expected := binary.BigEndian.Uint32(blob[:4])
	if int64(expected) > limit { return nil, errors.New("blob too large") }
	reader, err := zlib.NewReader(bytes.NewReader(blob[4:]))
	if err != nil { return nil, err }
	raw, err := io.ReadAll(io.LimitReader(reader, limit+1)); reader.Close()
	if err != nil || len(raw) != int(expected) { return nil, errors.New("invalid compressed length") }
	return raw, nil
}

func compressQtBlob(raw []byte) []byte {
	var compressed bytes.Buffer
	_ = binary.Write(&compressed, binary.BigEndian, uint32(len(raw)))
	zw := zlib.NewWriter(&compressed); _, _ = zw.Write(raw); _ = zw.Close()
	return compressed.Bytes()
}

func replaceQuickCues(blob []byte, requested []cueInput) ([]byte, error) {
	raw, err := inflateQtBlob(blob, 1<<20)
	if err != nil || len(raw) < 8 { return nil, errors.New("invalid compressed length") }
	count := int(binary.BigEndian.Uint64(raw[:8]))
	if count < 0 || count > 64 { return nil, errors.New("invalid cue count") }
	position := 8
	existing := make([]rawCue, count)
	for index := 0; index < count; index++ {
		if position >= len(raw) { return nil, errors.New("truncated label") }
		length := int(raw[position]); position++
		if position+length+12 > len(raw) { return nil, errors.New("truncated cue") }
		existing[index] = rawCue{append([]byte(nil), raw[position:position+length]...), math.Float64frombits(binary.BigEndian.Uint64(raw[position+length:position+length+8])), raw[position+length+8], raw[position+length+9], raw[position+length+10], raw[position+length+11]}
		position += length + 12
	}
	tail := append([]byte(nil), raw[position:]...)
	for len(existing) < 8 { existing = append(existing, rawCue{offset: -1, unknown: 255}) }
	byNumber := make(map[int]cueInput)
	for _, cue := range requested { byNumber[cue.Number] = cue }
	for index := 0; index < 8; index++ {
		if cue, ok := byNumber[index+1]; ok {
			color, _ := strconv.ParseUint(strings.TrimPrefix(cue.Color, "#"), 16, 24)
			existing[index].label = []byte(cue.Label); existing[index].offset = cue.SampleOffset
			existing[index].red, existing[index].green, existing[index].blue = byte(color>>16), byte(color>>8), byte(color)
		} else { existing[index].label = nil; existing[index].offset = -1; existing[index].red, existing[index].green, existing[index].blue = 0, 0, 0 }
	}
	var uncompressed bytes.Buffer
	_ = binary.Write(&uncompressed, binary.BigEndian, uint64(len(existing)))
	for _, cue := range existing {
		uncompressed.WriteByte(byte(len(cue.label))); uncompressed.Write(cue.label)
		_ = binary.Write(&uncompressed, binary.BigEndian, cue.offset)
		uncompressed.Write([]byte{cue.unknown, cue.red, cue.green, cue.blue})
	}
	uncompressed.Write(tail)
	return compressQtBlob(uncompressed.Bytes()), nil
}

func (s *server) metadata(w http.ResponseWriter, r *http.Request, d drive, trackID int64) {
	if r.Method == http.MethodGet || r.Method == http.MethodHead {
		db, _, err := openLibrary(r, d.db)
		if err != nil { http.Error(w, err.Error(), http.StatusServiceUnavailable); return }
		defer db.Close()
		var out metadataResponse
		out.TrackID = trackID
		err = db.QueryRowContext(r.Context(), `SELECT coalesce(title,''),coalesce(artist,''),coalesce(album,''),coalesce(genre,''),coalesce(comment,''),coalesce(label,''),coalesce(remixer,''),coalesce(rating,0) FROM Track WHERE id=?`, trackID).
			Scan(&out.Title, &out.Artist, &out.Album, &out.Genre, &out.Comment, &out.Label, &out.Remixer, &out.Rating)
		if errors.Is(err, sql.ErrNoRows) { http.Error(w, "track not found", http.StatusNotFound); return }
		if err != nil { http.Error(w, "metadata query failed", http.StatusInternalServerError); return }
		writeJSON(w, out)
		return
	}
	if r.Method != http.MethodPost { http.Error(w, "method not allowed", http.StatusMethodNotAllowed); return }
	r.Body = http.MaxBytesReader(w, r.Body, 64<<10)
	var input metadataResponse
	if err := json.NewDecoder(r.Body).Decode(&input); err != nil { http.Error(w, "invalid metadata", http.StatusBadRequest); return }
	if input.TrackID != 0 && input.TrackID != trackID { http.Error(w, "track id mismatch", http.StatusBadRequest); return }
	if strings.TrimSpace(input.Title) == "" || input.Rating < 0 || input.Rating > 100 || input.Rating%20 != 0 { http.Error(w, "invalid title or rating", http.StatusBadRequest); return }
	for _, value := range []string{input.Title, input.Artist, input.Album, input.Genre, input.Comment, input.Label, input.Remixer} {
		if len(value) > 2048 { http.Error(w, "metadata value too long", http.StatusBadRequest); return }
	}
	backup, err := s.backupLibrary(r, d.db, r.URL.Query().Get("drive"))
	if err != nil { http.Error(w, "backup failed: "+err.Error(), http.StatusServiceUnavailable); return }
	dsn := (&url.URL{Scheme: "file", Path: d.db, RawQuery: "mode=rw&_pragma=busy_timeout(5000)"}).String()
	db, err := sql.Open("sqlite", dsn)
	if err != nil { http.Error(w, "library write open failed", http.StatusServiceUnavailable); return }
	defer db.Close(); db.SetMaxOpenConns(1)
	tx, err := db.BeginTx(r.Context(), nil)
	if err != nil { http.Error(w, "library busy", http.StatusConflict); return }
	result, err := tx.ExecContext(r.Context(), `UPDATE Track SET title=?,artist=?,album=?,genre=?,comment=?,label=?,remixer=?,rating=?,lastEditTime=? WHERE id=?`,
		strings.TrimSpace(input.Title), input.Artist, input.Album, input.Genre, input.Comment, input.Label, input.Remixer, input.Rating, time.Now().Unix(), trackID)
	if err == nil { var changed int64; changed, err = result.RowsAffected(); if err == nil && changed != 1 { err = errors.New("track not found") } }
	if err != nil { _ = tx.Rollback(); http.Error(w, "metadata update failed", http.StatusInternalServerError); return }
	if err = tx.Commit(); err != nil { http.Error(w, "metadata commit failed", http.StatusInternalServerError); return }
	input.TrackID, input.Backup = trackID, backup
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, input)
}

func (s *server) backupLibrary(r *http.Request, path, driveName string) (string, error) {
	s.backupMu.Lock()
	defer s.backupMu.Unlock()
	if !safeDriveName.MatchString(driveName) { return "", errors.New("invalid drive") }
	if existing := s.backups[driveName]; existing != "" {
		if _, err := os.Stat(existing); err == nil { return existing, nil }
	}
	dir := filepath.Join(filepath.Dir(path), "CompanionBackups")
	if err := os.MkdirAll(dir, 0755); err != nil { return "", err }
	name := fmt.Sprintf("m-%s-%s.db", driveName, time.Now().UTC().Format("20060102T150405.000000000Z"))
	destination := filepath.Join(dir, name)
	dsn := (&url.URL{Scheme: "file", Path: path, RawQuery: "mode=ro&_pragma=busy_timeout(5000)"}).String()
	db, err := sql.Open("sqlite", dsn)
	if err != nil { return "", err }
	defer db.Close()
	db.SetMaxOpenConns(1)
	var major, minor, patch int
	if err = db.QueryRowContext(r.Context(), `SELECT schemaVersionMajor,schemaVersionMinor,schemaVersionPatch FROM Information LIMIT 1`).Scan(&major, &minor, &patch); err != nil { return "", err }
	if fmt.Sprintf("%d.%d.%d", major, minor, patch) != "3.0.2" { return "", errors.New("unsupported schema") }
	quoted := strings.ReplaceAll(destination, "'", "''")
	if _, err = db.ExecContext(r.Context(), "VACUUM INTO '"+quoted+"'"); err != nil { return "", err }
	checkDSN := (&url.URL{Scheme: "file", Path: destination, RawQuery: "mode=ro&_pragma=query_only(1)&_pragma=busy_timeout(5000)"}).String()
	checkDB, err := sql.Open("sqlite", checkDSN)
	if err != nil { _ = os.Remove(destination); return "", err }
	var integrity string
	err = checkDB.QueryRowContext(r.Context(), `PRAGMA integrity_check`).Scan(&integrity)
	checkDB.Close()
	if err != nil || integrity != "ok" { _ = os.Remove(destination); return "", errors.New("backup integrity check failed") }
	s.backups[driveName] = destination
	pruneBackups(dir, 5)
	return destination, nil
}

func pruneBackups(dir string, keep int) {
	entries, err := os.ReadDir(dir)
	if err != nil { return }
	type backupFile struct { path string; modified time.Time }
	files := make([]backupFile, 0, len(entries))
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasPrefix(entry.Name(), "m-") || !strings.HasSuffix(entry.Name(), ".db") { continue }
		if info, err := entry.Info(); err == nil { files = append(files, backupFile{filepath.Join(dir, entry.Name()), info.ModTime()}) }
	}
	sort.Slice(files, func(i, j int) bool { return files[i].modified.After(files[j].modified) })
	if len(files) > keep {
		for _, file := range files[keep:] { _ = os.Remove(file.path) }
	}
}

func (s *server) analysis(w http.ResponseWriter, r *http.Request, d drive, trackID int64) {
	db, version, err := openLibrary(r, d.db)
	if err != nil {
		http.Error(w, err.Error(), http.StatusServiceUnavailable)
		return
	}
	defer db.Close()
	result := analysisResponse{TrackID: trackID, SchemaVersion: version}
	err = db.QueryRowContext(r.Context(), `SELECT beatData,quickCues,loops,overviewWaveFormData FROM PerformanceData WHERE trackId=?`, trackID).
		Scan(&result.BeatData, &result.QuickCues, &result.Loops, &result.OverviewWaveformData)
	if errors.Is(err, sql.ErrNoRows) {
		http.Error(w, "track analysis not found", http.StatusNotFound)
		return
	}
	if err != nil {
		http.Error(w, "track analysis query failed", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, result)
}

func (s *server) audio(w http.ResponseWriter, r *http.Request, d drive, trackID int64) {
	db, _, err := openLibrary(r, d.db)
	if err != nil {
		http.Error(w, err.Error(), http.StatusServiceUnavailable)
		return
	}
	var storedPath string
	err = db.QueryRowContext(r.Context(), `SELECT path FROM Track WHERE id=?`, trackID).Scan(&storedPath)
	db.Close()
	if errors.Is(err, sql.ErrNoRows) {
		http.Error(w, "track not found", http.StatusNotFound)
		return
	}
	if err != nil {
		http.Error(w, "track query failed", http.StatusInternalServerError)
		return
	}
	fullPath, err := safeTrackPath(d.root, storedPath)
	if err != nil {
		http.Error(w, "unsafe track path", http.StatusForbidden)
		return
	}
	file, err := os.Open(fullPath)
	if err != nil {
		http.Error(w, "audio unavailable", http.StatusNotFound)
		return
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil || !info.Mode().IsRegular() {
		http.Error(w, "audio unavailable", http.StatusNotFound)
		return
	}
	if contentType := mime.TypeByExtension(filepath.Ext(fullPath)); contentType != "" {
		w.Header().Set("Content-Type", contentType)
	}
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Accept-Ranges", "bytes")
	http.ServeContent(w, r, filepath.Base(fullPath), info.ModTime(), file)
}

func openLibrary(r *http.Request, path string) (*sql.DB, string, error) {
	if _, err := os.Stat(path); err != nil {
		return nil, "", fmt.Errorf("library unavailable")
	}
	dsn := (&url.URL{Scheme: "file", Path: path, RawQuery: "mode=ro&_pragma=query_only(1)&_pragma=busy_timeout(3000)"}).String()
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, "", fmt.Errorf("library open failed")
	}
	db.SetMaxOpenConns(1)
	var major, minor, patch int
	if err := db.QueryRowContext(r.Context(), `SELECT schemaVersionMajor,schemaVersionMinor,schemaVersionPatch FROM Information LIMIT 1`).Scan(&major, &minor, &patch); err != nil {
		db.Close()
		return nil, "", fmt.Errorf("library schema unavailable")
	}
	version := fmt.Sprintf("%d.%d.%d", major, minor, patch)
	if version != "3.0.2" {
		db.Close()
		return nil, "", fmt.Errorf("unsupported library schema %s", version)
	}
	return db, version, nil
}

func safeTrackPath(root, stored string) (string, error) {
	clean := filepath.Clean(filepath.FromSlash(stored))
	if clean == "." || filepath.IsAbs(clean) || clean == ".." || strings.HasPrefix(clean, ".."+string(os.PathSeparator)) {
		return "", errors.New("invalid relative path")
	}
	rootReal, err := filepath.EvalSymlinks(root)
	if err != nil {
		return "", err
	}
	fullReal, err := filepath.EvalSymlinks(filepath.Join(rootReal, clean))
	if err != nil {
		return "", err
	}
	if fullReal != rootReal && !strings.HasPrefix(fullReal, rootReal+string(os.PathSeparator)) {
		return "", errors.New("path escapes drive")
	}
	return fullReal, nil
}

func writeJSON(w http.ResponseWriter, value any) {
	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(value); err != nil {
		log.Printf("json response: %v", err)
	}
}
