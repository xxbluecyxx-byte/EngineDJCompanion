package main

import (
	"bytes"
	"compress/zlib"
	"context"
	"database/sql"
	"encoding/binary"
	"io"
	"math"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestPlaylistRevisionStatus(t *testing.T) {
	s := &server{}
	s.playlistRevision.Store(7)
	request := httptest.NewRequest(http.MethodGet, "/playlist-revision", nil)
	response := httptest.NewRecorder()
	s.playlistRevisionStatus(response, request)
	if response.Code != http.StatusOK || response.Body.String() != "{\"revision\":7}\n" {
		t.Fatalf("status=%d body=%q", response.Code, response.Body.String())
	}
}

func TestReplaceQuickCuesPreservesTailAndSlots(t *testing.T) {
	var raw bytes.Buffer
	_ = binary.Write(&raw, binary.BigEndian, uint64(2))
	for _, cue := range []rawCue{{label: []byte("Old 1"), offset: 100, unknown: 7, red: 1, green: 2, blue: 3}, {offset: -1, unknown: 9}} {
		raw.WriteByte(byte(len(cue.label))); raw.Write(cue.label)
		_ = binary.Write(&raw, binary.BigEndian, cue.offset)
		raw.Write([]byte{cue.unknown, cue.red, cue.green, cue.blue})
	}
	tail := []byte("main-cue-tail")
	raw.Write(tail)
	var blob bytes.Buffer
	_ = binary.Write(&blob, binary.BigEndian, uint32(raw.Len()))
	zw := zlib.NewWriter(&blob); _, _ = zw.Write(raw.Bytes()); _ = zw.Close()

	updated, err := replaceQuickCues(blob.Bytes(), []cueInput{{Number: 1, Label: "Drop", SampleOffset: 44100, Color: "#F4D338"}, {Number: 3, Label: "Cue 3", SampleOffset: 88200, Color: "#3D9BFF"}})
	if err != nil { t.Fatal(err) }
	zr, err := zlib.NewReader(bytes.NewReader(updated[4:])); if err != nil { t.Fatal(err) }
	decoded, err := io.ReadAll(zr); _ = zr.Close(); if err != nil { t.Fatal(err) }
	if got := binary.BigEndian.Uint64(decoded[:8]); got != 8 { t.Fatalf("cue count = %d", got) }
	position := 8
	for slot := 1; slot <= 8; slot++ {
		length := int(decoded[position]); position++
		label := string(decoded[position:position+length]); position += length
		offset := math.Float64frombits(binary.BigEndian.Uint64(decoded[position:position+8])); position += 8
		unknown := decoded[position]; position += 4
		if slot == 1 && (label != "Drop" || offset != 44100 || unknown != 7) { t.Fatalf("slot 1 changed incorrectly: %q %f %d", label, offset, unknown) }
		if slot == 2 && (label != "" || offset != -1 || unknown != 9) { t.Fatalf("slot 2 changed incorrectly: %q %f %d", label, offset, unknown) }
		if slot == 3 && (label != "Cue 3" || offset != 88200) { t.Fatalf("slot 3 changed incorrectly: %q %f", label, offset) }
	}
	if !bytes.Equal(decoded[position:], tail) { t.Fatal("main cue tail changed") }
}

func TestReplaceBeatgridMovesAnchorAndChangesTempo(t *testing.T) {
	var raw bytes.Buffer
	_ = binary.Write(&raw, binary.BigEndian, float64(44100))
	_ = binary.Write(&raw, binary.BigEndian, float64(0))
	raw.WriteByte(1)
	_ = binary.Write(&raw, binary.BigEndian, uint64(2))
	for _, marker := range []rawMarker{{offset: 0, beat: 0, beats: 4, unknown: 11}, {offset: 88200, beat: 4, beats: 0, unknown: 12}} {
		_ = binary.Write(&raw, binary.LittleEndian, marker.offset); _ = binary.Write(&raw, binary.LittleEndian, marker.beat)
		_ = binary.Write(&raw, binary.LittleEndian, marker.beats); _ = binary.Write(&raw, binary.LittleEndian, marker.unknown)
	}
	_ = binary.Write(&raw, binary.BigEndian, uint64(0))
	updated, err := replaceBeatgrid(compressQtBlob(raw.Bytes()), 120, 1000)
	if err != nil { t.Fatal(err) }
	decoded, err := inflateQtBlob(updated, 1<<20); if err != nil { t.Fatal(err) }
	position := 17 + 8 + 2*24
	if got := binary.BigEndian.Uint64(decoded[position:position+8]); got != 2 { t.Fatalf("adjusted marker count = %d", got) }
	position += 8
	first := math.Float64frombits(binary.LittleEndian.Uint64(decoded[position:position+8])); position += 24
	second := math.Float64frombits(binary.LittleEndian.Uint64(decoded[position:position+8]))
	if first != 44100 { t.Fatalf("first marker = %f", first) }
	if second != 132300 { t.Fatalf("second marker = %f", second) }
}

func TestQueryObjectsPreservesNamesNullsAndValues(t *testing.T) {
	db, err := sql.Open("sqlite", "file::memory:?cache=shared")
	if err != nil { t.Fatal(err) }
	defer db.Close()
	if _, err = db.Exec(`CREATE TABLE sample(id INTEGER PRIMARY KEY,title TEXT,parent INTEGER); INSERT INTO sample(id,title,parent) VALUES(4,'Techno',NULL)`); err != nil { t.Fatal(err) }
	rows, err := queryObjects(context.Background(), db, `SELECT id,title,parent FROM sample`)
	if err != nil { t.Fatal(err) }
	if len(rows) != 1 || rows[0]["id"] != int64(4) || rows[0]["title"] != "Techno" || rows[0]["parent"] != nil {
		t.Fatalf("unexpected rows: %#v", rows)
	}
}

func TestPlaylistMutationsPreserveChainsAndOriginIdentity(t *testing.T) {
	db, err := sql.Open("sqlite", "file:playlist-test?mode=memory&cache=shared&_pragma=foreign_keys(1)")
	if err != nil { t.Fatal(err) }; defer db.Close(); db.SetMaxOpenConns(1)
	_, err = db.Exec(`
		CREATE TABLE Information(uuid TEXT,lastRekordBoxLibraryImportReadCounter INTEGER);
		CREATE TABLE Playlist(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT,parentListId INTEGER,isPersisted BOOLEAN,nextListId INTEGER,lastEditTime DATETIME,isExplicitlyExported BOOLEAN,UNIQUE(title,parentListId),UNIQUE(parentListId,nextListId));
		CREATE TABLE PlaylistEntity(id INTEGER PRIMARY KEY AUTOINCREMENT,listId INTEGER,trackId INTEGER,databaseUuid TEXT,nextEntityId INTEGER,membershipReference INTEGER,UNIQUE(listId,databaseUuid,trackId),FOREIGN KEY(listId) REFERENCES Playlist(id) ON DELETE CASCADE);
		CREATE TABLE Track(id INTEGER PRIMARY KEY,originDatabaseUuid TEXT,originTrackId INTEGER);
		CREATE TRIGGER trigger_before_insert_List BEFORE INSERT ON Playlist BEGIN UPDATE Playlist SET nextListId=-(1+nextListId) WHERE nextListId=NEW.nextListId AND parentListId=NEW.parentListId; END;
		CREATE TRIGGER trigger_after_insert_List AFTER INSERT ON Playlist BEGIN UPDATE Playlist SET nextListId=NEW.id WHERE nextListId=-(1+NEW.nextListId) AND parentListId=NEW.parentListId; END;
		CREATE TRIGGER trigger_after_delete_List AFTER DELETE ON Playlist BEGIN UPDATE Playlist SET nextListId=OLD.nextListId WHERE nextListId=OLD.id; DELETE FROM Playlist WHERE parentListId=OLD.id; END;
		CREATE TRIGGER trigger_before_delete_PlaylistEntity BEFORE DELETE ON PlaylistEntity BEGIN UPDATE PlaylistEntity SET nextEntityId=OLD.nextEntityId WHERE nextEntityId=OLD.id AND listId=OLD.listId; END;
		INSERT INTO Information VALUES('library-current',0);
		INSERT INTO Playlist(id,title,parentListId,isPersisted,nextListId,isExplicitlyExported) VALUES(2,'First',0,1,0,1);
		INSERT INTO Track VALUES(100,'library-a',7),(101,'library-a',9);`)
	if err != nil { t.Fatal(err) }
	tx, _ := db.Begin(); id := int64(0)
	if err = mutatePlaylist(context.Background(), tx, &id, playlistRequest{Action:"create",Title:"Second"}); err != nil { t.Fatal(err) }
	if err = tx.Commit(); err != nil { t.Fatal(err) }
	var next int64; if err = db.QueryRow(`SELECT nextListId FROM Playlist WHERE id=2`).Scan(&next); err != nil || next != id { t.Fatalf("next=%d err=%v", next, err) }
	tx, _ = db.Begin(); if err = mutatePlaylist(context.Background(), tx, &id, playlistRequest{Action:"replaceTracks",PlaylistID:id,TrackIDs:[]int64{101,100}}); err != nil { t.Fatal(err) }; if err = tx.Commit(); err != nil { t.Fatal(err) }
	rows, err := db.Query(`SELECT trackId,databaseUuid,nextEntityId,membershipReference FROM PlaylistEntity WHERE listId=? ORDER BY id`, id); if err != nil { t.Fatal(err) }; defer rows.Close()
	var gotTracks, gotNext []int64
	for rows.Next() { var track, entityNext, membership int64; var uuid string; if err = rows.Scan(&track,&uuid,&entityNext,&membership); err != nil { t.Fatal(err) }; if uuid != "library-current" || membership != 0 { t.Fatalf("uuid=%q membership=%d",uuid,membership) }; gotTracks=append(gotTracks,track); gotNext=append(gotNext,entityNext) }
	if len(gotTracks)!=2 || gotTracks[0]!=101 || gotTracks[1]!=100 || gotNext[0]==0 || gotNext[1]!=0 { t.Fatalf("tracks=%v next=%v",gotTracks,gotNext) }
	tx, _ = db.Begin(); if err = mutatePlaylist(context.Background(), tx, &id, playlistRequest{Action:"delete",PlaylistID:id}); err != nil { t.Fatal(err) }; if err = tx.Commit(); err != nil { t.Fatal(err) }
	if err = db.QueryRow(`SELECT nextListId FROM Playlist WHERE id=2`).Scan(&next); err != nil || next != 0 { t.Fatalf("repaired next=%d err=%v",next,err) }
}
