#!/usr/bin/env bash
# Build the CleanYourTV UI home screen and install it on the TV as the default home screen.
# Same contract as launcher/build-and-install.sh: TV_HOST, STOCK_LAUNCHER, and a rollback when Home does not land.
#
#   TV_HOST=192.168.1.50 launcher-ui/build-and-install.sh                                  # build here, then install
#   TV_HOST=192.168.1.50 APK=~/Downloads/CleanYourTV-UI.apk launcher-ui/build-and-install.sh  # a release APK, no build
#   ... CONFIG=my-config.json ...                                       # and push this TV's config.json at the end
#   ... WATCH_NEXT=1 ...                                                # let Continue Watching read Watch Next
set -euo pipefail
# APK and CONFIG are paths as the caller typed them: resolve them before moving into launcher-ui/.
for v in APK CONFIG; do
  eval "f=\${$v:-}"
  if [ -n "$f" ]; then
    case "$f" in /*|[A-Za-z]:[\\/]*) ;; *) f="$(pwd)/$f" ;; esac
    [ -f "$f" ] || { echo "$v=$f: no such file" >&2; exit 1; }
    eval "$v=\$f"
  fi
done
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0}"
TOOLS="$(cd ../tools && pwd)"
# git-bash/MSYS on Windows: MSYS_NO_PATHCONV=1 below stops /c/Users/... from being converted, and the venv's
# python.exe cannot open such a path, so hand it a C:/Users/... one instead.
if command -v cygpath >/dev/null 2>&1; then TOOLS="$(cygpath -m "$TOOLS")"; fi
PY=$TOOLS/venv/bin/python
[ -x "$PY" ] || PY=$TOOLS/venv/Scripts/python.exe
export MSYS_NO_PATHCONV=1
PKG=io.cleanyourtv.ui
HOME_ACTIVITY=$PKG/.HomeActivity
tv() { "$PY" -I "$TOOLS/tv.py" "$@"; }

MIN_SDK=$(sed -n 's/.*--min-sdk-version \([0-9]\{1,3\}\).*/\1/p' build.sh | head -n 1 || true)
TV_SDK=$(tv 'getprop ro.build.version.sdk' 2>/dev/null | tr -dc '0-9' || true)
if [ -n "$MIN_SDK" ] && [ -n "$TV_SDK" ] && [ "$TV_SDK" -lt "$MIN_SDK" ]; then
  echo "ERROR: this TV runs SDK $TV_SDK and CleanYourTV UI needs SDK $MIN_SDK (Android 9) or newer." >&2
  echo "Nothing was built, pushed or disabled. launcher/ (the original CleanYourTV launcher) runs from SDK 26." >&2
  exit 1
fi

if [ -n "${APK:-}" ]; then
  echo "Installing $APK (no build)."
else
if [ -z "${JAVA_HOME:-}" ]; then
  JH="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
  if [ -n "$JH" ]; then
    case "$("$JH/bin/java" -version 2>&1 || true)" in *'version "17.'*) ;; *) JH="" ;; esac
  fi
  if [ -z "$JH" ] && [ -d /opt/homebrew/opt/openjdk@17 ]; then JH=/opt/homebrew/opt/openjdk@17; fi
  if [ -n "$JH" ]; then export JAVA_HOME="$JH"; fi
fi
./build.sh
APK=CleanYourTV-UI.apk
fi

