# app/ Module

The monolithic application module containing UI, domain, data services, and DI wiring. Organized by fork origin (4 package roots), not architectural layer.

## Package roots

| Root | Origin | Content |
|---|---|---|
| `eu.kanade.tachiyomi.*` | Original Tachiyomi | Activities, screens, widgets, DI, data services |
| `eu.kanade.domain.*` | Tachiyomi refactor | App-level domain interactors |
| `eu.kanade.presentation.*` | Compose migration | Compose screens, components, theme |
| `exh.*` | TachiyomiSY/ExHentai | E-Hentai, MangaDex, recommendations |
| `mihon.*` | Mihon upstream | Migration, upcoming, Shizuku |

## App identity: launcher icons and custom names (KMK)

`AppIconManager` (`data/personalization/`) owns the **exactly-one-enabled launcher alias**
invariant and runs `sync()` from `App.onCreate`. Android cannot change an installed app's icon or
label at runtime: the launcher reads both from the component it starts. Built-in icons are
therefore `<activity-alias>` entries in `AndroidManifest.xml`, all targeting `MainActivity`;
`MainActivity` itself carries no `MAIN`/`LAUNCHER` filter.

Adding a built-in icon means three edits, no behaviour code:

1. an `<activity-alias>` named `.ui.main.MainActivity<Name>` with its own `android:icon` and
   `android:roundIcon`, `android:enabled="false"`;
2. a mipmap resource for it (an `adaptive-icon` reusing `@drawable/ic_launcher_foreground`);
3. one `BuiltInIcon` entry in `AppIconManager.builtInIcons`, carrying its KMR label.

Arbitrary user images cannot replace the installed launcher icon — resources are immutable. The
custom path therefore pins a `ShortcutInfoCompat` launcher shortcut carrying the image and the
custom name (`pinCustomShortcut`), and the UI says so. `customAppName` is also shown in
`LogoHeader`.

**Never derive the alias package with `Class.getPackageName()`.** That is a Java 9 API Android's
runtime does not implement on some releases — on API 30 it throws `NoSuchMethodError: No virtual
method getPackageName() ... in class java.lang.Class`, and because `LogoHeader` resolves
`AppIconManager` during composition, it takes the More tab down with it. Use the class's own name
instead: `MainActivity::class.java.name.substringBeforeLast('.')` is available on every API level
and is exactly the package the manifest aliases sit in.

**The custom name is in-app only.** Android has no runtime API to rename an installed app's launcher
entry, and nothing in this code tries to: `customName` is a SharedPreferences string shown by
`LogoHeader` and used as the pinned shortcut's label. Never wire it into a future "rename the app"
attempt — the only supported path is another activity alias with its own fixed label. The picked
custom image is stored twice: `launcher_icon_source.png` (bounded to 1024 px, full aspect) and the
square `launcher_icon.png` derived from it, so the Crop/Fit choice can be reapplied without asking
for the image again.

## Baked-in Komga source (KMK)

