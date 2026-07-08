// Minimal LSP client smoke test for LspSkript.
// Connects to localhost:30505, opens a broken .sk file, and asserts that
// Skript's parser reports at least one diagnostic. No external dependencies.

const net = require('net');

const PORT = parseInt(process.env.LSP_PORT || '30505', 10);
const HOST = process.env.LSP_HOST || '127.0.0.1';
const URI = 'file:///smoke-test.sk';

// A script that is guaranteed to fail Skript's parser.
const BROKEN = [
    'command /test:',
    '    frobnicate the entire universe with a banana',
    ''
].join('\n');

let nextId = 1;
const pending = new Map();
let diagnosticsReceived = null;
let completionReceived = null;

function connect() {
    return new Promise((resolve, reject) => {
        const socket = net.connect(PORT, HOST, () => resolve(socket));
        socket.on('error', reject);
    });
}

function makeClient(socket) {
    let buf = Buffer.alloc(0);

    function write(msg) {
        const json = JSON.stringify(msg);
        const payload = Buffer.from(json, 'utf8');
        const header = Buffer.from(`Content-Length: ${payload.length}\r\n\r\n`, 'utf8');
        socket.write(Buffer.concat([header, payload]));
    }

    socket.on('data', (chunk) => {
        buf = Buffer.concat([buf, chunk]);
        while (true) {
            const sep = buf.indexOf('\r\n\r\n');
            if (sep === -1) break;
            const headerText = buf.slice(0, sep).toString('utf8');
            const m = /Content-Length: (\d+)/i.exec(headerText);
            if (!m) { buf = buf.slice(sep + 4); continue; }
            const len = parseInt(m[1], 10);
            const start = sep + 4;
            if (buf.length < start + len) break;
            const body = buf.slice(start, start + len).toString('utf8');
            buf = buf.slice(start + len);
            let msg;
            try { msg = JSON.parse(body); } catch (e) { continue; }
            handle(msg);
        }
    });

    function handle(msg) {
        if (msg.id !== undefined && pending.has(msg.id)) {
            const resolve = pending.get(msg.id);
            pending.delete(msg.id);
            resolve(msg);
            return;
        }
        if (msg.method === 'textDocument/publishDiagnostics' && msg.params && msg.params.uri === URI) {
            diagnosticsReceived = msg.params.diagnostics || [];
            if (completionReceived !== null) finish();
        }
    }

    function request(method, params) {
        const id = nextId++;
        return new Promise((resolve) => {
            pending.set(id, resolve);
            write({ jsonrpc: '2.0', id, method, params });
        });
    }
    function notify(method, params) {
        write({ jsonrpc: '2.0', method, params });
    }

    return { write, request, notify };
}

function finish() {
    let ok = true;
    if (!diagnosticsReceived || diagnosticsReceived.length === 0) {
        console.error('FAIL: no diagnostics received for broken script');
        ok = false;
    } else {
        console.log(`PASS: received ${diagnosticsReceived.length} diagnostic(s):`);
        for (const d of diagnosticsReceived)
            console.log(`  - [${d.severity}] ${JSON.stringify(d.message)}`);
    }
    if (completionReceived && Array.isArray(completionReceived) && completionReceived.length > 0) {
        console.log(`PASS: completion returned ${completionReceived.length} item(s) (e.g. "${completionReceived[0].label}")`);
    } else {
        console.error('WARN: no completion items returned');
    }
    process.exit(ok ? 0 : 1);
}

async function main() {
    let socket;
    const deadline = Date.now() + 180000; // up to 3 min to connect (server boots slowly)
    while (true) {
        try {
            socket = await connect();
            break;
        } catch (e) {
            if (Date.now() > deadline) {
                console.error('FAIL: could not connect to LspSkript on', `${HOST}:${PORT}`, '-', e.message);
                process.exit(1);
            }
            await new Promise(r => setTimeout(r, 2000));
        }
    }
    console.log('Connected to LspSkript.');

    const client = makeClient(socket);

    const init = await client.request('initialize', {
        processId: process.pid,
        rootUri: null,
        capabilities: {},
        trace: 'off'
    });
    if (!init.result || !init.result.capabilities) {
        console.error('FAIL: initialize returned no capabilities');
        process.exit(1);
    }
    console.log('Initialized. Server capabilities present:', Object.keys(init.result.capabilities).length);

    client.notify('initialized', {});
    client.notify('textDocument/didOpen', {
        textDocument: { uri: URI, languageId: 'skript', version: 1, text: BROKEN }
    });
    console.log('Opened broken script; awaiting diagnostics...');

    // Also probe completion on a separate valid line.
    setTimeout(async () => {
        const comp = await client.request('textDocument/completion', {
            textDocument: { uri: URI },
            position: { line: 1, character: 4 }
        });
        completionReceived = comp && comp.result
            ? (Array.isArray(comp.result) ? comp.result : (comp.result.items || []))
            : [];
        if (diagnosticsReceived !== null) finish();
    }, 3000);

    // Hard timeout safety net.
    setTimeout(() => {
        if (diagnosticsReceived === null) {
            console.error('FAIL: timed out waiting for diagnostics');
            process.exit(1);
        }
    }, 120000);
}

main().catch((e) => { console.error('ERROR', e); process.exit(1); });
