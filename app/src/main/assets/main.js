try {
    const md = window.markdownit({
        html: true,
        linkify: true,
        typographer: true,
        highlight: function (str, lang) {
            if (lang && window.hljs.getLanguage(lang)) {
                try {
                    return '<pre><code class="hljs">' + window.hljs.highlight(str, { language: lang, ignoreIllegals: true }).value + '</code></pre>';
                } catch (__) {}
            }
            return '<pre><code class="hljs">' + md.utils.escapeHtml(str) + '</code></pre>';
        }
    })
        .use(window.markdownitFootnote)
        .use(window.markdownitTaskLists)
        .use(window.markdownitMark)
        .use(window.markdownitIns)
        .use(window.markdownitSub)
        .use(window.markdownitSup)
        .use(window.markdownitAbbr)
        .use(window.markdownitDeflist)
        .use(window.markdownitEmoji)
        .use(window.markdownitContainer, 'warning')
        .use(window.texmath, { engine: window.katex, delimiters: 'dollars', katexOptions: { macros: {"\\RR": "\\mathbb{R}"} } });
    
    const defaultRender = md.renderer.rules.fence || function(tokens, idx, options, env, self) {
        return self.renderToken(tokens, idx, options);
    };
    md.renderer.rules.fence = function(tokens, idx, options, env, self) {
        const token = tokens[idx];
        if (token.info && token.info.trim() === 'mermaid') {
            return '<div class="mermaid">' + md.utils.escapeHtml(token.content) + '</div>';
        }
        return defaultRender(tokens, idx, options, env, self);
    };
    
    // Parse only inline Markdown, leaving code spans and fenced blocks untouched.
    md.inline.ruler.before('link', 'wikilink', function(state, silent) {
        const start = state.pos;
        const embedded = state.src.startsWith('![[', start);
        if (!embedded && (!state.src.startsWith('[[', start) || state.linkLevel > 0)) return false;
        const labelStart = start + (embedded ? 3 : 2);
        const end = state.src.indexOf(']]', labelStart);
        if (end < 0 || end + 2 > state.posMax) return false;
        const label = state.src.slice(labelStart, end);
        if (/[\r\n]/.test(label)) return false;
        const separator = label.indexOf('|');
        let target = (separator < 0 ? label : label.slice(0, separator)).trim();
        const alias = separator < 0 ? target : label.slice(separator + 1);
        if (!target) return false;

        if (!embedded) {
            // Keep fragments and existing extensions, including same-document anchors.
            target = target.replace(/^([^?#]+)(.*)$/, function(_, path, suffix) {
                return path + (/\.(?:md|markdown)$/i.test(path) ? '' : '.md') + suffix;
            });
        }
        const href = md.normalizeLink(target);
        if (!md.validateLink(href)) return false;

        if (!silent) {
            const extension = target.split(/[?#]/)[0].split('.').pop().toLowerCase();
            if (embedded && ['mp4', 'webm', 'ogg', 'mp3', 'wav', 'flac'].includes(extension)) {
                const tag = ['mp4', 'webm', 'ogg'].includes(extension) ? 'video' : 'audio';
                const token = state.push('obsidian_media', tag, 0);
                token.attrs = [['src', href], ['controls', ''], ['title', alias]];
            } else if (embedded) {
                const token = state.push('image', 'img', 0);
                token.attrs = [['src', href], ['alt', '']];
                const text = new state.Token('text', '', 0);
                text.content = alias;
                token.children = [text];
            } else {
                state.push('link_open', 'a', 1).attrs = [['href', href]];
                state.push('text', '', 0).content = alias;
                state.push('link_close', 'a', -1);
            }
        }
        state.pos = end + 2;
        return true;
    });
    md.renderer.rules.obsidian_media = function(tokens, idx, options, env, self) {
        const token = tokens[idx];
        return '<' + token.tag + self.renderAttrs(token) + '></' + token.tag + '>';
    };

    let rawMarkdown = decodeURIComponent(escape(atob(window.RAW_MARKDOWN_BASE64)));
    // Strip Obsidian YAML front-matter.
    rawMarkdown = rawMarkdown.replace(/^---\r?\n[\s\S]*?\r?\n---(?:\r?\n|$)/, '');

    if (window.IS_DARK) {
        document.body.classList.add('dark-mode');
    }

    if (!window.DOMPurify || !window.DOMPurify.isSupported) {
        throw new Error('HTML sanitizer is unavailable.');
    }
    document.getElementById('content').replaceChildren(window.DOMPurify.sanitize(md.render(rawMarkdown), {
        RETURN_DOM_FRAGMENT: true,
        // Preserve local documents/media while rejecting executable URL schemes.
        ALLOWED_URI_REGEXP: /^(?:(?:https?|file|content|mailto|tel):|[^a-z]|[a-z+.\-]+(?:[^a-z+.\-:]|$))/i,
        ADD_TAGS: ['eq', 'eqn'], // Wrappers used by markdown-it-texmath's stylesheet.
        FORBID_TAGS: ['style', 'form']
    }));

    if (window.mermaid) {
        const isDarkMode = window.IS_DARK === true;
        window.mermaid.initialize({
            startOnLoad: false,
            securityLevel: 'strict',
            theme: isDarkMode ? 'dark' : 'default'
        });
        window.mermaid.run({ querySelector: '.mermaid' }).catch(console.error);
    }
} catch (e) {
    document.getElementById('content').textContent = 'Could not render document: ' + e;
}
