'use strict';
// Loaded by the Frida REPL, which supplies the Java bridge in Frida 17.
const LIMIT = 65536;
const SESSION_ID = Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10);
let counter = 0;
let attempts = 0;
let installed = false;
function emit(event) { console.log('DDH_HTTP_EVENT:' + JSON.stringify(event)); }
function headers(value) {
    const result = [];
    for (let i = 0; i < value.size(); i++) result.push({ name: String(value.name(i)), value: String(value.value(i)) });
    return result;
}
function install() {
    if (installed) return;
    if (typeof Java === 'undefined' || !Java.available) {
        emit({ kind: 'unsupported', message: 'Java bridge недоступен. Используйте frida-tools с поддержкой Frida 17; нужен Android Java-процесс.' });
        return;
    }
    Java.perform(function () {
        try {
            const Bridge = Java.use('okhttp3.internal.http.BridgeInterceptor');
            const AndroidProcess = Java.use('android.os.Process');
            const ActivityThread = Java.use('android.app.ActivityThread');
            const Base64 = Java.use('android.util.Base64');
            const JString = Java.use('java.lang.String');
            const uid = Number(AndroidProcess.myUid());
            function metadata() {
                const app = ActivityThread.currentApplication();
                return { packageName: app ? String(app.getPackageName()) : String(ActivityThread.currentPackageName() || ''), uid: String(uid), appId: String(uid % 100000) };
            }
            function issue(id, stage, error, request, raw) {
                let info = {};
                try { info = metadata(); } catch (_) { }
                let url = '', method = 'RAW';
                try { if (request) { url = String(request.url()); method = String(request.method()); } } catch (_) { }
                emit(Object.assign({ kind: 'diagnostic', id: id, source: 'Frida · OkHttp', stage: stage,
                    method: method, url: url, message: 'Ошибка наблюдения OkHttp', error: String(error),
                    stackTrace: error.stack || String(error), rawAvailable: !!(raw && raw.body),
                    rawBody: raw ? raw.body : '', rawEncoding: raw ? raw.encoding : 'text' }, info));
            }
            function bytesBody(bytes) {
                const text = String(JString.$new(bytes, 'UTF-8'));
                const roundtrip = JString.$new(text).getBytes('UTF-8');
                let same = roundtrip.length === bytes.length;
                for (let i = 0; same && i < bytes.length; i++) same = roundtrip[i] === bytes[i];
                return same ? { body: text, encoding: 'text', complete: true } : { body: String(Base64.encodeToString(bytes, 2)), encoding: 'base64', complete: true };
            }
            function requestBody(body) {
                if (body === null) return { body: '', encoding: 'text', complete: true, hasBody: false };
                const missing = { body: '', encoding: 'text', complete: false, hasBody: true };
                if (Number(body.contentLength()) > LIMIT) return missing;
                try { if (body.isDuplex() || body.isOneShot()) return missing; } catch (_) { }
                const name = String(body.getClass().getName());
                // Never call a custom RequestBody.writeTo(): serializers and one-shot streams must execute only once.
                if (name === 'okhttp3.FormBody') {
                    const form = Java.cast(body, Java.use('okhttp3.FormBody'));
                    const pairs = [];
                    for (let i = 0; i < form.size(); i++) pairs.push(String(form.encodedName(i)) + '=' + String(form.encodedValue(i)));
                    const text = pairs.join('&');
                    return text.length <= LIMIT ? { body: text, encoding: 'text', complete: true, hasBody: true } : missing;
                }
                if (name.indexOf('okhttp3.RequestBody$') !== 0) return missing;
                const fields = body.getClass().getDeclaredFields();
                let data = null, arrayObject = null, offset = 0, count = -1;
                const ReflectArray = Java.use('java.lang.reflect.Array');
                for (let i = 0; i < fields.length; i++) {
                    const field = fields[i]; field.setAccessible(true);
                    const type = String(field.getType().getName()); const key = String(field.getName());
                    if (type === '[B') arrayObject = field.get(body);
                    else if (type === 'okio.ByteString') data = Java.cast(field.get(body), Java.use('okio.ByteString')).toByteArray();
                    else if (type === 'int' && /offset/i.test(key)) offset = field.getInt(body);
                    else if (type === 'int' && /byteCount|length/i.test(key)) count = field.getInt(body);
                }
                if (arrayObject !== null) {
                    const length = ReflectArray.getLength(arrayObject);
                    if (length > LIMIT * 4) return missing;
                    data = [];
                    for (let i = 0; i < length; i++) data.push(ReflectArray.getByte(arrayObject, i));
                }
                if (data === null || data.length > LIMIT * 4) return missing;
                if (count < 0) count = data.length - offset;
                if (count > LIMIT || offset < 0 || count < 0 || offset + count > data.length) return missing;
                const slice = [];
                for (let i = offset; i < offset + count; i++) slice.push(data[i]);
                return Object.assign(bytesBody(Java.array('byte', slice)), { hasBody: true });
            }
            const intercept = Bridge.intercept.overload('okhttp3.Interceptor$Chain');
            intercept.implementation = function (chain) {
                const id = SESSION_ID + '-' + Process.id + '-' + (++counter);
                const start = Date.now();
                let sent = false;
                let observedRequest = null;
                try {
                    const request = chain.request(); const body = request.body();
                    observedRequest = request;
                    let payload, bodyError = null;
                    try { payload = requestBody(body); } catch (error) { bodyError = error; payload = { body: '', encoding: 'text', complete: false, hasBody: body !== null }; }
                    const requestHeaders = headers(request.headers());
                    if (body !== null && body.contentType() !== null && !requestHeaders.some(h => h.name.toLowerCase() === 'content-type'))
                        requestHeaders.push({ name: 'Content-Type', value: String(body.contentType()) });
                    emit(Object.assign({ kind: 'request', id: id, startedAt: new Date(start).toISOString(), method: String(request.method()), url: String(request.url()), headers: requestHeaders }, metadata(), payload));
                    sent = true;
                    if (bodyError) issue(id, 'okhttp-request-body', bodyError, request);
                } catch (error) { issue(id, 'okhttp-request-metadata', error, observedRequest); }
                let response;
                try { response = intercept.call(this, chain); }
                catch (error) { emit({ kind: 'error', id: id, source: 'Frida · OkHttp', message: String(error), stackTrace: error.stack || String(error), stage: 'okhttp-call', durationMs: Date.now() - start }); throw error; }
                if (sent) {
                    let payload = { body: '', encoding: 'text', complete: false };
                    try {
                        const body = response.body();
                        const length = body === null ? 0 : Number(body.contentLength());
                        if (length === 0) payload = { body: '', encoding: 'text', complete: true };
                        // Unknown/streaming body lengths are not eagerly consumed; this preserves streaming behaviour.
                        else if (length > 0 && length <= LIMIT) payload = bytesBody(response.peekBody(LIMIT + 1).bytes());
                    } catch (error) { issue(id, 'okhttp-response-body', error, observedRequest); }
                    try { emit(Object.assign({ kind: 'response', id: id, status: Number(response.code()), statusText: String(response.message()), httpVersion: String(response.protocol()), headers: headers(response.headers()), durationMs: Date.now() - start }, payload)); }
                    catch (error) { issue(id, 'okhttp-response-metadata', error, observedRequest, payload); }
                }
                return response;
            };
            installed = true;
            emit({ kind: 'ready', message: 'OkHttp 3/4 · захват запросов и ответов включён' });
        } catch (error) {
            if (++attempts < 20) setTimeout(install, 1000);
            else emit({ kind: 'unsupported', message: 'OkHttp 3/4 не найден или недоступен: ' + error });
        }
    });
}
setImmediate(install);
