// Android adaptation of src/inject/inject.js: text-node translation, source retention,
// and mutation-driven updates. Translation uses the shared local QT engine.
(function () {
    if (window.shelfTranslator) window.shelfTranslator.dispose();
    if (!window.ShelfTranslation || !document.body) return;
    const originals = new Map();
    const rendered = new WeakMap();
    let pending = null, timer, stopped = false, counter = 0;
    const session = Math.random().toString(36).slice(2);
    const ignored = 'script,style,noscript,textarea,input,select,option,code,pre,[contenteditable="true"]';
    function scan() {
        if (stopped || pending || !document.body) return;
        const nodes = [], texts = [];
        const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
        let node, size = 0;
        while ((node = walker.nextNode())) {
            if (!node.parentElement || node.parentElement.closest(ignored)) continue;
            const text = node.textContent;
            if (rendered.get(node) === text || !/[\u3400-\u9fff\uf900-\ufaff]/.test(text)) continue;
            if (text.length > 32000) continue;
            if (texts.length && size + text.length > 32000) break;
            nodes.push(node); texts.push(text); size += text.length;
            if (nodes.length >= 32) break;
        }
        if (!nodes.length) {
            document.dispatchEvent(new CustomEvent('shelf-translated'));
            return;
        }
        const id = session + ':' + (++counter);
        pending = {id, nodes, texts};
        ShelfTranslation.postMessage(JSON.stringify({id, texts}));
    }
    ShelfTranslation.onmessage = function (event) {
        let reply;
        try { reply = JSON.parse(event.data); } catch (_) { return; }
        if (stopped || !pending || reply.id !== pending.id) return;
        const batch = pending; pending = null;
        if (reply.error) {
            document.dispatchEvent(new CustomEvent('shelf-translation-error', {detail: reply.error}));
            return;
        }
        batch.nodes.forEach((node, i) => {
            if (!node.isConnected || node.textContent !== batch.texts[i] || typeof reply.texts[i] !== 'string') return;
            if (!originals.has(node)) originals.set(node, batch.texts[i]);
            rendered.set(node, reply.texts[i]);
            node.textContent = reply.texts[i];
        });
        timer = setTimeout(scan, 30);
    };
    const observer = new MutationObserver(() => {
        clearTimeout(timer); timer = setTimeout(scan, 150);
    });
    observer.observe(document.body, {subtree: true, childList: true, characterData: true});
    window.shelfTranslator = {
        originalText: function(node) { return originals.get(node) || node.orgn || node.textContent; },
        dispose: function () {
            stopped = true; clearTimeout(timer); observer.disconnect();
            originals.forEach((text, node) => {
                if (node.isConnected && rendered.get(node) === node.textContent) node.textContent = text;
            });
            originals.clear();
        }
    };
    scan();
})();
