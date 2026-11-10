#!/bin/sh
set -u

out="${1:-/tmp/companion-live-library-diagnose.txt}"
engine_pid="$(pidof EMain 2>/dev/null | awk '{print $1}')"

exec >"$out" 2>&1
echo "=== Engine Companion live-library diagnosis ==="
date -u
echo "engine_pid=${engine_pid:-missing}"

echo "=== Engine processes ==="
ps -eo pid,ppid,comm,args | grep -E 'PID|/usr/Engine|EMain|engine-companion' | grep -v grep || :

echo "=== Engine database descriptors ==="
if [ -n "${engine_pid:-}" ]; then
  ls -l "/proc/$engine_pid/fd" 2>/dev/null | grep -E 'Database2|m\.db|socket:' || :
fi

echo "=== Engine D-Bus owners ==="
busctl --no-pager list 2>/dev/null | grep -Ei 'engine|inmusic|edisks' || :

echo "=== edisksd tree ==="
busctl --no-pager tree io.github.inmusicdev.edisksd 2>&1 || :

echo "=== edisksd introspection ==="
busctl --no-pager introspect io.github.inmusicdev.edisksd / 2>&1 || :

echo "=== system bus policy references ==="
grep -R -n -E 'inmusicdev|edisks|Engine|Library' /etc/dbus-1 /usr/share/dbus-1 2>/dev/null | head -n 300 || :

echo "=== firmware library/import strings ==="
strings /usr/Engine/Engine 2>/dev/null |
  grep -Ei 'RekordBox|Playlist|Library(Model|Controller|Manager|Database|Import)|reload|refresh|rescan|database.*(changed|updated)|media.*(added|mounted)' |
  sort -u | head -n 1200 || :

echo "=== QML library entry points ==="
find /usr/Engine /data/sc5000-custom/bundle/ui -type f \( -name '*.qml' -o -name '*.js' \) -print0 2>/dev/null |
  xargs -0 grep -n -E 'Playlist|Library|reload|refresh|rescan|RekordBox|database' 2>/dev/null |
  head -n 1200 || :

echo "=== recent Engine journal ==="
journalctl -u engine.service --no-pager -n 400 2>/dev/null || :

echo "=== recent edisksd journal ==="
journalctl -u edisksd.service --no-pager -n 300 2>/dev/null || :

echo "=== end ==="
echo "$out"
