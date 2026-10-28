# CleanYourTV UI

**English** · [Português](README.pt-BR.md)

An Apple TV-style home screen for Android TV, built on **[CleanYourTV](https://github.com/Gathin23/cleanyourtv)**
(which strips the factory bloat off your TV) with the look of **[Free My TV](https://github.com/simbasjungles/free-my-tv)**.
Browse movies and shows with TMDB, see where each one streams, and open it right in the app.

![Home screen](docs/ui/screenshots/en/home.jpg)

This repository is a **fork of CleanYourTV**: everything the original has is still here, unchanged (the per-model
debloat lists in `examples/`, the scripts in `tools/`, the minimal launcher in `launcher/`). CleanYourTV's README is
**[`README.cleanyourtv.md`](README.cleanyourtv.md)**, kept current with the rest. The fork only **adds** files, and
a workflow brings in CleanYourTV's changes every day ([`sync-upstream.yml`](.github/workflows/sync-upstream.yml)).

## How it differs

| | [CleanYourTV](https://github.com/Gathin23/cleanyourtv) | [Free My TV](https://github.com/simbasjungles/free-my-tv) | **CleanYourTV UI** (this) |
|---|---|---|---|
| What it is | Per-model debloat + a minimal launcher | A good-looking home screen for TVs | All of CleanYourTV + a home screen in Free My TV's style |
| TV debloat | Per-model lists, with backup and restore | — | CleanYourTV's, unchanged |
| Home screen | Dock and grid, offline | Glass shelf, picks, weather | Free My TV's, adapted, plus **Explore** |
| ADB on after install | Not needed | Uses the TV's own ADB to keep Home | Not needed: everything is done once from the computer |
| Browse movies and shows | — | Picks on the home screen | Banner, catalogs, top 10, categories with filters, search, title pages, cast, seasons |
| TMDB account | — | — | QR code sign-in: watchlist, favorites, ratings, lists |
| "Watch on…" | — | Opens the app | Opens **the title** in the app (Netflix, Disney+, Apple TV, Max, Prime) when Wikidata knows its id |
| Remote buttons | `app_keys` in a theme | — | A `remote.json` per model + a settings screen on the TV |
| Configuration | The theme's `config.json` | Settings on the TV | A `config.json` pushed from the computer + settings on the TV |
| Language | English | Several | English and Portuguese, plus Spanish, German, Russian, French and Turkish; follows the TV |
| Cost for an AI assistant to adapt | Low | Medium | **Higher**: more code, network and screens, more tokens |

Just want a clean TV and a minimal home? Use CleanYourTV's `launcher/`. Want the home screen without the debloat? Use
Free My TV. This project is for people who want both and a full Explore.

## Features

**Home screen**
- "For You" with TMDB picks on your services, and Explore, Movies and Shows tabs.
- An app shelf with official logos; holding OK on an app drops a menu from its card: move, logo and color (a PNG's URL
  and a background color, or through `config.json`), the app's Android settings (force stop, clear cache) and close.
- Weather for your city, a theme that darkens at sunset, a photo screensaver.
- No ADB left on: debloat, the Home switch and the remote-button service are done once from the computer.

**Explore** (the ▶ button on the home screen, or a remote button)
- An Apple TV-like banner with the title's logo; its picture melts into the background, which follows the focused poster.
- Rows of up to 10 titles with "See more", which opens the whole list as a grid that loads as you scroll.
- A side menu (Left at the screen's edge): Explore and its settings, Search, Home, My list, then the rest in the order you
  like, each one hideable: Today's top 10, Movies, Shows, Kids, Anime, Categories, saved filters, and one page per
  streaming service on the TV.
- Movies, Shows, Kids and Anime as catalogs: tabs on top, a poster grid below.
- Search and Categories share one filter: type, one or more categories (Anime, K-dramas and Brazilian included) and
  release year; a category filter can be saved to the menu. Recent searches.
- My list as tabs: watchlist, favorites, seen, your ratings.
- Seen titles get a ✓ on the poster; long titles scroll like a ticker on the focused card.
- Title pages: rating and age rating, director, where to stream in your region, watchlist, seen, favorite, rate, lists,
  trailer, seasons and episodes, cast (each person with their page), collection, keywords and reviews.
- Fast and light: TMDB answers cached on disk (lists for 3 h, titles for 12 h, pictures and people for a week; an old
  copy when offline) and images too (up to 160 MB, a week). When an app or the TV is on, the launcher lets go of what it
  kept in memory: on a 2 GB TCL, about 66 MB in the background, down from ~155 MB.
- No ads and no telemetry: it only talks to TMDB, Wikidata (the services' ids) and Open-Meteo (weather), plus Binance
  for the crypto prices when `show_crypto=true` (off by default).

**Remote and configuration**
- `examples/<model>/remote.json` says which remote buttons can be changed; on the TV each one opens an app, an HDMI
  input, Explore or your list.
- `launcher-ui/config.sh` pushes a `config.json` (shelf, hidden apps, city, buttons, services, logos) over ADB, without
  rebuilding. Only the ADB shell can write that configuration into the app.
- CleanYourTV themes: `launcher-ui/config.sh --theme themes/<name> [my-config.json]` takes what both launchers share
  (a theme's dock apps become the shelf, plus hidden apps and remote buttons). The rest of a theme (wallpaper, columns,
  glass) belongs to the original launcher: here the background comes from the picks' photos.

## Screenshots

| | |
|---|---|
| ![Explore](docs/ui/screenshots/en/explore.jpg) | ![Explore's menu](docs/ui/screenshots/en/explore-menu.jpg) |
| ![Anime catalog](docs/ui/screenshots/en/explore-anime.jpg) | ![Today's top 10](docs/ui/screenshots/en/explore-top10.jpg) |
| ![Filters](docs/ui/screenshots/en/explore-filters.jpg) | ![Top 10 movies](docs/ui/screenshots/en/explore-top10-grid.jpg) |
| ![Title page](docs/ui/screenshots/en/title.jpg) | ![An app's menu on the home screen](docs/ui/screenshots/home-app-menu.jpg) |

## Quick start

You need a computer on the same Wi-Fi as the TV and a coding assistant with shell access (Claude Code, Codex or
Gemini CLI), as with CleanYourTV.

| Where | What to do |
|---|---|
| **1. On the TV** | Settings → Device Preferences → About → press OK on **Build** seven times → back → Developer options → **USB debugging** on (and **Network/ADB debugging** if there is one). Keep the TV on. |
| **2. On your computer** | Paste [`PROMPT.md`](PROMPT.md) into your assistant with your TV model, and ask for this fork's home screen in `launcher-ui/` instead of `launcher/`. |
| **3. Follow along** | Accept "Allow USB debugging?" on the TV and answer the checklist: the apps you use, the shelf order, the voice assistant, Chromecast and updates. |

The assistant backs up, debloats, builds and installs the home screen, and checks each step with screenshots from the
TV. The debloat, the supported TVs and how to go back are in [CleanYourTV's README](README.cleanyourtv.md).

## From the release APK

One APK serves every TV: download `CleanYourTV-UI-<version>.apk` from the
[releases](https://github.com/matheus-caldeira/cleanyourtv-ui/releases), install it with `adb install`, and give the TV
its settings (TMDB key, city, shelf, remote buttons) with a `config.json` or in Settings on the TV. No JDK, no Android
SDK, no build. The commands, and every `config.json` key, are in [`launcher-ui/README.md`](launcher-ui/README.md); the
[site](https://matheus-caldeira.github.io/cleanyourtv-ui-web/) has a form that writes `config.json` for you.

## Installing by hand

1. Follow [CleanYourTV's Quick start](README.cleanyourtv.md#quick-start) up to applying your TV's debloat list.
2. Write this TV's settings in a `config.json`: your TMDB key (free, at themoviedb.org → Settings → API), your city,
   the shelf and the remote's buttons. Start from `launcher-ui/config.example.json`; the keys are in
   [`launcher-ui/README.md`](launcher-ui/README.md#configjson). The key belongs to the app and is still needed with the
   QR sign-in: signing in only tells TMDB who you are, for your list and ratings. (`config.properties` still works for
   a personal build, as the first values.)
3. Build and install (JDK 17 and the Android SDK with build-tools 34+ and platform android-34; no Gradle):
   ```bash
   TV_HOST=192.168.1.50 CONFIG=my-config.json KEY_SERVICE=1 launcher-ui/build-and-install.sh
   ```
   `KEY_SERVICE=1` turns on the remote-button service, once: reinstalls keep it on. `SCREENSAVER=0` keeps the
   current screensaver. `APK=<file>` installs a release APK instead of building one.
4. Later changes: `TV_HOST=… launcher-ui/config.sh my-config.json`, and
   `launcher-ui/config.sh --remote examples/<model>/remote.json`.

The script keeps the original's safeguards: it checks Android before building, starts the new Home before touching the
old one, and undoes the switch if it doesn't take. To open Explore from the computer:
`adb shell am start -n io.cleanyourtv.ui/.HomeActivity --es open explore` (or `--es open watchlist`).

## Going back

- To CleanYourTV's launcher: install `launcher/` as in its README.
- To the factory TV: `tools/restore.sh`, as in [CleanYourTV's README](README.cleanyourtv.md#restoring-your-tv).

## CleanYourTV updates

The [`sync-upstream.yml`](.github/workflows/sync-upstream.yml) workflow runs every day and merges CleanYourTV's `main`.
This fork leaves the original's files alone, with one exception: `README.md` is this fork's. On every sync the workflow
copies CleanYourTV's new README to [`README.cleanyourtv.md`](README.cleanyourtv.md) and keeps this fork's. Any other
conflict becomes an issue with the commands to solve it by hand.

CleanYourTV's tests (`tests/`) read its README, so here they run through
[`launcher-ui/test-upstream.sh`](launcher-ui/test-upstream.sh): it copies the repository to a temporary folder, puts
CleanYourTV's README back as `README.md` there, and runs the suite. Your checkout and this README stay as they are.

## License and use

All the code is under the MIT License: CleanYourTV UI's own ([`launcher-ui/LICENSE`](launcher-ui/LICENSE)),
[CleanYourTV](https://github.com/Gathin23/cleanyourtv)'s ([`LICENSE`](LICENSE)) and
[Free My TV](https://github.com/simbasjungles/free-my-tv)'s ([`launcher-ui/LICENSE.free-my-tv`](launcher-ui/LICENSE.free-my-tv)).
You can use, change and share it, commercially too, keeping the copyright notices and licence texts. It comes as is,
without warranty.

The project mixes several things, each with its own terms, which the MIT License does not cover:
- **Online services.** TMDB's free API key and Open-Meteo's free API are for non-commercial use; commercial use is
  licensed by each of them separately. Wikidata's data is CC0; Binance has its own terms.
- **Fonts and icons.** Inter and Outfit (SIL Open Font License 1.1), Material Symbols (Apache 2.0) and the coin logos
  (CC0).
- **Trademarks.** Netflix, YouTube, Prime Video, Disney+, Apple TV, Max, TMDB and the other names and logos belong to
  their owners. This project is not affiliated with any of them, and nothing built on it should look official.

Everything, item by item, is in [`launcher-ui/NOTICE`](launcher-ui/NOTICE). If you use this project, in a service
too, a credit with a link to this repository is welcome (a request, not a condition), so other people can find it for
their own TVs.

## Credits and contact

- [CleanYourTV](https://github.com/Gathin23/cleanyourtv), by Gathin, and [Free My TV](https://github.com/simbasjungles/free-my-tv),
  by its authors, both under the MIT License. Details in [`launcher-ui/NOTICE`](launcher-ui/NOTICE).
- This product uses the TMDB API but is not endorsed or certified by TMDB. Service ids from Wikidata. Weather by
  Open-Meteo.com (CC BY 4.0).
- Matheus Caldeira, [github.com/matheus-caldeira](https://github.com/matheus-caldeira). Questions and problems in the
  [issues](https://github.com/matheus-caldeira/cleanyourtv-ui/issues).
