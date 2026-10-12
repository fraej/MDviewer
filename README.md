# MDviewer

A lightweight, feature-rich Markdown viewer for Android. MDviewer is designed to provide a clean, distraction-free reading experience for your Markdown files, with full support for modern Markdown extensions and Obsidian-style syntax. It also opens images, comic book archives and DjVu documents.

## Features

- **🚀 Direct Launch**: Automatically opens a file picker when launched directly.
- **📱 Shared Text Support**: Share text from any app to MDviewer; it will automatically save it as a `.md` file in your Downloads folder and open it.
- **🎨 Modern Rendering**: Powered by `markdown-it` with support for:
  - Math (KaTeX)
  - Diagrams (Mermaid.js)
  - Syntax Highlighting (highlight.js)
  - Task lists, Footnotes, Abbreviations, Deflists, and more.
- **🖼️ SVG Viewer**: Open and view SVG images directly. Images are automatically centered and scaled to fit your screen perfectly.
- **📷 Image Viewer**: JPEG, PNG, GIF, WebP, BMP and AVIF. Images are decoded by the WebView's own (Chromium) codecs rather than the Android platform decoder, so AVIF files display even on devices whose gallery cannot show them.
- **📚 Comic Books**: CBZ (ZIP), CBR (RAR 4 and RAR 5) and CB7 (7z) archives open as a vertically scrolling list of pages in natural order (`page2` before `page10`). The archive type is detected from the file contents, so a `.cbr` that is really a ZIP still opens.
- **📄 DjVu**: Single-file DjVu documents are rendered page by page with [DjVu.js](https://github.com/RussCoder/djvujs) in a background worker. Pages far from the current one are released to save memory.
- **🌲 Obsidian Compatibility**: Supports Wikilinks `[[Link]]` and media embedding `![[image.png]]`.
- **🌙 Immersive Mode**: Automatically hides system bars when scrolling down for a full-screen experience.
- **👋 Gesture Navigation**: Swipe left or right to quickly close the app.
- **🌐 Web Links**: Automatically opens external hyperlinks in your default browser.
- **📁 Offline First**: Works entirely offline with zero network dependencies (except for external web links).

## Development

MDviewer is built with:
- Java / Android SDK (Min SDK 33)
- WebView for rendering
- [markdown-it](https://github.com/markdown-it/markdown-it) for Markdown parsing
- [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/) and [XZ for Java](https://tukaani.org/xz/java.html) for ZIP and 7z archives, [junrar](https://github.com/junrar/junrar) for RAR archives
- [DjVu.js](https://github.com/RussCoder/djvujs) for DjVu documents

Images, comic book pages and DjVu files are served to `viewer.html` from the virtual origin
`https://appassets.androidplatform.net/` by `ViewerContent`; network loads stay blocked.
RAR and 7z pages are extracted in archive order to the app cache (solid archives cannot be
read out of order) and deleted when another document is opened.

## Tests

- Android activity and archive tests: `./gradlew testDebugUnitTest`
- Renderer regression tests (Node.js 22.22.2+ on the 22.x line): `npm ci` then `npm test`
- Android checks and APK: `./gradlew lintDebug assembleDebug`

The renderer tests exercise the bundled assets in a DOM environment. Mermaid's
layout is stubbed; its input and security settings are checked. Android tests use
Robolectric and do not require a device. Linked documents must already be readable
by the app; Android document-provider grants do not automatically include sibling files.

The RAR fixtures in `app/src/test/resources/com/mdviewer` come from junrar's test suite.

DOMPurify 3.4.15 is bundled for offline HTML sanitization from
[the upstream release](https://github.com/cure53/DOMPurify/releases/tag/3.4.15).
Its license is included in `app/src/main/assets/DOMPurify-LICENSE.txt`.

DjVu.js 0.5.4 is bundled as `app/src/main/assets/djvu.min.js`, built from the library sources in
the `djvujs-dist` npm package with its rollup configuration and minified with terser. The library
is licensed under the GNU GPL version 2; see `app/src/main/assets/DjVuJS-LICENSE.txt`.
junrar is distributed under the UnRAR license, which allows decompression but not re-creating
the RAR compression algorithm.

## License

This project is licensed under the GNU General Public License v3.0 - see the [LICENSE](LICENSE) file for details.
