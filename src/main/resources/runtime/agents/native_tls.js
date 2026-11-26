'use strict';
// Observe successful plaintext TLS I/O. No TLS validation, return values or buffers are modified.
const hooked = new Map();
const depth = new Map();
const epoch = Date.now().toString(36);
const channels = new Map();
let nextChannel = 0;
let sequence = 0;
function size(pointer) { return Process.pointerSize === 8 ? Number(pointer.readU64()) : pointer.readU32(); }
function observe(module) {
    let added = 0;
    const getFdAddress = module.findExportByName('SSL_get_fd');
    const sniAddress = module.findExportByName('SSL_get_servername');
    const getFd = getFdAddress ? new NativeFunction(getFdAddress, 'int', ['pointer']) : null;
    const getSni = sniAddress ? new NativeFunction(sniAddress, 'pointer', ['pointer', 'int']) : null;
    for (const name of ['SSL_read', 'SSL_write', 'SSL_read_ex', 'SSL_write_ex']) {
        const address = module.findExportByName(name);
        if (!address || hooked.has(address.toString())) continue;
        const direction = name.indexOf('write') !== -1 ? 'tx' : 'rx';
        const extended = name.endsWith('_ex');
        const listener = Interceptor.attach(address, {
            onEnter(args) {
                this.ssl = args[0]; this.buffer = args[1]; this.count = extended ? Number(args[2].toString()) : args[2].toInt32();
                this.outLength = extended ? args[3] : null;
                this.key = Process.getCurrentThreadId() + ':' + direction;
                const nesting = depth.get(this.key) || 0;
                this.outer = nesting === 0; depth.set(this.key, nesting + 1);
            },
            onLeave(result) {
                const nesting = (depth.get(this.key) || 1) - 1;
                if (nesting === 0) depth.delete(this.key); else depth.set(this.key, nesting);
                if (!this.outer) return;
                try {
                    const count = extended ? (result.toInt32() === 1 ? size(this.outLength) : 0) : result.toInt32();
                    if (count <= 0 || count > this.count) return;
                    const pointer = this.ssl.toString();
                    if (!channels.has(pointer)) channels.set(pointer, epoch + ':' + (++nextChannel));
                    const channel = channels.get(pointer);
                    if (count > 8 * 1024 * 1024) { send({ kind: 'tls_gap', channel: channel, direction: direction, message: 'TLS-фрагмент превысил предел чтения агента: ' + count + ' байт' }); return; }
                    let host = '', peer = null;
                    try { if (getSni) { const p = getSni(this.ssl, 0); if (!p.isNull()) host = p.readUtf8String(); } } catch (_) { }
                    try { if (getFd) peer = Socket.peerAddress(getFd(this.ssl)); } catch (_) { }
                    for (let offset = 0; offset < count; offset += 65536) {
                        const length = Math.min(65536, count - offset);
                        send({ kind: 'tls_data', channel: channel, direction: direction, host: host, peer: peer, sequence: ++sequence }, this.buffer.add(offset).readByteArray(length));
                    }
                } catch (error) { const channel = channels.get(this.ssl.toString()); if (channel) send({ kind: 'tls_gap', channel: channel, direction: direction, message: String(error), stackTrace: error.stack || '' }); }
            }
        });
        hooked.set(address.toString(), { module: module.path, listener: listener }); added++;
    }
    const free = module.findExportByName('SSL_free');
    if (free && !hooked.has(free.toString())) {
        const listener = Interceptor.attach(free, { onEnter(args) { const key = args[0].toString(); if (channels.has(key)) { send({ kind: 'tls_close', channel: channels.get(key) }); channels.delete(key); } } });
        hooked.set(free.toString(), { module: module.path, listener: listener });
    }
    if (added) {
        send({ kind: 'status', message: 'Native TLS: ' + module.name + ', функций I/O: ' + added });
        send({ kind: 'capability', hooks: hooked.size, capability: 'native-tls' });
    }
}
Process.attachModuleObserver({
    onAdded: observe,
    onRemoved(module) {
        for (const [address, value] of hooked) if (value.module === module.path) { try { value.listener.detach(); } catch (_) { } hooked.delete(address); }
    }
});
try { const uid = new NativeFunction(Module.getGlobalExportByName('getuid'), 'uint', [])(); send({ kind: 'identity', uid: String(uid) }); } catch (_) { }
send({ kind: 'status', message: 'Native TLS: наблюдение включено. Если TLS-функции скрыты или статически связаны, используйте прокси/VPN.' });
