package io.cleanyourtv.example.openmovies;

import org.json.JSONObject;

import java.text.Normalizer;
import java.util.Locale;

/** The four films, as static data, and the rules that match a provider request to one of them. */
final class Catalog {
    static final Film[] FILMS = {
        new Film(10378, "tt1254207", "Big Buck Bunny", 2008, 10, "720p", "CC BY 3.0",
            "© 2008 Blender Foundation | www.bigbuckbunny.org",
            "https://archive.org/download/BigBuckBunny/big_buck_bunny_720p_h264.mov",
            "https://archive.org/download/BigBuckBunny_124/Content/big_buck_bunny_720p_surround.mp4"),
        new Film(9761, "tt0807840", "Elephants Dream", 2006, 11, "576p", "CC BY 2.5",
            "© 2006 Blender Foundation / Netherlands Media Art Institute | www.elephantsdream.org",
            "https://download.blender.org/ED/elephantsdream-1024-h264-st-aac.mov",
            "https://download.blender.org/ED/elephantsdream-720-h264-st-aac.mov"),
        new Film(45745, "tt1727587", "Sintel", 2010, 15, "2K", "CC BY 3.0",
            "© Blender Foundation | durian.blender.org",
            "https://archive.org/download/Sintel/sintel-2048-stereo.mp4",
            "https://archive.org/download/Sintel/sintel-2048-stereo_512kb.mp4"),
        new Film(133701, "tt2285752", "Tears of Steel", 2012, 12, "720p", "CC BY 3.0",
            "(CC) Blender Foundation | mango.blender.org",
            "https://download.blender.org/demo/movies/ToS/tears_of_steel_720p.mov",
            "https://archive.org/download/Tears-of-Steel/tears_of_steel_720p.mp4"),
    };

    private Catalog() {}

    static Film byTmdb(long tmdb) {
        for (Film f : FILMS) if (f.tmdb == tmdb) return f;
        return null;
    }

    /** The film a request asks about, or null. Only movies match; ids first, then title and year. */
    static Film find(JSONObject request) {
        if (!"movie".equals(request.optString("type"))) return null;
        JSONObject ids = request.optJSONObject("ids");
        if (ids != null) {
            long tmdb = ids.optLong("tmdb", 0);
            if (tmdb != 0) {
                Film f = byTmdb(tmdb);
                if (f != null) return f;
            }
            String imdb = ids.optString("imdb", "");
            if (!imdb.isEmpty()) {
                for (Film f : FILMS) if (f.imdb.equalsIgnoreCase(imdb)) return f;
            }
        }
        String title = normalize(request.optString("title", ""));
        String original = normalize(request.optString("original_title", ""));
        int year = request.optInt("year", 0);
        for (Film f : FILMS) {
            String name = normalize(f.title);
            boolean named = (!title.isEmpty() && title.equals(name))
                || (!original.isEmpty() && original.equals(name));
            if (named && (year == 0 || Math.abs(year - f.year) <= 1)) return f;
        }
        return null;
    }

    /** Lower case letters and digits only, accents dropped: "Sintel!" and "SINTEL" are the same title. */
    static String normalize(String s) {
        String d = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return d.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
