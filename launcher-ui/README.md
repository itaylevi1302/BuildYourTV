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
4. Optional, the screensaver and the remote's app buttons from inside any app:
   ```bash
   adb shell settings put secure screensaver_components io.cleanyourtv.ui/.Screensaver   # Android 11: .ScreensaverAlone
   adb shell settings put secure enabled_accessibility_services io.cleanyourtv.ui/.RemoteKeyService
   adb shell settings put secure accessibility_enabled 1
   ```
   The second command replaces any accessibility service already on: check
   `adb shell settings get secure enabled_accessibility_services` first and join them with `:`.
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

## config.json

Each new file is applied once, the next time Home opens, as if those choices were made on the TV; what is changed on
the TV afterwards wins until the next push. Every key is optional, and a value the app does not accept is skipped and
logged, never guessed. [`config.example.json`](config.example.json) has them all.

| Key | Value | Notes |
|---|---|---|
| `tmdb_key` | TMDB API key or read access token | Free at themoviedb.org → Settings → API. The picks, Explore and the TMDB sign-in need it. Can also be typed on the TV under TMDB account. |
| `tmdb_region` | `"auto"` or two letters (`"BR"`, `"US"`) | Which titles are on which streaming app. `auto` is the TV's country. |
| `language` | `auto`, `pt`, `en`, `es`, `de`, `ru`, `fr`, `tr`, `he` | `auto` follows the TV. Hebrew (`he`) also mirrors the home screen right to left. |
| `shelf` | list of packages | The apps on the home screen, in order. |
| `dock_apps` | list of packages | CleanYourTV's name for `shelf`, read when `shelf` is absent, so a theme's `config.json` works. |
| `hidden_apps` | list of packages | Apps never shown. |
| `city` | `{ "name", "latitude", "longitude" }` | The weather and the sunset that turns the theme dark. Without a city there is no weather. |
| `theme` | `auto`, `light`, `dark` | `auto` is dark from sunset to sunrise. |
| `temperature` | `auto`, `C`, `F` | |
| `clock` | `auto`, `24h`, `12h` | |
| `screensaver` | `photos`, `aurora` | |
| `weather` | `true` / `false` | The weather widget. |
| `show_crypto` | `true` / `false` | The quotes widget (Binance). |
| `currency` | three letters (`USD`, `BRL`, `EUR`, `TRY`) | The quotes' currency. |
| `buttons` | `{ "s395": action, "k4062": action }` | A remote button by scancode (`s`) or keycode (`k`), from `tools/tv.py 'getevent -lt'`. |
| `app_keys` | `["scan:395:com.app", "4062:com.app"]` | CleanYourTV's form of `buttons`; long presses (`hold:`) are skipped. |
| `remote` | the contents of a `remote.json` | The remote's buttons: which are the TV's own and which can be changed. See `examples/tcl-55c725/remote.json`. |
| `providers` | `{ "283": "com.app" }` | A TMDB streaming provider id to the app that opens its titles, or `"off"`. |
| `show_all_providers` | `true` / `false` | Explore shows every provider of the region, not only the installed apps'. |
| `app_tiles` | `{ "com.app": { "logo", "color" } }` | A tile for an app without a banner: an `https://` logo or a PNG pushed with `config.sh --file`, and a `#RRGGBB` colour. |

A button's action is a package, `input:<TV input id>`, `live`, `settings`, `home`, `tmdb:explore`, `tmdb:watchlist`,
or `""` for what the button did before.

## More

- [`docs/ui/DESIGN.md`](../docs/ui/DESIGN.md): the design of the home screen.
- [`docs/ui/TMDB.md`](../docs/ui/TMDB.md): what the app uses from TMDB's API, and what comes next.
- [CleanYourTV UI's site](https://matheus-caldeira.github.io/cleanyourtv-ui-web/): the same steps, and a form that writes
  `config.json` for you.
