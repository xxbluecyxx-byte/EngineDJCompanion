package main

import (
	"bytes"
	"compress/zlib"
	"encoding/binary"
	"io"
	"math"
	"testing"
)

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
