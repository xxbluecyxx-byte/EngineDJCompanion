package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestSafeTrackPath(t *testing.T) {
	root := t.TempDir()
	if err := os.MkdirAll(filepath.Join(root, "Music"), 0o755); err != nil { t.Fatal(err) }
	track := filepath.Join(root, "Music", "track.mp3")
	if err := os.WriteFile(track, []byte("audio"), 0o644); err != nil { t.Fatal(err) }
	got, err := safeTrackPath(root, "Music/track.mp3")
	if err != nil { t.Fatal(err) }
	if got != track { t.Fatalf("got %q want %q", got, track) }
	if _, err := safeTrackPath(root, "../secret"); err == nil { t.Fatal("parent traversal accepted") }
}

func TestSafeTrackPathRejectsSymlinkEscape(t *testing.T) {
	root := t.TempDir()
	outside := t.TempDir()
	secret := filepath.Join(outside, "secret.mp3")
	if err := os.WriteFile(secret, []byte("secret"), 0o644); err != nil { t.Fatal(err) }
	if err := os.Symlink(secret, filepath.Join(root, "escape.mp3")); err != nil { t.Fatal(err) }
	if _, err := safeTrackPath(root, "escape.mp3"); err == nil { t.Fatal("symlink escape accepted") }
}
