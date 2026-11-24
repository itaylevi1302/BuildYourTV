# Making a watch provider

A watch provider is a normal Android TV app that tells CleanYourTV UI "I have this title, open it
with this link". Your app then appears under "Where to watch" on a title's page, next to Netflix,
Prime Video and the other services TMDB knows about. A home media server, a library app, a TV
operator's app: anything that can answer "do you have TMDB 1368337?" fits.

CleanYourTV UI ships no sources and plays nothing. It asks the apps the person allowed, and opens the
answer inside that app. Your repo, your licence, your signing key; the person sideloads your APK like
any other app.

The contract is [`docs/specs/provider.md`](specs/provider.md). This page is the short version. A
complete example lives in `addons/watch-provider-example/` (arrives with #107; linked once it exists).

## 1. Declare the provider

```xml
<provider
    android:name=".WatchProvider"
    android:authorities="com.example.mytv.watch"
    android:exported="true">
    <intent-filter>
        <action android:name="io.cleanyourtv.action.WATCH_PROVIDER" />
    </intent-filter>
    <meta-data android:name="io.cleanyourtv.watch_provider" android:resource="@xml/watch_provider" />
</provider>
```

`res/xml/watch_provider.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<watch-provider spec="1" label="My TV" />
```

The label is the name shown under "Where to watch": 1 to 24 characters, and not a streaming service's
name.

## 2. Answer `availability`

```java
public class WatchProvider extends ContentProvider {
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!"availability".equals(method) || extras == null) return null;
        try {
            JSONObject req = new JSONObject(extras.getString("request", "{}"));
            int tmdb = req.getJSONObject("ids").getInt("tmdb");
            String type = req.getString("type");            // "movie" or "tv"

            JSONArray items = new JSONArray();
            if (myCatalogHas(type, tmdb)) {
                items.put(new JSONObject()
                        .put("label", "Watch")
                        .put("detail", "1080p")
                        .put("action", "play")
                        .put("uri", "mytv://play?tmdb=" + tmdb + "&type=" + type));
            }
            Bundle out = new Bundle();
            out.putString("response", new JSONObject().put("v", 1).put("items", items).toString());
            return out;
        } catch (Exception e) {
            return null;    // left out of this page; asked again next time
        }
    }
    // query, insert, update, delete, getType: not used, return null or 0
}
```

- Answer within **1.5 seconds**. Look things up in a local index or cache; do not start a slow scan.
- An empty `items` list means "I don't have it". Your app then does not appear on that page.
- The request also carries IMDb, TVDB and Wikidata ids when TMDB has them, the title, original title
  and year, and `season`/`episode` when the person is on an episode. Match on whatever your catalog
  knows.

## 3. Open your own link

CleanYourTV UI opens the `uri` with `ACTION_VIEW`, pinned to your package. Handle it with an
ordinary intent filter:

```xml
<activity android:name=".PlayerActivity" android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:scheme="mytv" android:host="play" />
    </intent-filter>
</activity>
```

Because the intent is pinned to your package, a link to another app does nothing: you can only open
yourself.

## 4. Try it

1. Install your APK on the TV, open a title's page in CleanYourTV UI, and allow your app when it
   asks.
2. Open a title your app has: it appears under "Where to watch", with your label and detail line.
3. Settings → Services shows your app with the streaming services; switch it on or off, or move it
   with ← →, there.

## What the person sees and allows

The first time CleanYourTV UI finds your app, it asks: "*My TV* wants to appear in Where to watch. It
will learn which titles you open." Nothing is sent before they allow it, and only the title they
opened is sent, never their account or lists.
