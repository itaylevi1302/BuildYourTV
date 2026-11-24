package io.cleanyourtv.example.openmovies;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

public class AvailabilityTest {
    private static JSONObject ask(String json) throws JSONException {
        return new JSONObject(Availability.answer(json));
    }

    private static JSONArray items(String json) throws JSONException {
        return ask(json).getJSONArray("items");
    }

    @Test
    public void everyFilmMatchesItsTmdbId() throws JSONException {
        for (Film f : Catalog.FILMS) {
            JSONArray items = items("{\"v\":1,\"type\":\"movie\",\"ids\":{\"tmdb\":" + f.tmdb + "}}");
            assertEquals(1, items.length());
            assertEquals(f.uri(), items.getJSONObject(0).getString("uri"));
        }
    }

    @Test
    public void answerShape() throws JSONException {
        JSONObject out = ask("{\"v\":1,\"type\":\"movie\",\"ids\":{\"tmdb\":10378}}");
        assertEquals(1, out.getInt("v"));
        JSONObject item = out.getJSONArray("items").getJSONObject(0);
        assertEquals("play", item.getString("action"));
        assertTrue(item.getString("label").length() <= 24);
        assertTrue(item.getString("detail").length() <= 60);
    }

    @Test
    public void tvShowsNeverMatch() throws JSONException {
        assertEquals(0, items("{\"v\":1,\"type\":\"tv\",\"ids\":{\"tmdb\":10378}}").length());
    }

    @Test
    public void unknownFilmGivesNoItems() throws JSONException {
        assertEquals(0, items("{\"v\":1,\"type\":\"movie\",\"ids\":{\"tmdb\":1}}").length());
    }

    @Test
    public void imdbOnlyMatches() throws JSONException {
        assertEquals(1, items("{\"v\":1,\"type\":\"movie\",\"ids\":{\"imdb\":\"TT1727587\"}}").length());
    }

    @Test
    public void titleAndYearMatchWithinOne() throws JSONException {
        assertEquals(1, items("{\"v\":1,\"type\":\"movie\",\"title\":\"SINTEL\",\"year\":2011}").length());
        assertEquals(0, items("{\"v\":1,\"type\":\"movie\",\"title\":\"SINTEL\",\"year\":2015}").length());
        assertEquals(1, items("{\"v\":1,\"type\":\"movie\",\"title\":\"Sintel\"}").length());
    }

    @Test
    public void originalTitleMatches() throws JSONException {
        assertEquals(1, items("{\"v\":1,\"type\":\"movie\",\"title\":\"Elefante\",\"original_title\":\"Elephants Dream\",\"year\":2006}").length());
    }

    @Test
    public void newerVersionAndUnknownFieldsAreAccepted() throws JSONException {
        assertEquals(1, items("{\"v\":2,\"type\":\"movie\",\"ids\":{\"tmdb\":9761},\"extra\":true}").length());
    }

    @Test(expected = JSONException.class)
    public void malformedJsonThrows() throws JSONException {
        Availability.answer("not json");
    }
}
