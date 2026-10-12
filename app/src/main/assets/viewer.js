// Shows images, comic book pages and DjVu documents served by ViewerContent.
(function () {
    'use strict';

    // Rendered DjVu pages kept around the current page; the rest are released to save memory.
    const KEEP_DJVU_PAGES = 4;

    const container = document.getElementById('pages');
    const status = document.getElementById('status');
    const counter = document.getElementById('counter');

    function showStatus(message) {
        status.textContent = message;
        status.hidden = !message;
    }

    function createPage(alt, width, height) {
        const page = document.createElement('img');
        page.className = 'page';
        page.alt = alt;
        page.setAttribute('decoding', 'async');
        if (width && height) {
            page.width = width;
            page.height = height;
        }
        page.addEventListener('load', () => page.classList.add('loaded'));
        container.appendChild(page);
        return page;
    }

    async function fetchOk(url) {
        const response = await fetch(url);
        if (!response.ok) throw new Error(response.status + ' ' + response.statusText);
        return response;
    }

    async function showImages() {
        const manifest = await (await fetchOk('pages.json')).json();
        if (manifest.error) throw new Error(manifest.error);
        const single = manifest.pages.length === 1;
        document.body.classList.toggle('single', single);
        manifest.pages.forEach(entry => {
            const page = createPage(entry.name);
            if (single) {
                // The WebView decodes images itself, so this only fails for unsupported formats.
                page.addEventListener('error', () => showStatus('This image format cannot be displayed.'));
            } else {
                page.setAttribute('loading', 'lazy');
            }
            page.src = entry.url;
        });
        showStatus('');
        trackCurrentPage();
    }

    function loadScript(src) {
        return new Promise((resolve, reject) => {
            const script = document.createElement('script');
            script.src = src;
            script.onload = resolve;
            script.onerror = () => reject(new Error('Cannot load ' + src));
            document.head.appendChild(script);
        });
    }

    async function showDjVu() {
        const [response] = await Promise.all([fetchOk('file'), loadScript('/assets/djvu.min.js')]);
        // Decoding runs in a Web Worker so that scrolling stays responsive.
        const worker = new window.DjVu.Worker();
        await worker.createDocument(await response.arrayBuffer());
        const sizes = await worker.doc.getPagesSizes().run();
        // An empty alt keeps unrendered pages as blank placeholders instead of broken images.
        const pages = sizes.map(size => createPage('', size.width, size.height));
        const rendered = new Map();
        const visible = new Set();
        let current = 0;
        let rendering = false;

        async function renderNext() {
            if (rendering) return;
            const next = [...visible].filter(index => !rendered.has(index))
                .sort((a, b) => Math.abs(a - current) - Math.abs(b - current))[0];
            if (next === undefined) return;
            rendering = true;
            try {
                const image = await worker.doc.getPage(next + 1).createPngObjectUrl().run();
                rendered.set(next, image.url);
                pages[next].src = image.url;
                releaseDistantPages();
            } catch (error) {
                console.error(error);
                rendered.set(next, null);
                pages[next].alt = 'Page ' + (next + 1) + ' could not be rendered';
            } finally {
                rendering = false;
                renderNext();
            }
        }

        function releaseDistantPages() {
            for (const [index, url] of rendered) {
                if (url && !visible.has(index) && Math.abs(index - current) > KEEP_DJVU_PAGES) {
                    pages[index].removeAttribute('src');
                    pages[index].classList.remove('loaded');
                    URL.revokeObjectURL(url);
                    rendered.delete(index);
                }
            }
        }

        const observer = new IntersectionObserver(entries => {
            for (const entry of entries) {
                const index = pages.indexOf(entry.target);
                if (entry.isIntersecting) visible.add(index); else visible.delete(index);
            }
            renderNext();
        }, { rootMargin: '100% 0px' });
        pages.forEach(page => observer.observe(page));
        document.body.classList.toggle('single', pages.length === 1);
        showStatus('');
        trackCurrentPage(index => { current = index; });
    }

    // Shows "page / total" briefly while scrolling through multi-page documents.
    function trackCurrentPage(onChange) {
        const pages = container.children;
        if (pages.length < 2) return;
        let hideTimer;
        let scheduled = false;
        function update() {
            scheduled = false;
            const viewport = window.visualViewport;
            const middle = viewport ? viewport.pageTop + viewport.height / 2 : window.scrollY + window.innerHeight / 2;
            let low = 0;
            let high = pages.length - 1;
            while (low < high) {
                const mid = (low + high + 1) >> 1;
                if (pages[mid].offsetTop <= middle) low = mid; else high = mid - 1;
            }
            if (onChange) onChange(low);
            counter.textContent = (low + 1) + ' / ' + pages.length;
            counter.hidden = false;
            clearTimeout(hideTimer);
            hideTimer = setTimeout(() => { counter.hidden = true; }, 1500);
        }
        function schedule() {
            if (scheduled) return;
            scheduled = true;
            requestAnimationFrame(update);
        }
        window.addEventListener('scroll', schedule, { passive: true });
        if (window.visualViewport) window.visualViewport.addEventListener('scroll', schedule);
        update();
    }

    (document.body.dataset.kind === 'djvu' ? showDjVu() : showImages()).catch(error => {
        showStatus('Could not open this file: ' + (error && error.message ? error.message : error));
    });
})();
