# BuildYourTV

An Apple TV-style home screen for Android TV and Google TV, in Hebrew and right to left. By Itay Levi.

BuildYourTV is built on [CleanYourTV UI](https://github.com/matheus-caldeira/cleanyourtv-ui) by Matheus Caldeira,
which itself builds on [CleanYourTV](https://github.com/Gathin23/cleanyourtv) (the per-model debloat) and
[Free My TV](https://github.com/simbasjungles/free-my-tv). Everything those projects do is still here; this
repository adds the parts below. It was built and tested on a TCL Smart TV Pro (G09), Google TV / Android 12.

## What it adds

| Area | What you get |
|---|---|
| **Hebrew** | Every screen translated, titles and genres from TMDB in Hebrew, dates and weather in Hebrew. |
| **Right to left** | With Hebrew the whole interface is mirrored: the home screen's title, tabs, cards, shelf and drawer, the title pages, Explore and Settings, and the remote's arrows follow. |
| **Continue watching** | The first cards are what you are in the middle of, from Netflix (the TV's Watch Next rows) and Stremio, with a progress bar. OK goes straight back to the episode. |
| **YouTube picks** | YouTube's recommended videos on the first tab, with sharp thumbnails, opening in YouTube. |
| **Screensaver** | Starts on its own on Android 12 too; an aurora that works without Android 13; Google Photos as a third choice. |
| **Settings** | Redesigned and mirrored, with icons and a status block, plus switches for Continue watching, YouTube picks, ad blocking (Private DNS) and the screensaver delay. |
| **From the computer** | Read the settings changed on the TV back into `config.json`, and dump what the TV's apps publish. |

The app's package stays `io.cleanyourtv.ui`, so an existing install updates in place and keeps its settings.

## Build and install

The build and the configuration are the same as CleanYourTV UI's: see [`launcher-ui/README.md`](launcher-ui/README.md).
A few extras need a permission granted once from ADB:

```bash
adb shell pm grant io.cleanyourtv.ui android.permission.READ_TV_LISTINGS        # Continue watching, YouTube picks
adb shell pm grant io.cleanyourtv.ui android.permission.WRITE_SECURE_SETTINGS   # Google Photos screensaver, ad blocking
adb shell appops set io.cleanyourtv.ui WRITE_SETTINGS allow                       # the screensaver delay
```

Set `"language": "he"` in `config.json`, or pick עברית in Settings > Home Settings > Language.

## Credits and licence

- BuildYourTV: Itay Levi, [github.com/itaylevi1302/BuildYourTV](https://github.com/itaylevi1302/BuildYourTV).
- Built on CleanYourTV UI by Matheus Caldeira, CleanYourTV by Gathin, and Free My TV by its authors, all under the
  MIT License. Their notices are kept in [`LICENSE`](LICENSE), [`launcher-ui/LICENSE`](launcher-ui/LICENSE),
  [`launcher-ui/LICENSE.free-my-tv`](launcher-ui/LICENSE.free-my-tv) and [`launcher-ui/NOTICE`](launcher-ui/NOTICE).
- The original READMEs: [`README.cleanyourtv-ui.md`](README.cleanyourtv-ui.md) and [`README.cleanyourtv.md`](README.cleanyourtv.md).
- This product uses the TMDB API but is not endorsed or certified by TMDB.
- Netflix, YouTube, Stremio, Apple TV, Disney+ and the other names belong to their owners; this project is not affiliated with them.
