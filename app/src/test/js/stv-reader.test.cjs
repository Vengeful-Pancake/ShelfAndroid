const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const script = fs.readFileSync(path.join(__dirname, '../../main/assets/stv-reader.js'), 'utf8');

function harness() {
    const nodes = [], requests = [], timers = new Map();
    let observer, serial = 0;
    const bridge = {postMessage: raw => requests.push(JSON.parse(raw))};
    const context = vm.createContext({
        window: {ShelfTranslation: bridge}, ShelfTranslation: bridge,
        NodeFilter: {SHOW_TEXT: 4}, CustomEvent: class {constructor(name) {this.type = name;}},
        MutationObserver: class {constructor(fn) {observer = fn;} observe() {} disconnect() {}},
        setTimeout: fn => {timers.set(++serial, fn); return serial;},
        clearTimeout: id => timers.delete(id),
        document: {
            body: {}, dispatchEvent() {},
            createTreeWalker() {let i = 0; return {nextNode: () => nodes[i++]};},
        },
    });
    return {
        nodes, requests,
        add(text, ignored = false) {
            const node = {textContent: text, isConnected: true, parentElement: {closest: () => ignored}};
            nodes.push(node); return node;
        },
        run() {vm.runInContext(script, context);},
        flush() {const jobs = [...timers.values()]; timers.clear(); jobs.forEach(fn => fn());},
        mutate() {observer();},
        reply(texts) {bridge.onmessage({data: JSON.stringify({id: requests.at(-1).id, texts})});},
        dispose() {context.window.shelfTranslator.dispose();},
    };
}

test('translates text nodes, skips controls and scripts, and restores Chinese', () => {
    const h = harness();
    const content = h.add('你好');
    h.add('中文', true); h.add('English');
    h.run();
    assert.deepEqual(h.requests[0].texts, ['你好']);
    h.reply(['xin chào']); h.flush();
    assert.equal(content.textContent, 'xin chào');
    assert.equal(h.requests.length, 1);
    h.dispose(); assert.equal(content.textContent, '你好');
});

test('new chapter content is translated after DOM mutation without retranslating existing text', () => {
    const h = harness(); h.add('你好'); h.run(); h.reply(['xin chào']); h.flush();
    const next = h.add('世界'); h.mutate(); h.flush();
    assert.deepEqual(h.requests[1].texts, ['世界']);
    h.reply(['thế giới']); h.flush();
    assert.equal(next.textContent, 'thế giới');
});

test('late replies cannot overwrite source changed by the website', () => {
    const h = harness(); const node = h.add('你好'); h.run();
    node.textContent = '世界'; h.reply(['xin chào']); h.flush();
    assert.equal(node.textContent, '世界');
    assert.deepEqual(h.requests[1].texts, ['世界']);
});

test('batches large chapter lists', () => {
    const h = harness(); for (let i = 0; i < 40; ++i) h.add('你好');
    h.run(); assert.equal(h.requests[0].texts.length, 32);
    h.reply(Array(32).fill('xin chào')); h.flush();
    assert.equal(h.requests[1].texts.length, 8);
});
