# MDviewer

MDviewer is an offline viewer for Android. It opens Markdown notes, images, comic books and DjVu
documents in one simple, distraction-free reader.

## What it opens

| Type | Files | How it is shown |
|---|---|---|
| Markdown | `.md`, `.markdown`, `.txt` | Formatted text with math, diagrams and highlighted code |
| SVG | `.svg` | Centered and scaled to fit the screen |
| Images | JPEG, PNG, GIF, WebP, BMP, **AVIF** | Centered and scaled to fit the screen |
| Comic books | `.cbz`, `.cbr`, `.cb7` | All pages in one vertical scroll |
| DjVu | `.djvu`, `.djv` | All pages in one vertical scroll |

You can pinch to zoom in every view.

### Markdown

- Headings, lists, tables, links, images, quotes and code blocks.
- **Math** with KaTeX: `$inline$` and `$$block$$`.
- **Diagrams** with Mermaid: use a ` ```mermaid ` code block.
- **Syntax highlighting** for code blocks in many languages.
- Task lists, footnotes, abbreviations, definition lists, emoji (`:smile:`),
  ==highlight==, ++inserted++, ~subscript~, ^superscript^ and `::: warning` blocks.
- **Obsidian-style notes**: `[[Another note]]` links, `[[note|alias]]`, `[[note#heading]]`,
  and embedded media with `![[picture.png]]`, `![[clip.mp4]]` or `![[song.mp3]]`.
- YAML front matter at the top of a note is hidden.
- Links to other local notes, images, comics or DjVu files open in MDviewer.
  Web links open in your browser.

### Images

AVIF images that your phone's gallery can't show still display here. MDviewer decodes images with
the browser engine built into Android System WebView, not with the phone's own image decoder.

### Comic books

- Supports CBZ (ZIP), CBR (RAR 4 and RAR 5) and CB7 (7-Zip) files.
- Pages are sorted the way you'd expect: `page2` comes before `page10`.
- The file type is detected from its contents, so a `.cbr` that is really a ZIP still opens.
- A page counter (`12 / 48`) appears while you scroll.
- Password-protected archives are not supported.

### DjVu

- Pages are rendered as you scroll, so large scanned books open quickly.
- Pages you've scrolled far away from are released to save memory.
- Only single-file DjVu documents are supported, not documents split across many files.

## Using the app

- **Open a file**: launch MDviewer and tap **Open File** in the side menu. You can also choose
  MDviewer from "Open with" in a file manager or another app.
- **Side menu**: tap the ☰ button or swipe in from the middle of the left edge. It has
  **Open File**, your five most **Recent Files**, and the **Theme** (System, Light or Dark).
- **Share text to MDviewer**: share text from any app. MDviewer saves it as a `.md` file in your
  Downloads folder and opens it.
- **Full screen**: the status and navigation bars hide when you scroll down and come back when
  you scroll up.

## Privacy

MDviewer works entirely offline and never connects to the internet. Web images inside a Markdown
note are not loaded, and links to websites open in your own browser. HTML inside notes is
sanitized, so scripts can't run.

## Install

Download the latest `MDviewer-build-<number>.apk` from the
[Releases](../../releases) page and open it on your phone. You may need to allow installing apps
from your browser or file manager. Requires Android 13 or newer.

A new release is built automatically every time a change lands on `master`.

## Building

MDviewer is written in Java for Android 13+ (min SDK 33) and renders documents in a WebView.

- Build the APK: `./gradlew assembleDebug`, or open the project in Android Studio.
- Android and archive tests: `./gradlew testDebugUnitTest` (uses Robolectric, so no device is needed).
- Lint: `./gradlew lintDebug`
- Renderer tests (Node.js 22): `npm ci` then `npm test`

The GitHub Actions workflow in `.github/workflows/release.yml` runs the tests and builds the APK
for every pull request. For changes on `master`, it also publishes the APK as a release. To sign
releases with a stable key, so that new versions can update an installed app, add your
`~/.android/debug.keystore` as a base64-encoded repository secret named `DEBUG_KEYSTORE_BASE64`.

### How documents are displayed

Markdown is rendered by `main.js` from `template.html`. Images, comic books and DjVu files are
shown by `viewer.html`. `ViewerContent` serves those files to the viewer from the address
`https://appassets.androidplatform.net/` without any real network access. RAR and 7z pages are
unpacked in order into the app's cache, because solid archives can't be read out of order. They
are deleted when another file is opened.

## Third-party components

| Component | Used for | License |
|---|---|---|
| [markdown-it](https://github.com/markdown-it/markdown-it) and plugins | Markdown parsing | MIT |
| [KaTeX](https://katex.org/) and markdown-it-texmath | Math | MIT |
| [Mermaid](https://mermaid.js.org/) | Diagrams | MIT |
| [highlight.js](https://highlightjs.org/) | Code highlighting | BSD-3-Clause |
| [DOMPurify](https://github.com/cure53/DOMPurify) 3.4.15 | HTML sanitizing | Apache-2.0 / MPL-2.0 (`assets/DOMPurify-LICENSE.txt`) |
| [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/), [XZ for Java](https://tukaani.org/xz/java.html) | ZIP and 7z archives | Apache-2.0, 0BSD |
| [junrar](https://github.com/junrar/junrar) | RAR archives | UnRAR license |
| [DjVu.js](https://github.com/RussCoder/djvujs) 0.5.4 | DjVu documents | GPL-2.0 (`assets/DjVuJS-LICENSE.txt`) |

- DjVu.js is bundled as `app/src/main/assets/djvu.min.js`. It was built from the library sources
  in the `djvujs-dist` npm package with its rollup configuration, then minified with terser.
- The UnRAR license allows extracting RAR files but not re-creating the RAR compression algorithm.
- The RAR test files in `app/src/test/resources/com/mdviewer` come from junrar's test suite.

## License

MDviewer is licensed under the GNU General Public License v3.0. See [LICENSE](LICENSE).
