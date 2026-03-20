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
            return '<div class="mermaid">' + token.content + '</div>';
        }
        return defaultRender(tokens, idx, options, env, self);
    };
    
    let rawMarkdown = decodeURIComponent(escape(atob(window.RAW_MARKDOWN_BASE64)));
    
    // Strip Obsidian YAML front-matter
    rawMarkdown = rawMarkdown.replace(/^---\n[\s\S]*?\n---\n/, '');
    
    // Parse ![[media.png]]
    rawMarkdown = rawMarkdown.replace(/!\[\[(.*?)\]\]/g, function(match, link) {
        let parts = link.split('|');
        let target = parts[0];
        let alias = parts.length > 1 ? parts[1] : target;
        let ext = target.split('.').pop().toLowerCase();
        if (['png', 'jpg', 'jpeg', 'gif', 'svg', 'webp', 'bmp'].includes(ext)) {
            return '![' + alias + '](' + encodeURI(target) + ')';
        } else if (['mp4', 'webm', 'ogg'].includes(ext)) {
            return '<video src="' + encodeURI(target) + '" controls title="' + alias + '" style="max-width:100%;"></video>';
        } else if (['mp3', 'wav', 'flac'].includes(ext)) {
            return '<audio src="' + encodeURI(target) + '" controls title="' + alias + '"></audio>';
        }
        return '![' + alias + '](' + encodeURI(target) + ')';
    });
    
    // Parse [[Wikilinks]]
    rawMarkdown = rawMarkdown.replace(/\[\[(.*?)\]\]/g, function(match, link) {
        let parts = link.split('|');
        let target = parts[0];
        let alias = parts.length > 1 ? parts[1] : target;
        return '[' + alias + '](' + encodeURI(target) + '.md)';
    });
    
    document.getElementById('content').innerHTML = md.render(rawMarkdown);
    
    if (window.mermaid) {
        const isDarkMode = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
        window.mermaid.initialize({
            startOnLoad: false,
            theme: isDarkMode ? 'dark' : 'default'
        });
        window.mermaid.run({ querySelector: '.mermaid' });
    }
} catch (e) {
    document.getElementById('content').innerHTML = '<h1>Error</h1><p>Could not load markdown-it or its plugins.</p><pre>' + e + '</pre>';
}
