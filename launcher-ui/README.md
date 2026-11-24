# CleanYourTV UI: build, install and configure

One APK serves every TV. Nothing about a TV is built into it: the TMDB key, the city, the language, the shelf and the
remote's buttons come afterwards, from a `config.json` pushed over ADB or from Settings on the TV. The original
CleanYourTV launcher works the same way (its `config.json` lies over `config.xml`), and this one reads that launcher's
`dock_apps`, `hidden_apps` and `app_keys` too, so a theme's `config.json` can be pushed as is.

There are two ways to get it on a TV. Either way, debloat the TV first with
[CleanYourTV's Quick start](https://github.com/Gathin23/cleanyourtv#quick-start) or keep it as it is: the home screen works on both.

## From the release APK

You need ADB (Android's platform-tools) on a computer on the same Wi-Fi as the TV, and the TV with **Network debugging**
on (Settings → Device Preferences → About → press OK on **Build** seven times → Developer options).

1. Download `CleanYourTV-UI-<version>.apk` from the [releases](https://github.com/matheus-caldeira/cleanyourtv-ui/releases)
   and check it against the `.sha256` beside it.
2. Install it and make it Home:
   ```bash
   adb connect 192.168.1.50
   adb install -r CleanYourTV-UI-1.1.0.apk
   adb shell cmd package set-home-activity --user 0 io.cleanyourtv.ui/.HomeActivity
   adb shell input keyevent KEYCODE_HOME
   ```
   Your stock launcher stays installed and enabled. Accept "Allow USB debugging?" on the TV the first time.
3. Give it this TV's settings, either on the TV (Settings → Home Settings, and TMDB account to type the key), or all at
   once with a `config.json` (below):
   ```bash
   adb push config.json /data/local/tmp/cyt-ui-config.json
   adb shell 'content write --uri content://io.cleanyourtv.ui.config/config.json < /data/local/tmp/cyt-ui-config.json && rm /data/local/tmp/cyt-ui-config.json'
   adb shell input keyevent KEYCODE_HOME
   ```
4. Optional, the screensaver, and the key service (the remote's app buttons from inside any app, and the sleep timer):
   ```bash
   adb shell settings put secure screensaver_components io.cleanyourtv.ui/.Screensaver   # Android 11 to 12L: .ScreensaverAlone
   adb shell settings put secure enabled_accessibility_services io.cleanyourtv.ui/.RemoteKeyService
   adb shell settings put secure accessibility_enabled 1
   ```
   The second command replaces any accessibility service already on: check
   `adb shell settings get secure enabled_accessibility_services` first and join them with `:`.
   Skip it on TCL TVs with Android 11: there any key filter makes one volume press move two steps and a held key
   stick, so the app buttons work on the home screen only (`build-and-install.sh` refuses `KEY_SERVICE=1` there).
   For "Google Photos" under Settings → Screensaver (Google TV's own screensaver, which must stay enabled), let the app
   switch the screensaver: `adb shell pm grant io.cleanyourtv.ui android.permission.WRITE_SECURE_SETTINGS`.
   Now Playing is off until you allow it: see [Now Playing](#now-playing).
5. Switch Network debugging off again on the TV when you are done: port 5555 is plaintext.

With a clone of this repository, `build-and-install.sh` does steps 2 to 4 with the same safeguards as CleanYourTV's
(it checks Android's version, starts the new Home before touching the old one, and rolls back when Home does not land):

```bash
TV_HOST=192.168.1.50 APK=~/Downloads/CleanYourTV-UI-1.1.0.apk CONFIG=my-config.json KEY_SERVICE=1 launcher-ui/build-and-install.sh
```

A release APK and one you built yourself are signed with different keys: to switch between them, uninstall first
(`adb uninstall io.cleanyourtv.ui`), which also clears the settings made on the TV.

## From the clone

JDK 17 and the Android SDK (build-tools 34+, platform android-34); no Gradle. `tools/` needs its venv
(`python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt`).

```bash
TV_HOST=192.168.1.50 CONFIG=my-config.json KEY_SERVICE=1 launcher-ui/build-and-install.sh
```

| Build | Command | What goes inside |
|---|---|---|
| Personal | `launcher-ui/build.sh` | `config.properties` as the first values (copied from `config.example.properties` the first time), signed with `cleanyourtv-ui.keystore`, created once |
| Release | `RELEASE=1 launcher-ui/build.sh` | Nothing about a TV. `VERSION_NAME`, `VERSION_CODE` and `KEYSTORE` (with `KEYSTORE_PASS`, `KEY_ALIAS`, `KEY_PASS`) as in the script's header |

A personal build still takes `config.json`, and what it sets wins over `config.properties`. The release workflow,
[`release-ui.yml`](../.github/workflows/release-ui.yml), builds the release APK when a `ui-v1.2.0` tag is pushed.

Changing settings later, with the clone:

```bash
TV_HOST=192.168.1.50 launcher-ui/config.sh my-config.json
TV_HOST=192.168.1.50 launcher-ui/config.sh --remote examples/tcl-55c725/remote.json
TV_HOST=192.168.1.50 launcher-ui/config.sh --theme themes/aurora my-config.json
TV_HOST=192.168.1.50 launcher-ui/config.sh --file tomato.png      # a logo for app_tiles
TV_HOST=192.168.1.50 launcher-ui/config.sh --reset
```

## Continue Watching (optional)

Home Settings → **Top of the screen** → *Continue Watching* shows, in place of the picks, what your apps left half-way in
Android TV's own Watch Next list (and in the "Continue Watching" rows some apps publish instead): up to 6 titles, 2 per
app, newest first, with how far you got. OK resumes it in its app (the app's own link; the app itself if that fails).
It's off until you pick it, and the list is read only while it's picked, each time the home screen comes back: nothing
runs in the background. To read other apps' entries Android asks for `READ_TV_LISTINGS`, which only ADB can grant:

```bash
TV_HOST=192.168.1.50 WATCH_NEXT=1 launcher-ui/build-and-install.sh      # with the clone (WATCH_NEXT=0 takes it back)
adb shell pm grant io.cleanyourtv.ui android.permission.READ_TV_LISTINGS     # or by hand
adb shell pm revoke io.cleanyourtv.ui android.permission.READ_TV_LISTINGS    # to take it back
```

What it reads, from the apps' entries only: the app, the title, season and episode, length and position, when it was
last watched, the link that resumes it and the picture's address. The list never leaves the TV. Pictures come from the
TV (`content://`, `android.resource://`) or, where an app left an `https` address instead (Netflix does), from that
address, the app's own server: `https` only, never an address on your home network, pictures only, 4 MB and 10 s at
most, kept in memory and never on disk. A title without a picture shows its app's banner. Without the permission the
home screen says how to give it.

## config.json

Each new file is applied once, the next time Home opens, as if those choices were made on the TV; what is changed on
the TV afterwards wins until the next push. Every key is optional, and a value the app does not accept is skipped and
logged, never guessed. [`config.example.json`](config.example.json) has them all.

| Key | Value | Notes |
|---|---|---|
| `tmdb_key` | TMDB API key or read access token | Free at themoviedb.org → Settings → API. The picks, Explore and the TMDB sign-in need it. Can also be typed on the TV under TMDB account. |
| `tmdb_region` | `"auto"` or two letters (`"BR"`, `"US"`) | Which titles are on which streaming app. `auto` is the TV's country. |
| `language` | `auto`, `pt`, `en`, `es`, `de`, `ru`, `uk`, `fr`, `tr`, `he` | `auto` (default) follows the TV. English is the fallback. Hebrew (`he`) also mirrors the home screen right to left. |
| `shelf` | list of packages | The apps on the home screen, in order. |
| `dock_apps` | list of packages | CleanYourTV's name for `shelf`, read when `shelf` is absent, so a theme's `config.json` works. |
| `shelf_order` | `manual`, `recent` | `manual` (default) keeps the order of `shelf` and Settings → Shelf. `recent` puts the app last opened from the home screen (the shelf, All Apps, a pick's app) first; for that the TV keeps a list of the packages opened, newest first and without times, which never leaves the TV and is deleted by going back to `manual`. Also Settings → Shelf → Shelf order. |
| `hidden_apps` | list of packages | Apps never shown. |
| `show_system_apps` | `true` / `false` | All Apps (and the shelf editor and app pickers) also lists the TV's system apps that have a launcher entry. Off by default; factory menus, `hidden_apps` and the Live TV apps never show. Also Home Settings, System apps in All Apps. |
| `screensaver_ok` | `true` / `false` | OK on the screensaver opens the title on screen in its app (straight to the title when its link is known, else the app's home); any other key still wakes it, and the caption says so. Off by default: the screensaver then wakes on any key, shows no hint and looks up no links. Also Home Settings, OK on the screensaver opens the title. |
| `city` | `{ "name", "latitude", "longitude" }` | The weather and the sunset that turns the theme dark. Without a city there is no weather. |
| `theme` | `auto`, `light`, `dark` | `auto` is dark from sunset to sunrise. |
| `top_of_screen` | `picks`, `continue` | The top of the home screen: TMDB's picks (default) or Continue Watching, which needs `READ_TV_LISTINGS` (see Continue Watching above). Also Home Settings → Top of the screen. |
| `temperature` | `auto`, `C`, `F` | |
| `clock` | `auto`, `24h`, `12h` | |
| `power_on` | `tv`, `home`, `live` | Settings → Home Settings → At power-on. Only after the TV starts from off, never when it wakes from standby (a TV that starts in standby after a power cut acts when the screen first comes on). `tv` (default) changes nothing; `live` opens Live TV 10 s after Home comes up, unless a key is pressed first; `home` brings Home back when another app takes the screen within 5 s of Home first showing, before any key. |
| `forecast` | `hours`, `days` | The weather card: the next 6 hours (default) or the next 5 days. Also Home Settings → Forecast. |
| `screensaver` | `photos`, `aurora`, `google` | `google` is Google TV's own screensaver (a Google Photos album): it needs dreamx enabled and `adb shell pm grant io.cleanyourtv.ui android.permission.WRITE_SECURE_SETTINGS`, or only the choice is saved. |
| `weather` | `true` / `false` | The weather widget. |
| `show_crypto` | `true` / `false` | The quotes widget (Binance). |
| `status_icons` | `true` / `false` | Small icons next to the round buttons at the top: Wi-Fi or Ethernet, and a USB drive (or SD card) when one is mounted. Off by default. No Bluetooth icon: reading it needs the Bluetooth permission, which the app does not ask for. |
| `currency` | three letters (`USD`, `BRL`, `EUR`, `TRY`) | The quotes' currency. |
| `buttons` | `{ "s395": action, "k4062": action }` | A remote button by scancode (`s`) or keycode (`k`), from `tools/tv.py 'getevent -lt'`. |
| `app_keys` | `["scan:395:com.app", "4062:com.app"]` | CleanYourTV's form of `buttons`; long presses (`hold:`) are skipped. |
| `remote` | the contents of a `remote.json` | The remote's buttons: which are the TV's own and which can be changed. See `examples/tcl-55c725/remote.json`. |
| `providers` | `{ "283": "com.app" }` | A TMDB streaming provider id to the app that opens its titles, or `"off"`. |
| `show_all_providers` | `true` / `false` | Explore shows every provider of the region, not only the installed apps'. |
| `app_store_button` | `true` / `false` | A round App Store button at the top right (Google Play, or Amazon Appstore on Fire TV), where the TV has one. Off by default; also added or removed by holding OK on a top button. |
| `app_tiles` | `{ "com.app": { "logo", "color" } }` | A tile for an app without a banner: an `https://` logo or a PNG pushed with `config.sh --file`, and a `#RRGGBB` colour. |

A button's action is a package, `input:<TV input id>`, `input:next`, `live`, `settings`, `home`, `tmdb:explore`,
`tmdb:watchlist`, `inputs` (the TV's inputs panel, or the home screen's drawer on the inputs where nothing answers it),
or `""` for what the button did before.

`live` opens Live TV, or the HDMI input picked on the TV under Settings → Inputs → Live TV button (that row shows where
a remote button runs `live`). Settings → Inputs also picks which inputs the drawer shows, and in what order: OK shows or
hides one, ← → moves it, Live TV included. Until it is used every input shows, in the TV's order; a port the TV lists
later joins at the end, and one it stops listing keeps its place.

## remote.json

A model's remote: `examples/<model>/remote.json`, pushed with `config.sh --remote` (or as config.json's `remote`).
`buttons` lists each button with its `scan` (scancode) or `key` (keycode), a `name`, and `kind`: `native` is never
touched, `configurable` shows in Settings > Remote Buttons with its `default` action. A button is matched by scancode
first, then by keycode. These top-level fields are optional:

| Field | Value | Notes |
|---|---|---|
| `remote` | text | The remote's name, shown on the TV. |
| `vendor` | number (`2391`) or hex string (`"0957"`, `"0x0957"`) | Only keys from the input device with this vendor id are ours; others (a gamepad's or keyboard's `BUTTON_3`, another remote) go on as before. The hex form is the one in the remote's `Vendor_0957_Product_0031.kl`; `tools/tv.py 'getevent -i'` or `dumpsys input` lists it. |
| `product` | same forms (`"0031"`) | The device's product id, with `vendor` or alone. |
| `debounce_ms` | 0 to 5000 | A press of the same button within this many milliseconds of its last press is the same press: the action runs once. For remotes that send one press as a burst (Xiaomi: `1000`). Default 0, off. |

A `vendor` or `product` that is not an id (more than four hex digits, a decimal like `9.5`) makes the whole file
unread, logged as `remote.json not read`, rather than letting every device's keys through.

On Hisense TVs the firmware turns the Input key (`KEYCODE_TV_INPUT`, 178) into the TV's inputs request
(`com.android.tv.action.VIEW_INPUTS`), which only the stock home screen's panel answers: with that home screen
disabled the key did nothing. With the key service on (`KEY_SERVICE=1`) it now opens the home screen with the drawer on
the first input, and only while nothing answers the request (re-enable the stock home and the key is the TV's again).
A row for key 178 decides instead: `configurable` with `"default": "inputs"` gives the same on any TV, and `""` leaves
the key alone on Hisense too; a `native` row does not switch it off.

## Now Playing

Off by default. Once allowed, what an app is playing (its title, the artist or channel, and the app's name) takes the
quotes' place on the home screen and the screensaver while it plays; when it stops, the quotes come back.

Android shares what apps play (their media sessions) only with an app whose notification listener has been allowed,
and such a listener *could* read every notification. So CleanYourTV UI never asks for it on its own: you allow it from
the computer, once (a reinstall keeps it):

```bash
TV_HOST=192.168.1.50 NOW_PLAYING=1 launcher-ui/build-and-install.sh    # NOW_PLAYING=0 takes it back
adb shell cmd notification allow_listener io.cleanyourtv.ui/io.cleanyourtv.ui.NowPlaying    # by hand; disallow_listener takes it back
```

What it reads, and what it doesn't:
- From each app's media session: the title, the subtitle (artist, channel or show) and whether it is playing. The
  app's name comes from the TV's app list.
- It never reads a notification: the listener asks Android to unbind it the moment it connects, so none reaches it.
- It watches only while the home screen or the screensaver is on screen. Nothing runs in the background for it.
- Nothing is stored, logged or sent: what's playing is only drawn on the screen.

Settings → Home Settings → *Now Playing widget* hides or shows it. Hiding it doesn't take the permission back
(only `NOW_PLAYING=0` or `disallow_listener` do). The row says *Not allowed* until the permission is there, and stays
so where the TV refuses it. On Android 11 to 12L the screensaver runs in its own process and picks up that switch
the next time that process starts.

## Sleep timer

Settings → Sleep Timer turns the TV off after 15, 30, 60, 90 or 120 minutes; Off cancels it. A minute before, a small
box over whatever is playing counts down with **15 more minutes**, **Keep the TV on** and **Turn off now**; Back closes
the box and the timer runs on. It needs the key service (`KEY_SERVICE=1`, step 4 above): Android lets no ordinary app
put the TV to sleep, and the service's "lock screen" action (Android 9 and later) does it the way the remote's power key
does. Without the service the row says so. It adds no permission (no overlay, no exact alarm, no boot receiver), so:

- The alarms are inexact. While the TV is on Android normally delivers them on time; Android 12 and later may deliver
  one a few minutes late.
- The timer survives the home screen being closed, and ends when the TV restarts or is turned off some other way.
- The box over a playing app pauses most players, as any window on top does.
- What sleep does is the firmware's: on most TVs the same standby as the power key, and a box behind HDMI sleeps and
  turns the TV off over HDMI-CEC. A firmware that handles its power key its own way may only turn the screen off (see
  "Xiaomi HyperOS 3" in [`docs/TROUBLESHOOTING.md`](../docs/TROUBLESHOOTING.md)).
- Not on TCL's Android 11, where the key service is refused.

To try it, pick 15 min and watch `adb logcat -s CleanYourTV-UI` for "sleep timer: 900 s", then, after the warning box a
minute before the end, "sleep timer: TV off".

## Watch providers

An Android TV app can tell a title's page whether it has that title, and open it: its answer shows under **Where to watch**
next to the streaming services, and the page's Watch button uses it when no streaming app has a link. A provider stays
silent until you allow it: the first time one is found the page asks once, naming the app. Settings → Services lists every
provider found, with OK to switch one on or off and ← → to change the order. The request goes from app to app on the TV
(no network, no new permission), only when a title page opens, and only to the apps you allowed; on a season's page the
Watch pill asks about one episode. The contract is [`docs/specs/provider.md`](../docs/specs/provider.md) and the short
version for app authors is [`docs/providers.md`](../docs/providers.md).

## Translations

Edit the English defaults in [`app/res/values/strings.xml`](app/res/values/strings.xml)
and use the same resource names in each language's `app/res/values-<language>/strings.xml`.
Use Android plurals for counts, and keep format arguments such as `%1$s` in translated strings.

## More

- [`docs/ui/DESIGN.md`](../docs/ui/DESIGN.md): the design of the home screen.
- [`docs/ui/TMDB.md`](../docs/ui/TMDB.md): what the app uses from TMDB's API, and what comes next.
- [CleanYourTV UI's site](https://matheus-caldeira.github.io/cleanyourtv-ui-web/): the same steps, and a form that writes
  `config.json` for you.