# Which app owns Home now. A stock launcher this script knows is disabled once ours is running; the original
# CleanYourTV launcher (com.gathin.tvhome) is left installed and enabled, so switching back is one command.
KNOWN_STOCK_LAUNCHERS="com.google.android.tvlauncher com.google.android.apps.tv.launcherx com.google.android.leanbacklauncher com.mitv.tvhome"
HOME_QUERY='cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'
if [ -z "${STOCK_LAUNCHER:-}" ]; then
  HOME_PKG=$(tv "$HOME_QUERY" | tail -n 1 | tr -d '\r' | cut -d/ -f1) || {
    echo "ERROR: could not ask the TV which launcher owns Home. Check TV_HOST and the debugging prompt on the TV," >&2
    echo "or pass STOCK_LAUNCHER=<package> yourself." >&2; exit 1; }
  case " $KNOWN_STOCK_LAUNCHERS " in
    *" $HOME_PKG "*) STOCK_LAUNCHER=$HOME_PKG ;;
    *)
      case "$HOME_PKG" in
        "$PKG") STOCK_LAUNCHER=none; echo "Home already belongs to CleanYourTV UI; nothing will be disabled." ;;
        com.gathin.tvhome) STOCK_LAUNCHER=none; echo "Home is the original CleanYourTV launcher; it stays installed and enabled." ;;
        *)
          echo "ERROR: Home currently resolves to '${HOME_PKG:-nothing}', which is not a launcher this script knows." >&2
          echo "  android                                  = Android's launcher chooser; pick a default on the TV first" >&2
          echo "  com.google.android.tungsten.setupwraith  = Google TV's setup wizard; see README, Troubleshooting" >&2
          echo "Run again with STOCK_LAUNCHER=<package to disable> or STOCK_LAUNCHER=none." >&2; exit 1 ;;
      esac ;;
  esac
fi
echo "Stock launcher: $STOCK_LAUNCHER"
if [ "$STOCK_LAUNCHER" = none ]; then DISABLE_STOCK="echo stock launcher left alone"
else DISABLE_STOCK="pm disable-user --user 0 $STOCK_LAUNCHER"; fi
PREVIOUS_HOME=$(tv "$HOME_QUERY" 2>/dev/null | tail -n 1 | tr -d '\r' || true)

"$PY" -I "$TOOLS/b64push.py" "$APK" /data/local/tmp/cleanyourtv-ui.apk
OUT=$(tv 'pm install -r /data/local/tmp/cleanyourtv-ui.apk; rm -f /data/local/tmp/cleanyourtv-ui.apk')
echo "$OUT"
case "$OUT" in
  *Success*) ;;
  *) echo "ERROR: pm install failed; the current home screen was left untouched." >&2; exit 1 ;;
esac

START_OUT=$(tv "am start -W -n $HOME_ACTIVITY")
echo "$START_OUT"
if ! printf '%s' "$START_OUT" | grep -qF 'Status: ok' || printf '%s' "$START_OUT" | grep -qi '^Error'; then
  echo "ERROR: CleanYourTV UI did not start (see above); the current home screen was left alone." >&2
  exit 1
fi

# TCL blocks background work by default (TclAppBoot); the weather and the picks refresh in the background.
printf '%s\n' \
  "appops set $PKG APP_AUTO_START allow 2>/dev/null; appops set $PKG AUTO_START allow 2>/dev/null; echo autostart-allowed" \
  "cmd package compile -m speed -f $PKG" \
  "$DISABLE_STOCK" \
  "cmd package set-home-activity --user 0 $HOME_ACTIVITY" \
  | tv --batch

query_home() { tv "$HOME_QUERY" 2>/dev/null | tail -n 1 | tr -d '\r' || true; }

rollback_home() {
  if [ "$STOCK_LAUNCHER" != none ]; then tv "pm enable --user 0 $STOCK_LAUNCHER" >/dev/null 2>&1 || true; fi
  if [ -n "$PREVIOUS_HOME" ] && [ "${PREVIOUS_HOME%%/*}" != "$PKG" ]; then
    tv "cmd package set-home-activity --user 0 $PREVIOUS_HOME" >/dev/null 2>&1 || true
  fi
  BACK=$(query_home)
  echo "Home resolves to '${BACK:-nothing}' after the rollback." >&2
}

