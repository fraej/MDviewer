# MDviewer

A lightweight, feature-rich Markdown viewer for Android. MDviewer is designed to provide a clean, distraction-free reading experience for your Markdown files, with full support for modern Markdown extensions and Obsidian-style syntax.

## Features

- **🚀 Direct Launch**: Automatically opens a file picker when launched directly.
- **📱 Shared Text Support**: Share text from any app to MDviewer; it will automatically save it as a `.md` file in your Downloads folder and open it.
- **🎨 Modern Rendering**: Powered by `markdown-it` with support for:
  - Math (KaTeX)
  - Diagrams (Mermaid.js)
  - Syntax Highlighting (highlight.js)
  - Task lists, Footnotes, Abbreviations, Deflists, and more.
- **🖼️ SVG Viewer**: Open and view SVG images directly. Images are automatically centered and scaled to fit your screen perfectly.
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

## Tests

- Android activity regression tests: `./gradlew testDebugUnitTest`
- Renderer regression tests (Node.js 22.22.2+ on the 22.x line): `npm ci` then `npm test`
- Android checks and APK: `./gradlew lintDebug assembleDebug`

The renderer tests exercise the bundled assets in a DOM environment. Mermaid's
layout is stubbed; its input and security settings are checked. Android tests use
Robolectric and do not require a device. Linked documents must already be readable
by the app; Android document-provider grants do not automatically include sibling files.

DOMPurify 3.4.15 is bundled for offline HTML sanitization from
[the upstream release](https://github.com/cure53/DOMPurify/releases/tag/3.4.15).
Its license is included in `app/src/main/assets/DOMPurify-LICENSE.txt`.

## License

This project is licensed under the GNU General Public License v3.0 - see the [LICENSE](LICENSE) file for details.
