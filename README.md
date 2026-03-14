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

## License

This project is licensed under the GNU General Public License v3.0 - see the [LICENSE](LICENSE) file for details.