`app/src/main/java/eu/kanade/tachiyomi/source/online/all/komga/` — a self-hosted Komga server
(https://komga.org) as a built-in source, registered in `AndroidSourceManager`'s builtins map under
`exh.source.KOMGA_SOURCE_ID` (no catalog extension needed; the Keiyoushi Komga extension
coexists — the app's Komga enhanced tracker accepts both FQCNs, so either source binds to it).

| File | Concern |
|------|---------|
| `Komga.kt` | The `HttpSource` + `TextSource` + `MixedContentSource`/`EntryTypeSource` + `EpubArchiveSource` + text capability interfaces; browse/chapters/pages REST; epub chapter routing |
| `KomgaClient.kt` | One OkHttp client: per-request `X-API-Key` interceptor + Basic `Authenticator` (never re-authenticates, never answers a foreign host's challenge), `Dns.SYSTEM` (Komga servers are often IP-addressed; DoH would break that) |
| `KomgaDto.kt` | kotlinx-serialization DTOs (server sends more; unknown keys are ignored) |
| `KomgaPreferences.kt` | Keys on the standard `source_$id` file; `serverBase()`/`displayName()` |
| `KomgaEpubAdapter.kt` | Downloads the EPUB to `cacheDir/komga/<bookId>.epub.cache` (keyed on the server's `fileHash`), then serves it through `EpubTextSource`/`EpubResourceServer` — the same stack as the local source's epub half |
| `DownloadableTextSource` (in `data/download/`) | The optional seam that lets a book chapter be **downloaded** as its source file instead of pages. Komga implements it; the downloader writes `<chapter dir>/book.epub`, and `DownloadedBookPageLoaderProvider` opens that file through `DownloadedEpubTextSource` so a downloaded book reads offline. Never wrap the result in CBZ — an EPUB in a CBZ is not a book |

**URLs are stored absolute** (`$server/api/v1/series/<id>`), matching the Keiyoushi extension's
storage format: the enhanced tracker GETs `manga.url` directly, and this source's library entries
interoperate with the extension's. Chapter urls carry the book id; `KomgaEpubAdapter.bookIdOf`
parses it out wherever it is needed.

**Delegated Komga (issue #69)**: when the Keiyoushi Komga extension is installed *and* the
"delegated sources" preference (`DelegateSourcePreferences.delegateSources()`, Settings → Advanced →
Developer tools) is on, `AndroidSourceManager.toInternalSource` substitutes `Komga(context, id, lang)`
under the extension's own source id — same id, same `source_<id>` prefs, same absolute urls, so
settings, library entries and the tracker carry over and EPUB books open in the book reader. Matched
on the extension's *package* (`KOMGA_EXTENSION_PACKAGE`). The switch is read at source-map build
time, so flipping it needs a restart.

**Entry typing**: `entryTypeOf` deliberately answers null (a series may mix books and comics);
`detectBookEntry` probes the book's `mediaProfile` — `EPUB` and *not* Divina-compatible means the
book reader claims the chapter, everything else goes to image pages. The host's explicit-user-first
precedence (`PageLoaderFactory.isBookEntry`) sits above both.

## Key directories

| Path | Purpose |
|------|---------|
| `eu.kanade.tachiyomi.ui.*` | Nav3 routes + screen composables; ViewModels in `ui/` |
| `eu.kanade.presentation.*` | Compose UI (screens, components, theme) |
| `eu.kanade.tachiyomi.di/` | AppModule, PreferenceModule |
| `eu.kanade.domain/` | DomainModule, KMKDomainModule, SYDomainModule |
| `eu.kanade.tachiyomi.data.*` | Infrastructure: backup, download, sync, track, coil |
| `exh/` | E-Hentai/ExHentai features (see exh/AGENTS.md) |
| `mihon/feature/` | Self-contained feature modules (migration, upcoming) |

## Dependency injection

Primary: **Metro** (`dev.zacsweers.metro`). `AppGraph` (`mihon/app/di/AppGraph.kt`) exposes typed accessors; resolve via `context.appGraph.x`, `globalAppGraph.x` (non-Context classes), or constructor `@Inject`. ViewModels: `@AssistedInject` + nested `@AssistedFactory` (`@ContributesIntoMap(AppScope)`), resolved with `viewModels<T> { graph.viewModelFactory }`. New bindings go through `@Inject` constructors / `AppBindings` — **not** Injekt.

Legacy Injekt modules (still loaded in `App.onCreate`, kept only for remaining keep-sites + the extension bridge — do not extend):
1. `PreferenceModule` – preference stores
2. `AppModule` – infrastructure singletons (DB, network, downloads)
3. `DomainModule` – core domain interactors + repos
4. `KMKDomainModule` – Komikku-only (library update errors)
5. `SYPreferenceModule` – SY/ExH preferences
6. `SYDomainModule` – SY domain (metadata, merge, feed)

## Screen pattern

Nav3 route + screen composable in `ui/`, Metro `ViewModel` in `ui/`, content composable in `presentation/`:
- `LibraryTab.kt` → `LibraryViewModel.kt` → `presentation/library/`
- `MangaScreen.kt` → `MangaViewModel.kt` → `presentation/manga/`
- `ReaderActivity` uses `ReaderViewModel` (AndroidX ViewModel)

## Controller delegation (2026-11 structural rewrite)

State-heavy ViewModels hold state, thin intent methods, and wire **controllers**; business logic
lives in the controller, which takes scope + dependencies + accessor lambdas and never an Android
Context of its own beyond what the concern needs. Conventions the splits established:

- Controllers sit beside their VM (`ui/library/controller/`, or flat in the same package:
  `ui/manga/ChapterActionsController.kt`, `ui/reader/ReaderImageActions.kt`,
  `data/download/DownloadFileOps.kt`); class + moved top-level helpers are `internal`.
- The VM constructs the controller once with `provide* = { … }` accessors for its own state,
  an `updateState`/`sendEvent` lambda for writes, and keeps public intent methods as one-line
  delegations so the screen-facing API never changes.
- Result types moved out of a VM become top-level in the controller's file and keep the old
  public visibility (interface members cannot be `internal`): `ReaderSetAsCoverResult`,
  `ReaderSaveImageResult`.
- Large composables split the same way by concern into co-located section files
  (`TrackInfoDialogHomeItems.kt`, `MangaScreenActions.kt`, `SettingsEhDialogs.kt`,
  `NovelFontPickerDialog.kt`): helpers become `internal`, marker islands (`// KMK`, `// SY`)
  move WHOLE — never cut an island open.
- Files assessed-and-left deliberately: `ReaderActivity`/`NovelWebViewer` (already
  concern-separated per their AGENTS.md, private-member coupling), `App.kt` (bootstrap
  linearity is the structure), DI assembly (`AppGraph`).


## Settings framework

Modular preferences with automatic UI wiring — full guide:
`app/src/main/java/eu/kanade/presentation/more/settings/framework/AGENTS.md`.

- Store base: `SettingKey`/`SettingsRegistry` (`core:common`); UI: `SettingDefinition`,
  `SettingRow`/`toItems`, `GenericSettingsScreen`; screen registry: `SettingsCatalog`.
- New setting = key in `*SettingKeys` + definition in `*SettingsHost` + host list entry.
- New screen = register in `SettingsCatalog.searchableScreens` AND `SettingsMainScreen.items`.
- Pilot: webhooks (`WebhookSettingKeys`, `WebhookSettingsHost`).

## Backup & restore

Code in `eu.kanade.tachiyomi.data.backup.{create,restore}`:
- A restore **streams the file**: `BackupDecoder.decodeMetadata` walks the entries by length to
  count them and hands back everything else, and `decodeEntries(uri, include)` yields one
  `BackupManga` at a time, skipping rejected ones by length (`BackupReader.kt` walks the protobuf
  by hand; the wire format is unchanged, so old and new backups are both readable). Never call
  `decode(uri)` on a restore path — it materialises the whole library, which is the thing the
  streaming pass exists to avoid. `decode(uri)` is for the integrity check, which needs every
  entry's trackers.
- Entry order is a **classification**, not a sort: `restorePhaseOf` returns NEW / EXISTING / MERGED
  and `BackupRestorer` walks the file twice to realise it. Sorting would mean holding every entry
  to order it.
- `MangaRestorer.restore` takes a **list** and opens **one transaction for the chunk**, falling
  back to entry-by-entry in `BackupRestorer` if the batch throws. One transaction per entry was
  ~15 000 commits on a large library; see `restore`'s own comment.
- The per-entry fetch-interval recompute (schedule-less backups: every Mihon-format one) reads the
  **backup's own chapter list**, not the table — `FetchInterval.toMangaUpdate` takes the chapters
  as a parameter for exactly this caller; reading them back re-fetched every row just written.
- `PreferenceBackupCreator` snapshots `preferenceStore.getAll()` → typed `BackupPreference`
  list; always drops `__APP_STATE_`, drops `__PRIVATE_` unless user opts in.
- `PreferenceRestorer` type-switches values back; remaps category IDs; special-cases
  `LibraryPreferences`/`DownloadPreferences` category key sets + `SourcePreferences.PINNED_SOURCES_PREF_KEY`.
- Key renames require a `mihon.core.migration.Migration` (`MigrateUtils.replacePreferences`);
  backup files match on raw key strings, so framework `SettingKey` values must stay stable.
- Post-restore bookkeeping: `LibraryUpdateJob.startNow` is gated on `RestoreOptions.updateLibrary`
  (default **on**; UI row is `RestoreOptions.updateLibraryEntry`, rendered in its own section
  because it is a follow-up, not backup content — it never counts towards `canRestore()`).
  `restore()` folds the WAL in a `finally` (`DatabaseHandler.checkpoint()` — outside any
  transaction), `fromBooleanArray` reads WorkManager input with `getOrNull` fallbacks so stale
  input from an older build resumes instead of crashing, and the restore screen validates on
  `screenModelScope.launchIO` because the validation decodes the whole file.
- `restoreManga` flushes the partial `pendingEntries` chunk **after the second pass, before the
  merged entries**: `restoreEntries` clears the buffer in its `finally`, so a merged chunk
  written with a partial buffer still pending used to silently drop the tail of the library
  (everything when `entries.size % 250 != 0`) and stop the progress bar short of 100%.

## Library imports (KMK)

Resumable imports of another service's library export — MangaBaka today, any service through one
adapter. Code in `mihon/feature/libraryimport/` (app) + `tachiyomi/domain/importer/` (models,
repository interface, interactors); Settings → Data and storage → **Library imports**.

- **An import entry is a shell, never a manga row.** `ImportEntry` lives in the import session's
  JSON, not in `mangas`, so nothing can open it in the reader or list chapters for it. It becomes
  real only when the reader matches it to a source (`MigrateImportEntryUseCase`).
- **Generic by adapter.** `LibraryImportAdapter` (`data/`) parses one export format;
  `LibraryImportAdapters` is the registry. Adding a service = one adapter + a line in the registry.
  Parsed entries carry `trackingLinks` (`ImportTrackingLink`: app tracker id + remote id + state),
  so auto-tracking is not MangaBaka-specific.
- **Persistence is one JSON file** (`ImportSessionStore`, `filesDir/library_imports/sessions.json`)
  behind `ImportSessionRepository`, written through a temp file + rename and mutated under one
  mutex. Resumability is the `importedEntryIds` / `skippedEntryIds` sets; re-importing the same
  export (same adapter + same entry-id list) continues the existing session instead of duplicating.
- **The migration flow is the import screens themselves**: session list → session
  (`ImportSessionScreen`, the "fake entries") → `ImportEntrySearchScreen` (a `SearchViewModel`
  subclass reusing `MigrateSearchScreen`) → `MigrateImportEntryDialog` → `MigrateImportEntryUseCase`,
  which fetches the target from its source, favorites it, marks chapters read up to the export's
  progress (recognized numbers only), copies the note, and inserts the tracking row **locally**
  (no upload — the next tracker refresh adopts the service's values). An existing track for that
  tracker is never replaced, and an entry already in the library keeps its `dateAdded`.

## Theme system

19 named color schemes extending `BaseColorScheme`. KMK adds cover-based dynamic theming via `DynamicMaterialExpressiveTheme`.

## Fork markers

All Komikku additions wrapped in `// KMK -->` ... `// KMK <--`. SY additions in `// SY -->` ... `// SY <--`.

## Conventions

- Logging: `xLogE()` / `xLog()` from `exh.log` for KMK code; `logcat {}` for Mihon code
- Preferences: `eu.kanade.domain.*.service.*Preferences`, keys centralized in co-located
  `*SettingKeys` (see `settings/framework/AGENTS.md`); new UI rows via `*SettingsHost` definitions
- Strings: `KMR` + `i18n-kmk/` for Komikku-only features
