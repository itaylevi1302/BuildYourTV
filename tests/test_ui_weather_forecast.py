"""CleanYourTV UI: the weather card says when the next rain comes, and shows 5 days as a choice (#59).

From Free My TV feefda1 (PR 428) and 195bb32 (Free My TV PR 232): rainInHours() over the 6 hours the card shows ("Raining now",
"Rain in 3 h"), and one Open-Meteo request with both the hours and the days, so Home Settings → Forecast switches
without a new fetch. Next hours stays the default. This reads the source and the string files.

Run from the repository root: python3 -m unittest discover -s tests -p test_ui_weather_forecast.py -v
"""
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "launcher-ui"
SRC = UI / "app/src/io/cleanyourtv/ui"
RES = UI / "app/res"
NEW_STRINGS = ["weather_rain_now", "weather_rain_in_hours", "weather_day_range", "next_days_suffix_prefix",
               "forecast", "forecast_next_hours", "forecast_next_5_days"]


def read(name):
    return (SRC / name).read_text(encoding="utf-8")


class WeatherForecastTest(unittest.TestCase):
    def test_one_request_to_the_same_host(self):
        data = read("HomeData.java")
        query = re.search(r'WEATHER_QUERY = "([^"]*)"\s*\+\s*"([^"]*)"', data)
        self.assertIsNotNone(query)
        q = query.group(1) + query.group(2)
        self.assertIn("daily=temperature_2m_max,temperature_2m_min,sunrise,sunset,weather_code", q)
        self.assertIn("hourly=temperature_2m,weather_code,is_day", q)
        self.assertIn("forecast_days=6", q)
        self.assertIn("forecast_hours=48", q)
        hosts = set(re.findall(r"https://([a-z.-]+)", data))
        self.assertEqual(hosts, {"api.open-meteo.com", "geocoding-api.open-meteo.com", "api.binance.com"})

    def test_cache_remembers_its_query(self):
        data = read("HomeData.java")
        self.assertIn('WEATHER_QUERY.equals(cache.getString("weatherQuery", ""))', data)
        self.assertIn('.putString("weatherQuery", WEATHER_QUERY)', data)

    def test_rain_codes_and_window(self):
        data = read("HomeData.java")
        self.assertIn("static int rainInHours(Weather w, long now)", data)
        self.assertIn("(code >= 51 && code <= 67) || (code >= 80 && code <= 82) || code >= 95", data)
        # The window is the hours the card shows: parseWeather keeps 6.
        self.assertIn("w.hours.size() < 6", data)
        self.assertIn("w.days.size() < 5", data)

    def test_default_is_next_hours(self):
        prefs = read("Prefs.java")
        self.assertIn('"days".equals(sp().getString("forecast", "hours")) ? "days" : "hours"', prefs)
        example = json.loads((UI / "config.example.json").read_text(encoding="utf-8"))
        self.assertEqual(example.get("forecast"), "hours")

    def test_card_draws_days_only_when_chosen_and_fetched(self):
        view = read("HomeView.java")
        self.assertIn('textDays = Prefs.forecast().equals("days") && !wx.days.isEmpty();', view)
        self.assertIn("if (textDays) {", view)
        self.assertIn("R.string.weather_rain_now", view)
        self.assertIn("R.string.weather_rain_in_hours", view)
        self.assertIn("textRainMinute = -1;", view)

    def test_settings_row_switches_without_a_fetch(self):
        settings = read("SettingsActivity.java")
        rows = dict(re.findall(r"(\w+_ROWS?) = (\d+)", settings[settings.index("CLOCK_ROW ="):]))
        self.assertEqual(int(rows["QUOTES_ROW"]), int(rows["FORECAST_ROW"]) + 1, "quotes follow the forecast row")
        self.assertEqual(int(rows["NOW_PLAYING_ROW"]), int(rows["QUOTES_ROW"]) + 1, "Now Playing follows the quotes row")
        self.assertEqual(int(rows["COIN_ROWS"]), int(rows["NOW_PLAYING_ROW"]) + 1, "the coin rows come last")
        case = settings[settings.index("case FORECAST_ROW:"):]
        case = case[:case.index("default:")]
        self.assertIn("Prefs.setForecast(", case)
        self.assertIn("return;", case)
        self.assertNotIn("settingsChanged", case)

    def test_config_json_key(self):
        self.assertIn('if (o.has("forecast")) Prefs.setForecast(o.getString("forecast"));', read("TvConfig.java"))

    def test_every_locale_has_the_new_strings(self):
        for path in [RES / "values/strings.xml", *sorted(RES.glob("values-*/strings.xml"))]:
            if not (path.parent / "strings.xml").exists() or path.parent.name.startswith("values-v"):
                continue
            text = path.read_text(encoding="utf-8")
            for name in NEW_STRINGS:
                with self.subTest(locale=path.parent.name, name=name):
                    self.assertIn('name="%s"' % name, text)


if __name__ == "__main__":
    unittest.main()
