package main

import (
	"crypto/subtle"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"mime"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	_ "modernc.org/sqlite"
)

const (
	listenAddress = ":8081"
	tokenPath     = "/data/sc5000-custom/state/access-token"
	agentVersion  = 2
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
}

type analysisResponse struct {
	TrackID              int64  `json:"trackId"`
	SchemaVersion        string `json:"schemaVersion"`
	BeatData             []byte `json:"beatData,omitempty"`
	QuickCues            []byte `json:"quickCues,omitempty"`
	Loops                []byte `json:"loops,omitempty"`
	OverviewWaveformData []byte `json:"overviewWaveformData,omitempty"`
}

func main() {
	s := &server{drives: allowedDrives, tokenFile: tokenPath, semaphore: make(chan struct{}, 2)}
	mux := http.NewServeMux()
	mux.HandleFunc("/api/companion/v1/health", s.authorize(s.health))
	mux.HandleFunc("/api/companion/v1/tracks/", s.authorize(s.track))
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
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
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
	writeJSON(w, map[string]any{"service": "engine-companion-agent", "version": agentVersion, "readOnly": true})
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
	default:
		http.NotFound(w, r)
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
