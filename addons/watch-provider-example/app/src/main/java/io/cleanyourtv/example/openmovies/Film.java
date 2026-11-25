package io.cleanyourtv.example.openmovies;

/** One open movie: its ids, how it is shown, its licence and credit, and where its video files are. */
final class Film {
    final long tmdb;
    final String imdb;
    final String title;
    final int year;
    final int minutes;
    final String quality;
    final String licence;
    final String credit;
    /** The primary video URL first, then fallbacks. */
    final String[] sources;

    Film(long tmdb, String imdb, String title, int year, int minutes, String quality,
         String licence, String credit, String... sources) {
        this.tmdb = tmdb;
        this.imdb = imdb;
        this.title = title;
        this.year = year;
        this.minutes = minutes;
        this.quality = quality;
        this.licence = licence;
        this.credit = credit;
        this.sources = sources;
    }

    /** The link CleanYourTV UI opens for this film; PlayerActivity answers it. */
    String uri() {
        return "openmovies://play?tmdb=" + tmdb;
    }
}
