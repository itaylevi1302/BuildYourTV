#!/usr/bin/env bash
# Build Open Movies and install it on the TV.
#   TV_HOST=<tv ip> addons/watch-provider-example/install.sh
set -euo pipefail
: "${TV_HOST:?set TV_HOST to the TV IP address}"
cd "$(dirname "$0")"
TOOLS="$(cd ../../tools && pwd)"
PY=$TOOLS/venv/bin/python
[ -x "$PY" ] || PY=$TOOLS/venv/Scripts/python.exe
export MSYS_NO_PATHCONV=1
PKG=io.cleanyourtv.example.openmovies
# Script paths stay relative: with MSYS_NO_PATHCONV, Git Bash would hand Windows Python a /c/... path.
tv() { "$PY" -I ../../tools/tv.py "$@"; }

[ -f local.properties ] || { [ -n "${ANDROID_HOME:-}" ] && echo "sdk.dir=${ANDROID_HOME//\\//}" > local.properties; } || true
if [ -n "${JAVA_HOME:-}" ]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
./gradlew assembleRelease -q --console=plain

"$PY" -I ../../tools/b64push.py app/build/outputs/apk/release/app-release.apk /data/local/tmp/watch-provider-example.apk
OUT=$(tv 'pm install -r /data/local/tmp/watch-provider-example.apk; rm -f /data/local/tmp/watch-provider-example.apk')
echo "$OUT"
case "$OUT" in *Success*) ;; *) echo "ERROR: pm install failed" >&2; exit 1 ;; esac

tv "am start -n $PKG/.MainActivity >/dev/null; echo opened"
echo "Installed. Open a film's page in CleanYourTV UI and allow Open Movies, or open Open Movies from the apps list."
