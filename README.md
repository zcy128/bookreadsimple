English | [简体中文](README.zh-CN.md)

# BookReader - Privacy-First Native Android E-Book Reader

A privacy-first native Android e-book reader built with **Kotlin + Jetpack Compose + Room**, supporting **EPUB, KEPUB, MOBI, PDF, and TXT**.

> **Privacy First — Your Books Stay Yours:** All your books are stored strictly in your **own Google Drive folder**, so **no one else (and no third-party server) can access them**. Your reading progress, extracted titles, and shelf sorting also sync seamlessly across devices via `bookreader-progress.json` inside that same private folder, with local offline caching.

---

## 🌟 Key Features

### 1. Private Personal Cloud & Progress Sync
- **Zero Third-Party Servers**: No Firebase, no developer-hosted backend, and no telemetry. Your e-books never leave your own Google Drive and your device.
- **Scoped Folder Access**: Uses Android Storage Access Framework (SAF) to access only the specific Google Drive subfolder you manually authorize—never requesting broad Drive root access.
- **Seamless Progress & Metadata Sync**: Reading progress, extracted book titles, custom renames, tags, book lock states, and cleared-priority states are synced privately across your devices via `bookreader-progress.json` in your Drive folder.
- **Offline Reading**: Books are cached locally when opened so you can read anytime without an internet connection.
- **Pull-to-Refresh & Sync Summary**: Pull down on the bookshelf to sync cloud additions/removals (waiting for SAF `EXTRA_LOADING` cloud updates) and view a summary of scanned files, added/removed books, skipped formats, and failures.

### 2. Multi-Format Native Support
- **EPUB / KEPUB**: Lightweight native ZIP extraction with OPF/NCX/NAV parsing for chapters and TOC; rendered in Compose WebView with injected responsive CSS, paged/scroll modes, footnote popups, and eye-care themes.
- **MOBI**: Pure-Kotlin Mobipocket / PalmDOC parser (supporting uncompressed, PalmDOC LZ77, and HUFF/CDIC records), extracting metadata, embedded images, and split chapters into `EpubReaderView` (DRM-protected `.mobi` files are not supported).
- **PDF**: High-performance rendering via Android's native `android.graphics.pdf.PdfRenderer`, with pinch-to-zoom and horizontal paging.
- **TXT**: Automatic charset detection (UTF-8, GBK, Unicode, etc.), regex-based chapter splitting, customizable font size/line spacing, and character-offset progress tracking.

### 3. Adaptive UI for Phones & Tablets
- **Phones (Compact / Medium)**: Bottom `NavigationBar`, list/grid toggle, and left/right-hand tap zones for one-handed reading.
- **Tablets / Foldables (Expanded / Large)**: Side `NavigationRail`, adaptive 3–6 column bookshelf grid (`GridCells.Adaptive`), and two-page reading layout support.

### 4. Reader Customization & Utilities
- 4 reading themes: Light, Parchment, Eye-Protect Green, and Dark.
- Adjustable font size (12sp–32sp), line spacing, scroll/page mode, and left/right-hand page turning.
- Slide-out chapter drawer, progress bar modes, in-app dictionary lookup, and Text-to-Speech (TTS).

---

## 🏗️ Architecture Overview

```text
com.example.bookreader
├── BookReaderApplication.kt         # Application singleton & lifecycle setup
├── MainActivity.kt                  # Window size class detection & navigation host
├── domain/
│   └── model/
│       └── BookModels.kt            # Book, format, progress, chapter, and settings models
├── data/
│   ├── local/
│   │   ├── Entities.kt              # Room entities (BookEntity, ProgressEntity)
│   │   ├── Daos.kt                  # Offline Room DAOs
│   │   ├── BookPasswordStore.kt     # Book lock password store
│   │   └── AppDatabase.kt           # Room Database
│   ├── remote/
│   │   └── GoogleDriveManager.kt    # SAF folder scanning, file transfer, & progress JSON I/O
│   └── repository/
│       └── BookRepository.kt        # Offline-first repository & cross-device sync logic
├── parser/
│   ├── BookMetadataExtractor.kt     # Title & author metadata extraction
│   ├── TxtBookParser.kt             # TXT charset detection & chapter splitting
│   ├── PdfBookRenderer.kt           # Thread-safe native PdfRenderer wrapper
│   ├── EpubBookParser.kt            # EPUB/KEPUB extraction & OPF/NCX/NAV parsing
│   └── MobiBookParser.kt            # Pure-Kotlin MOBI parser with image & chapter extraction
└── ui/
    ├── theme/                       # Compose Material 3 theme & colors
    ├── components/                  # Adaptive navigation, book cards/rows, dictionary sheet
    ├── bookshelf/                   # Bookshelf UI, pull-to-refresh, search & filters
    ├── reader/                      # Reader container & TXT/PDF/EPUB(MOBI) views
    └── settings/                    # Sync settings & offline cache management
```

---

## 📦 Build & Test

```powershell
# Run unit tests and Lint checks
.\gradlew.bat testDebugUnitTest lintDebug

# Build Debug APK
.\gradlew.bat assembleDebug
# Output: app\build\outputs\apk\debug\app-debug.apk

# Build Release APK
.\gradlew.bat assembleRelease
# Output: app\build\outputs\apk\release\app-release.apk
```
