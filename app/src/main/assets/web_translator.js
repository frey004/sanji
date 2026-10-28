// Se inyecta en cada página. Recoge los textos visibles, se los pasa a la app
// (TrBridge) para traducirlos con ML Kit y luego sustituye cada texto por su
// traducción. Vigila los cambios del DOM para traducir también el contenido
// que se carga después (scroll infinito, menús, comentarios…).
(function () {
    if (window.__tr) {
        window.__tr.scan();
        return;
    }

    var SKIP = { SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, TEXTAREA: 1, CODE: 1, PRE: 1, svg: 1, SVG: 1, MATH: 1, TEMPLATE: 1, IFRAME: 1 };
    var LETTER = /[A-Za-zÀ-ɏͰ-ϿЀ-ӿऀ-ॿ぀-ヿ㐀-鿿가-힯]/;
    var BATCH = 40;

    var nodes = [];          // id -> nodo de texto
    var originals = [];      // id -> texto original
    var translated = [];     // id -> texto traducido
    var seen = new WeakSet();
    var showOriginal = false;
    var timer = null;

    function skipped(el) {
        for (; el && el !== document.body; el = el.parentNode) {
            if (el.nodeType !== 1) continue;
            if (SKIP[el.nodeName] || el.isContentEditable || el.getAttribute('translate') === 'no' ||
                (el.classList && el.classList.contains('notranslate'))) return true;
        }
        return false;
    }

    function collect(root) {
        var batch = [];
        if (!root) return batch;
        var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
        var node;
        while ((node = walker.nextNode())) {
            if (seen.has(node)) continue;
            var text = node.nodeValue;
            if (!text || !LETTER.test(text) || text.trim().length < 2) continue;
            if (skipped(node.parentNode)) continue;
            seen.add(node);
            var id = nodes.length;
            nodes.push(node);
            originals.push(text);
            batch.push({ id: id, text: text.trim() });
        }
        return batch;
    }

    function send(items) {
        for (var i = 0; i < items.length; i += BATCH) {
            TrBridge.translate(JSON.stringify(items.slice(i, i + BATCH)));
        }
    }

    function withSpaces(original, text) {
        var lead = original.match(/^\s*/)[0];
        var trail = original.match(/\s*$/)[0];
        return lead + text + trail;
    }

    function scan() {
        var items = collect(document.body);
        if (document.title && !seen.has(document)) {
            seen.add(document);
            // id -1 reservado para el título de la pestaña.
            items.unshift({ id: -1, text: document.title });
        }
        if (items.length) send(items);
    }

    window.__tr = {
        scan: scan,
        apply: function (results) {
            for (var i = 0; i < results.length; i++) {
                var r = results[i];
                if (r.id === -1) {
                    if (!showOriginal) document.title = r.text;
                    continue;
                }
                var node = nodes[r.id];
                if (!node) continue;
                translated[r.id] = withSpaces(originals[r.id], r.text);
                if (!showOriginal) node.nodeValue = translated[r.id];
            }
        },
        setOriginal: function (original) {
            showOriginal = original;
            for (var i = 0; i < nodes.length; i++) {
                if (translated[i] === undefined) continue;
                nodes[i].nodeValue = original ? originals[i] : translated[i];
            }
        }
    };

    new MutationObserver(function () {
        clearTimeout(timer);
        timer = setTimeout(scan, 600);
    }).observe(document.body, { childList: true, subtree: true });

    scan();
})();
