const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');
const { JSDOM } = require('jsdom');

const assets = path.join(__dirname, '../app/src/main/assets');
const template = fs.readFileSync(path.join(assets, 'template.html'), 'utf8');
const scripts = [...template.matchAll(/<script src="file:\/\/\/android_asset\/([^"]+)"/g)]
    .map(match => match[1]);

function render(markdown, { dark = false, sanitizer = true } = {}) {
    const dom = new JSDOM('<!doctype html><html><body><div id="content"></div></body></html>', {
        url: 'file:///documents/start.md', runScripts: 'outside-only'
    });
    const { window } = dom;
    let mermaidConfig;
    // Mermaid SVG layout needs a browser; capture its input/config without changing the renderer.
    window.mermaid = { initialize(config) { mermaidConfig = config; }, run() { return Promise.resolve(); } };
    window.RAW_MARKDOWN_BASE64 = Buffer.from(markdown).toString('base64');
    window.IS_DARK = dark;
    for (const script of scripts) {
        if (script === 'mermaid.min.js' || (!sanitizer && script === 'purify.min.js')) continue;
        vm.runInContext(fs.readFileSync(path.join(assets, script), 'utf8'), dom.getInternalVMContext(), { filename: script });
    }
    return { dom, document: window.document, content: window.document.getElementById('content'),
        mermaidConfig };
}

test('wikilinks and embeds remain literal in code and escaped text', () => {
    const literal = '[[example]]\n![[image.png]]\n';
    const result = render('```text\n' + literal + '```\n\n`[[inline]]`\n\n    ![[indented.png]]\n\n\\[[escaped]]');
    const code = result.content.querySelectorAll('code');
    assert.equal(code[0].textContent, literal);
    assert.equal(code[1].textContent, '[[inline]]');
    assert.equal(code[2].textContent, '![[indented.png]]\n');
    assert.equal(result.content.querySelectorAll('a, img').length, 0);
    result.dom.window.close();
});

test('inline wikilinks preserve aliases, extensions, spaces and fragments', () => {
    const result = render('[[My note|Read me]] [[note.md#part|Part]] [[#local|Here]]\n\n![[pic name.png|Picture]] ![[clip.mp4|Clip]] ![[song.mp3|Song]]');
    const links = [...result.content.querySelectorAll('a')];
    assert.deepEqual(links.map(link => link.getAttribute('href')), ['My%20note.md', 'note.md#part', '#local']);
    assert.deepEqual(links.map(link => link.textContent), ['Read me', 'Part', 'Here']);
    assert.equal(result.content.querySelector('img').getAttribute('src'), 'pic%20name.png');
    assert.equal(result.content.querySelector('img').alt, 'Picture');
    assert.equal(result.content.querySelector('video').getAttribute('src'), 'clip.mp4');
    assert.ok(result.content.querySelector('audio').hasAttribute('controls'));
    result.dom.window.close();
});

test('sanitization removes executable HTML while retaining formatting and local URLs', () => {
    const result = render('<strong>Safe</strong><img src="file:///images/a.png" onerror="alert(1)">' +
        '<a href="javascript:alert(1)">bad</a><iframe srcdoc="bad"></iframe><script>alert(1)</script>' +
        '<svg onload="alert(1)"><circle r="2"/></svg><a href="content://documents/42">local</a>' +
        '<a href="java&#x09;script:alert(1)">obfuscated</a>');
    assert.equal(result.content.querySelector('strong').textContent, 'Safe');
    assert.equal(result.content.querySelector('img').getAttribute('src'), 'file:///images/a.png');
    assert.equal(result.content.querySelector('script, iframe, [onerror], [onload]'), null);
    assert.equal(result.content.querySelectorAll('a[href]').length, 1);
    assert.equal(result.content.querySelector('a[href]').getAttribute('href'), 'content://documents/42');
    result.dom.window.close();
});

test('media aliases cannot inject attributes or HTML', () => {
    const result = render('![[clip.mp4|x" onerror="alert(1)]]\n\n[[note|<img src=x onerror=alert(1)>]]\n\n![[javascript:alert(1)|bad]]');
    assert.equal(result.content.querySelector('[onerror], img'), null);
    assert.equal(result.content.querySelector('video').getAttribute('title'), 'x" onerror="alert(1)');
    assert.equal(result.content.querySelector('a').textContent, '<img src=x onerror=alert(1)>');
    assert.equal(result.content.querySelector('[src^="javascript:"]'), null);
    result.dom.window.close();
});

test('Mermaid source remains text, with strict security and the selected theme', () => {
    const source = 'graph TD\nA["<img src=x onerror=alert(1)>"] --> B\n';
    const result = render('```mermaid\n' + source + '```', { dark: true });
    assert.equal(result.content.querySelector('.mermaid').textContent, source);
    assert.equal(result.content.querySelector('img'), null);
    assert.equal(result.mermaidConfig.securityLevel, 'strict');
    assert.equal(result.mermaidConfig.theme, 'dark');
    assert.ok(result.document.body.classList.contains('dark-mode'));
    result.dom.window.close();
});

test('math, task lists, footnotes and tables survive sanitization', () => {
    const result = render('$x^2$\n\n$$x + y$$\n\n- [x] Done\n\nA note[^1]\n\n[^1]: Footnote\n\n| A | B |\n| - | - |\n| 1 | 2 |');
    assert.ok(result.content.querySelector('.katex math'));
    assert.ok(result.content.querySelector('eq .katex'));
    assert.ok(result.content.querySelector('eqn .katex'));
    assert.ok(result.content.querySelector('input[type="checkbox"][disabled][checked]'));
    assert.ok(result.content.querySelector('a[href="#fn1"]'));
    assert.ok(result.content.querySelector('#fn1'));
    assert.ok(result.content.querySelector('table td'));
    result.dom.window.close();
});

test('missing sanitizer fails closed', () => {
    const result = render('<img src=x onerror=alert(1)>', { sanitizer: false });
    assert.equal(result.content.querySelector('img'), null);
    assert.match(result.content.textContent, /sanitizer is unavailable/);
    result.dom.window.close();
});
