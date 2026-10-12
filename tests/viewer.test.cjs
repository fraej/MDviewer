const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { test } = require('node:test');
const { JSDOM } = require('jsdom');

const assets = path.join(__dirname, '../app/src/main/assets');
const base = 'https://appassets.androidplatform.net/viewer/token/';

// Loads viewer.html as MainActivity does, with fetch() answered by the given manifest.
async function open(kind, manifest) {
    const html = fs.readFileSync(path.join(assets, 'viewer.html'), 'utf8')
        .replace('{{KIND}}', kind).replace('{{THEME}}', 'dark-mode')
        .replace(/<script src="[^"]+"><\/script>/, '');
    const dom = new JSDOM(html, { url: base, runScripts: 'outside-only', pretendToBeVisual: true });
    const requests = [];
    dom.window.fetch = async url => {
        requests.push(String(url));
        return { ok: true, json: async () => manifest };
    };
    dom.window.eval(fs.readFileSync(path.join(assets, 'viewer.js'), 'utf8'));
    await new Promise(resolve => setTimeout(resolve, 20));
    return { dom, document: dom.window.document, requests };
}

test('comic book pages are lazily loaded from the viewer origin', async () => {
    const { dom, document, requests } = await open('book', {
        pages: [{ url: 'page/0', name: '1.avif' }, { url: 'page/1', name: '2.png' }]
    });
    assert.deepEqual(requests, ['pages.json']);
    const pages = [...document.querySelectorAll('img.page')];
    assert.deepEqual(pages.map(page => page.src), [base + 'page/0', base + 'page/1']);
    assert.deepEqual(pages.map(page => page.getAttribute('loading')), ['lazy', 'lazy']);
    assert.equal(pages[0].alt, '1.avif');
    assert.ok(document.getElementById('status').hidden);
    // jsdom has no layout, so only the page total is meaningful here.
    assert.match(document.getElementById('counter').textContent, /^\d \/ 2$/);
    assert.ok(!document.getElementById('counter').hidden);
    assert.ok(!document.body.classList.contains('single'));
    assert.ok(document.body.classList.contains('dark-mode'));
    dom.window.close();
});

test('a single image is shown on its own', async () => {
    const { dom, document } = await open('image', { pages: [{ url: 'file', name: 'photo.avif' }] });
    assert.ok(document.body.classList.contains('single'));
    assert.equal(document.querySelector('img.page').src, base + 'file');
    assert.ok(document.getElementById('counter').hidden);
    dom.window.close();
});

test('archive errors are reported instead of pages', async () => {
    const { dom, document } = await open('book', { error: 'No images found in this archive.', pages: [] });
    assert.equal(document.querySelectorAll('img.page').length, 0);
    assert.equal(document.getElementById('status').textContent,
        'Could not open this file: No images found in this archive.');
    assert.ok(!document.getElementById('status').hidden);
    dom.window.close();
});
