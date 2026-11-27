# Novel plugins

Hyoukan can run user-installed JavaScript novel sources. A plugin is a single `.js` file that the
app executes in a fresh QuickJS context; it has no filesystem, database or class access, and its
only network entry point is the host object.

## Header

The first lines must carry the identity, so the source list can be built without executing the
file:

```js
// @id example-novels
// @name Example Novels
// @lang en
// @version 1
// @site https://example.com

module.exports = {
  // Page numbers start at 1. Return an array, or an empty array at the end.
  popular: function (page) { return []; },

  // Optional. Omit it to serve without a latest tab.
  latest: function (page) { return []; },

  search: function (query, page) { return []; },

  // One catalogue entry for a detail page.
  manga: function (url) {
    return { title: "...", url: url, author: "...", description: "...", cover: "..." };
  },

  // Chapters, in reading order.
  chapters: function (url) {
    return [{ name: "Chapter 1", url: "https://...", chapterNumber: 1 }];
  },

  // One HTML string, or an array of them.
  text: function (url) {
    return "<html>...</html>";
  },
};
```

## Catalogue entry shape

| Field | Required | Notes |
|-------|----------|-------|
| `title` | yes | |
| `url` | yes | Stable; used as the entry's identity |
| `cover` | no | Absolute URL |
| `author`, `artist`, `description`, `genre` | no | |

## Host API

| Call | Meaning |
|------|---------|
| `host.fetch(url)` | GET, returns the body as a string, or null on failure |
| `host.postJson(url, body)` | POST JSON |
| `host.postForm(url, body)` | POST form-encoded |
| `host.log(message)` | Writes to the app log, prefixed with the plugin id |
| `host.now()` | Current wall-clock milliseconds |

Calls are synchronous. Responses are capped at 8 MiB and the script at 2 MiB.

## Why this is not an LNReader plugin

LNReader plugins depend on asynchronous `fetch` promises and a `cheerio` DOM. The QuickJS
binding bundled with the app exposes neither, so this host exposes a synchronous API instead of
pretending to be compatible. A plugin written here is a small translation of the source logic,
not a drop-in LNReader file.
