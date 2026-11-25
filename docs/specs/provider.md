# Watch provider contract, spec 1

A watch provider is an Android TV app that tells CleanYourTV UI whether it has a title, and opens it.
CleanYourTV UI shows the answers under "Where to watch", next to the streaming services it already
knows from TMDB. It never plays anything itself and ships no sources: it asks the installed apps the
person allowed, and opens the link the app gave back, inside that app.

This page is the contract. [`docs/providers.md`](../providers.md) is the short version with a
working example.

## 1. Declaring the provider

The app exports a `ContentProvider` with an intent filter and a `meta-data` entry:

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

- `spec`: the highest spec the app speaks. CleanYourTV UI ignores a file without `spec`, or whose
  `spec` is not a whole number of 1 or more. A higher spec than it knows is fine: it still sends the
  requests it speaks (section 6).
- `label`: 1 to 24 characters, the same rule as a module card's label (letters, digits, space and
  `. & + ' -`, no space at either end, at least one letter or digit). It is the name under "Where to
  watch" and in Settings. A label that imitates a streaming service or the launcher ("Netflix",
  "CleanYourTV", "Official") is refused.
- The icon is the app's own launcher icon; there is nothing to declare.

CleanYourTV UI finds providers with `PackageManager.queryIntentContentProviders` for
`io.cleanyourtv.action.WATCH_PROVIDER` (it declares that action in its `<queries>`). Only one
provider per package is used: the first one the system returns. When two providers share a label
(compared ignoring case, spaces and punctuation), the one already allowed keeps it; if neither was
allowed, neither is shown.

## 2. Consent

A provider stays silent until the person allows it. The first time CleanYourTV UI finds a new one, it
asks once, naming the app and what it will learn: "*My TV* wants to appear in Where to watch. It will
learn which titles you open. Allow / Not now". The answer is kept per package; an app update does not
ask again, a reinstall with another signing key does.

Settings → Services lists every provider found, with the streaming services: each can be switched on
or off and moved in the order. Switching one on asks the same question again; Back on the question
records nothing, and it is asked again later. The order decides which provider the title page's main
Watch button uses when more than one has the title.

## 3. Asking: `availability`

When a title page opens (and only then: never in the background, never for a list of titles),
CleanYourTV UI calls every allowed provider at once:

```java
Bundle in = new Bundle();
in.putString("request", requestJson);
Bundle out = resolver.call(Uri.parse("content://" + authority), "availability", null, in);
String responseJson = out == null ? null : out.getString("response");
```

`call` runs on the provider's binder thread (CleanYourTV UI uses an unstable `ContentProviderClient`, so
a provider that crashes cannot take the launcher down); the provider answers from what it already has (a local
index, a cache) or from a quick lookup. CleanYourTV UI waits **1500 ms** per provider; a provider that
is late, throws, or returns `null` is left out of that page. It is asked again the next time the page
opens.

### The request

```json
{
  "v": 1,
  "type": "tv",
  "ids": { "tmdb": 37854, "imdb": "tt0388629", "tvdb": 81797, "wikidata": "Q673" },
  "title": "One Piece",
  "original_title": "ワンピース",
  "year": 1999,
  "season": 1,
  "episode": 3,
  "lang": "pt-BR",
  "region": "BR"
}
```

| Field | Always there | Meaning |
|---|---|---|
| `v` | yes | The spec of this request: `1`. |
| `type` | yes | `movie` or `tv`. |
| `ids.tmdb` | yes | The TMDB id (number). |
| `ids.imdb`, `ids.tvdb`, `ids.wikidata` | when TMDB has them | Other ids, for catalogs that do not use TMDB's. |
| `title` | yes | The title in the person's language. |
| `original_title` | when it differs | The original title. |
| `year` | when known | First release or first air year. |
| `season`, `episode` | only for an episode | Sent when the person picks Watch on an episode of a season's page. Without them, a `tv` request is about the show. |
| `lang`, `region` | yes | The person's language (BCP 47) and TMDB region (ISO 3166-1). |

Nothing else is sent: not the account, not the person's lists, not what they watched. A provider may
match on any of the ids, or on title and year when its catalog has no ids. New fields can appear in a
later spec; a provider ignores fields it does not know.

### The response

```json
{
  "v": 1,
  "items": [
    {
      "label": "Watch",
      "detail": "1080p · Dubbed",
      "uri": "mytv://play?tmdb=37854&s=1&e=3",
      "action": "play",
      "progress": { "position": 1410, "duration": 1440 }
    }
  ]
}
```

| Field | Required | Rule |
|---|---|---|
| `v` | yes | `1`. A response with another `v`, that is not valid JSON, or that is over 32 KB, is dropped. |
| `items` | yes | 0 to 5 entries; more are cut. An empty list means "I don't have it": the provider does not appear. |
| `items[].uri` | yes | What CleanYourTV UI opens (section 4). At most 2048 characters. |
| `items[].label` | no | At most 24 characters; longer is cut. Shown on the dim line with `detail`, under the provider's own label: an item can't take another service's name. |
| `items[].detail` | no | One dim line (quality, audio, source), at most 60 characters; longer is cut. |
| `items[].action` | no | `play` (starts playing) or `details` (opens the title's page in the app). Default `details`. |
| `items[].progress` | no | `position` and `duration` in seconds; CleanYourTV UI may show "Resume from 23:30". Ignored when `position` is not between 0 and `duration`. |

Text is shown as plain text, never as HTML or markup. CleanYourTV UI keeps nothing from a response
after the page closes.

## 4. Opening

When the person picks an item, CleanYourTV UI starts:

```java
new Intent(Intent.ACTION_VIEW, Uri.parse(item.uri)).setPackage(providerPackage)
```

The intent is always pinned to the provider's own package, so a provider can only open itself: an
`https://` link to another app's site, or a scheme another app owns, does not resolve and the item is
dropped. If nothing in the provider resolves the uri, CleanYourTV UI shows "This app couldn't open
the title" and does nothing else.

The app decides what the uri does: play at once, show its page, ask the person to sign in.

## 5. What CleanYourTV UI does not do

- It does not search a provider's catalog, list its titles, or ask about titles the person did not
  open.
- It does not send anything over the network for this: the request goes from app to app on the TV.
- It does not install, update or recommend providers. Providers live in their own repositories and
  are installed by the person, like any other app.

## 6. Versions

| Spec | Changes |
|---|---|
| 1 | First version. |

A later spec only adds fields. A provider that declares `spec="2"` still gets `v: 1` requests from a
CleanYourTV UI that only speaks spec 1, and must answer them.
