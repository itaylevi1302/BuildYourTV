package io.cleanyourtv.example.openmovies;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The provider's answer, as a pure function of the request JSON (docs/specs/provider.md, spec 1). */
final class Availability {
    private Availability() {}

    /** Returns {"v":1,"items":[...]}; items is empty when the film is not one of ours. Throws on malformed JSON. */
    static String answer(String requestJson) throws JSONException {
        JSONObject request = new JSONObject(requestJson);
        JSONArray items = new JSONArray();
        Film f = Catalog.find(request);
        if (f != null) {
            items.put(new JSONObject()
                .put("label", "Watch")
                .put("detail", "CC BY · " + f.quality)
                .put("action", "play")
                .put("uri", f.uri()));
        }
        return new JSONObject().put("v", 1).put("items", items).toString();
    }
}