HOME_NOW=$(query_home)
case "$HOME_NOW" in
  "$PKG"/*) echo "Home now resolves to $HOME_NOW" ;;
  *)
    echo "ERROR: Home resolves to '${HOME_NOW:-nothing}', not to CleanYourTV UI. Rolling back." >&2
    rollback_home || true
    echo "On Google TV this usually means com.google.android.tungsten.setupwraith also claims Home: add it to" >&2
    echo "your debloat list (see README, Troubleshooting), apply the list, then run this script again." >&2
    exit 1 ;;
esac

# The screensaver, on unless SCREENSAVER=0. Android 11 to 12L (SDK 30-32) run it from the main process (ScreensaverAlone).
if [ "${SCREENSAVER:-1}" = 1 ]; then
  case "${TV_SDK:-0}" in 30|31|32) DREAM=$PKG/.ScreensaverAlone ;; *) DREAM=$PKG/.Screensaver ;; esac
  WAS=$(tv 'settings get secure screensaver_components' 2>/dev/null | tr -d '\r' || true)
  tv "settings put secure screensaver_components $DREAM" >/dev/null
  case "$WAS" in
    "$PKG"/*) echo "Screensaver: $DREAM" ;;
    *) echo "Screensaver: $DREAM (was ${WAS:-unset}; put it back with: tools/tv.py 'settings put secure screensaver_components $WAS')" ;;
  esac
fi

# Continue Watching (Home Settings → Top of the screen) reads Android TV's Watch Next list, which needs READ_TV_LISTINGS.
# Only with WATCH_NEXT=1 (WATCH_NEXT=0 takes it back; a reinstall keeps either). A TV that refuses only gets a warning:
# the home screen then says how to grant it.
case "${WATCH_NEXT:-}" in
  1)
    G=$(tv "pm grant $PKG android.permission.READ_TV_LISTINGS 2>&1; echo rc=\$?" 2>&1 | tr -d '\r' || true)
    case "$G" in
      *rc=0*) echo "Continue Watching: READ_TV_LISTINGS granted" ;;
      *) echo "WARNING: the TV did not grant READ_TV_LISTINGS (${G:-no answer}); Continue Watching stays empty." >&2 ;;
    esac ;;
  0)
    tv "pm revoke $PKG android.permission.READ_TV_LISTINGS" >/dev/null 2>&1 || true
    echo "Continue Watching: READ_TV_LISTINGS revoked" ;;
esac

# The remote's app buttons from inside any app, with KEY_SERVICE=1 (once: a reinstall keeps them on). Replacing the
# APK unbinds the service while it stays listed, and Android binds it again only when the list changes, so a listed
# service is cleared and written back, as CleanYourTV's launcher does since its PR #526.
# Not on TCL's Android 11 (SDK 30): there any key filter makes one volume press move two steps and a held key stick,
# even one that passes every key on (Free My TV #325). The service is refused, and taken out when a reinstall finds
# it on; the app buttons still work on CleanYourTV UI's home screen, whose window gets them.
svc=$PKG/.RemoteKeyService
if [ "${TV_SDK:-0}" = 30 ] && tv 'getprop ro.product.manufacturer; getprop ro.product.brand' 2>/dev/null | tr -d '\r' | grep -ix tcl >/dev/null; then
  if [ "${KEY_SERVICE:-0}" = 1 ]; then
    echo "KEY_SERVICE=1 refused: on TCL's Android 11 any key filter makes one volume press move two steps" >&2
    echo "and a held key stick (Free My TV #325). Here the app buttons work on CleanYourTV UI's home screen only." >&2
  fi
  tv "s=\$(settings get secure enabled_accessibility_services); case \"\$s\" in *$svc*) n=\$(echo \"\$s\" | sed 's#$svc##; s#::#:#; s#^:##; s#:\$##'); if [ -n \"\$n\" ]; then settings put secure enabled_accessibility_services \"\$n\"; else settings put secure enabled_accessibility_services ''; settings put secure accessibility_enabled 0; fi; echo remote buttons switched off: TCL Android 11 ;; esac"
elif [ "${KEY_SERVICE:-0}" = 1 ] || tv "settings get secure enabled_accessibility_services" | grep -q "$svc"; then
  tv "s=\$(settings get secure enabled_accessibility_services); case \"\$s\" in *$svc*) settings put secure enabled_accessibility_services ''; settings put secure accessibility_enabled 0; sleep 1; settings put secure enabled_accessibility_services \"\$s\" ;; null|'') settings put secure enabled_accessibility_services $svc ;; *) settings put secure enabled_accessibility_services \"\$s:$svc\" ;; esac; settings put secure accessibility_enabled 1; echo remote buttons enabled"
fi
if [ -n "${CONFIG:-}" ]; then ./config.sh "$CONFIG"; fi
echo "Installed. Press Home on the remote to see it. Back to CleanYourTV's own launcher:"
echo "  TV_HOST=$TV_HOST launcher/build-and-install.sh   (or, if it is still installed: tools/tv.py 'cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity')"
