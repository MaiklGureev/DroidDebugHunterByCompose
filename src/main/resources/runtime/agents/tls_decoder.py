"""Bounded passive HTTP/1 + HTTP/2 reconstruction from plaintext TLS directions."""
import base64
from collections import deque, OrderedDict
from datetime import datetime, timezone
import time
import traceback
import h11
from hpack import Decoder
from capture_diagnostics import RAW_LIMIT, raw_fields

LIMIT = 1024 * 1024
PREFACE = b"PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"


def payload(data, complete=True):
    try:
        return {"body": bytes(data).decode("utf-8"), "encoding": "text", "complete": complete}
    except UnicodeDecodeError:
        return {"body": base64.b64encode(data).decode("ascii"), "encoding": "base64", "complete": complete}


def headers(values):
    return [{"name": k.decode("ascii", "replace"), "value": v.decode("utf-8", "replace")} for k, v in values]


class Body:
    def __init__(self):
        self.data = bytearray()
        self.truncated = False

    def add(self, value):
        remaining = LIMIT - len(self.data)
        self.data.extend(value[:remaining])
        self.truncated |= len(value) > remaining

    def event(self, complete=True):
        return payload(self.data, complete and not self.truncated)


class Channel:
    def __init__(self, key, emit, identity):
        self.key = key
        self.emit = emit
        self.identity = identity
        self.host = ""
        self.protocol = None
        self.prelude = {"tx": bytearray(), "rx": bytearray()}
        self.ordinal = 0
        self.pending = deque()
        self.request = None
        self.response = None
        self.request_body = None
        self.response_body = None
        self.req_conn = h11.Connection(h11.SERVER, max_incomplete_event_size=65536)
        self.res_conn = h11.Connection(h11.CLIENT, max_incomplete_event_size=65536)
        self.response_primed = False
        self.h2_buffer = {"tx": bytearray(), "rx": bytearray()}
        self.hpack = {"tx": Decoder(max_header_list_size=65536), "rx": Decoder(max_header_list_size=65536)}
        self.h2_blocks = {}
        self.streams = {}
        self.raw_history = {"tx": bytearray(), "rx": bytearray()}
        self.raw_bytes = {"tx": 0, "rx": 0}
        self.peer = None

    def snapshot(self, direction):
        sample = self.raw_history.get(direction, b"")
        total = self.raw_bytes.get(direction, 0)
        if not total: return raw_fields()
        return raw_fields(sample, total, max(0, total - len(sample)))

    def raw(self, direction, data=b"", reason="Нераспознанный протокол TLS", stack_trace="", stage="protocol", error=""):
        fields = self.snapshot(direction)
        if not fields['rawBytes'] and data:
            fields = raw_fields(data)
        body, encoding = fields.pop('rawBody'), fields.pop('rawEncoding')
        self.emit(dict(self.identity, kind="stream", id=self.key + ":raw:" + direction, source="Frida · Native TLS", direction=direction,
                       url="tls://" + (self.host or "unknown.invalid"), message=reason, error=error,
                       stage=stage, stackTrace=stack_trace, peer=self.peer, channel=self.key,
                       body=body, encoding=encoding, complete=False, **fields))

    def request_event(self, method, target, values, version, stream=None, complete=False):
        self.ordinal += 1
        fields = dict(values)
        authority = fields.get(b":authority", fields.get(b"host", b"")).decode("utf-8", "replace") or self.host
        path = target.decode("utf-8", "replace")
        scheme = fields.get(b":scheme", b"https").decode("ascii", "replace")
        url = path if path.startswith(("http://", "https://")) else (scheme + "://" + authority + (path if path.startswith('/') else '/') if authority else "")
        identifier = self.key + ":" + str(stream if stream is not None else self.ordinal)
        item = {"id": identifier, "method": method.decode("ascii", "replace"), "start": time.monotonic(), "body": Body()}
        event = dict(self.identity, kind="request", id=identifier, method=item["method"], url=url, headers=headers(values), source="Frida · Native TLS",
                     httpVersion=version, startedAt=datetime.now(timezone.utc).isoformat(), hasBody=not complete)
        event.update(payload(b"", complete))
        self.emit(event)
        return item

    def feed(self, direction, data, host="", peer=None):
        if host: self.host = host
        if peer: self.peer = peer
        self.raw_bytes[direction] += len(data)
        history = self.raw_history[direction]
        history.extend(data[-RAW_LIMIT:])
        if len(history) > RAW_LIMIT: del history[:-RAW_LIMIT]
        if self.protocol is None:
            self.prelude[direction].extend(data)
            if len(self.prelude[direction]) > LIMIT:
                self.protocol = "raw"
            tx = bytes(self.prelude['tx'])
            if tx and PREFACE.startswith(tx) and len(tx) < len(PREFACE): return
            if tx.startswith(PREFACE): self.protocol = "h2"
            elif b"\r\n" in tx[:65536] and b" HTTP/1." in tx.split(b"\r\n", 1)[0]: self.protocol = "h1"
            elif b"\r\n" in tx or (tx and not 65 <= tx[0] <= 90): self.protocol = "raw"
            elif len(tx) > 65536: self.protocol = "raw"
            else: return
            first = {d: bytes(v) for d, v in self.prelude.items()}
            self.prelude = {"tx": bytearray(), "rx": bytearray()}
            if self.protocol == "h2": first['tx'] = first['tx'][len(PREFACE):]
            for d in ['tx', 'rx']:
                if first[d]: self._feed(d, first[d])
            return
        self._feed(direction, data)

    def _feed(self, direction, data):
        try:
            if self.protocol == 'h1': self.http1(direction, data)
            elif self.protocol == 'h2': self.http2(direction, data)
            else: self.raw(direction, data)
        except Exception as exc:
            reason = "HTTP-разбор остановлен: " + str(exc)
            stack = traceback.format_exc()
            stage = self.protocol + "-parser"
            self.raw(direction, data, reason, stack, stage, reason)
            self.abort(reason, stack, direction, stage)

    def http1(self, direction, data):
        if direction == 'tx':
            self.req_conn.receive_data(data)
            while True:
                event = self.req_conn.next_event()
                if event is h11.NEED_DATA: break
                if event is h11.PAUSED: break
                if isinstance(event, h11.Request):
                    if len(self.pending) >= 128: raise ValueError("Too many pipelined requests")
                    values = list(event.headers)
                    has_body = any(k == b'transfer-encoding' or (k == b'content-length' and v != b'0') for k, v in values)
                    self.request = self.request_event(event.method, event.target, values, 'HTTP/' + event.http_version.decode(), complete=not has_body)
                    self.request_body = self.request['body']; self.pending.append(self.request)
                elif isinstance(event, h11.Data): self.request_body.add(event.data)
                elif isinstance(event, h11.EndOfMessage):
                    self.emit(dict(kind='request_update', id=self.request['id'], **self.request_body.event()))
                    self.req_conn.send(h11.Response(status_code=200, headers=[(b'Content-Length', b'0')]))
                    self.req_conn.send(h11.EndOfMessage())
                    if self.req_conn.our_state is h11.DONE and self.req_conn.their_state is h11.DONE: self.req_conn.start_next_cycle()
                    else: break
                elif isinstance(event, h11.ConnectionClosed): break
            self.read_responses()
        else:
            self.res_conn.receive_data(data)
            if not self.pending and data:
                self.raw('rx', reason='HTTP-данные ответа без наблюдаемого запроса', stage='h1-orphan')
            self.read_responses()

    def read_responses(self):
        while self.pending:
            if not self.response_primed:
                self.res_conn.send(h11.Request(method=self.pending[0]['method'].encode(), target=b'/', headers=[(b'Host', b'capture.invalid')]))
                self.res_conn.send(h11.EndOfMessage()); self.response_primed = True
            event = self.res_conn.next_event()
            if event is h11.NEED_DATA: break
            if event is h11.PAUSED:
                self.raw('rx', self.res_conn.trailing_data[0], 'После HTTP Upgrade идут бинарные кадры протокола')
                break
            if isinstance(event, h11.InformationalResponse):
                if event.status_code == 101:
                    self.emit(dict(kind='response', id=self.pending[0]['id'], status=101, headers=headers(event.headers), httpVersion='HTTP/1.1', **payload(b'')))
                continue
            if isinstance(event, h11.Response):
                self.response = event; self.response_body = Body()
                self.response_event(False)
            elif isinstance(event, h11.Data): self.response_body.add(event.data)
            elif isinstance(event, h11.EndOfMessage):
                self.response_event(True); self.pending.popleft(); self.response_primed = False
                if self.res_conn.our_state is h11.DONE and self.res_conn.their_state is h11.DONE: self.res_conn.start_next_cycle()
                else: break
            elif isinstance(event, h11.ConnectionClosed): break

    def response_event(self, complete):
        item = self.pending[0]
        self.emit(dict(kind='response', id=item['id'], status=self.response.status_code, statusText=self.response.reason.decode('ascii', 'replace'),
                       httpVersion='HTTP/' + self.response.http_version.decode(), headers=headers(self.response.headers),
                       durationMs=(time.monotonic() - item['start']) * 1000, **self.response_body.event(complete)))

    def http2(self, direction, data):
        buffer = self.h2_buffer[direction]; buffer.extend(data)
        while len(buffer) >= 9:
            length = int.from_bytes(buffer[:3], 'big')
            if length > LIMIT: raise ValueError('HTTP/2 frame too large')
            if len(buffer) < 9 + length: return
            kind, flags = buffer[3], buffer[4]
            stream = int.from_bytes(buffer[5:9], 'big') & 0x7fffffff
            frame = bytes(buffer[9:9 + length]); del buffer[:9 + length]
            key = (direction, stream)
            if kind in (1, 9):
                if kind == 1:
                    padding = frame[0] if flags & 8 else 0
                    offset = (1 if flags & 8 else 0) + (5 if flags & 32 else 0)
                    self.h2_blocks[key] = [bytearray(frame[offset:len(frame) - padding if padding else len(frame)]), bool(flags & 1)]
                else:
                    if key not in self.h2_blocks: raise ValueError('Orphan CONTINUATION')
                    self.h2_blocks[key][0].extend(frame)
                if len(self.h2_blocks[key][0]) > 65536: raise ValueError('HTTP/2 headers too large')
                if not flags & 4: continue
                block, end = self.h2_blocks.pop(key)
                values = self.hpack[direction].decode(bytes(block), raw=True)
                info = self.streams.get(stream)
                if direction == 'tx':
                    if info is None:
                        if len(self.streams) >= 256: raise ValueError('Too many HTTP/2 streams')
                        fields = dict(values)
                        info = self.request_event(fields.get(b':method', b'GET'), fields.get(b':path', b'/'), values, 'HTTP/2', stream, end)
                        info.update(response_body=Body(), response_headers=[], status=None, tx_end=end, rx_end=False)
                        self.streams[stream] = info
                    if end: self.h2_end(stream, direction)
                elif info is not None:
                    fields = dict(values)
                    if b':status' in fields:
                        status = int(fields[b':status'])
                        if status < 200: continue
                        info['status'] = status
                    info['response_headers'].extend(values)
                    self.h2_response(info, end)
                    if end: self.h2_end(stream, direction)
                else:
                    self.raw(direction, reason='HTTP/2 HEADERS без наблюдаемого запроса: stream ' + str(stream), stage='h2-orphan')
            elif kind == 0 and stream in self.streams:
                padding = frame[0] if flags & 8 else 0
                value = frame[1 if flags & 8 else 0:len(frame) - padding if padding else len(frame)]
                self.streams[stream]['body' if direction == 'tx' else 'response_body'].add(value)
                if flags & 1: self.h2_end(stream, direction)
            elif kind == 3 and stream in self.streams:
                self.emit(dict(kind='error', id=self.streams.pop(stream)['id'], message='HTTP/2 RST_STREAM'))
            elif kind == 0:
                self.raw(direction, reason='HTTP/2 DATA без наблюдаемого запроса: stream ' + str(stream), stage='h2-orphan')
            elif kind == 4 and not flags & 1:
                for i in range(0, len(frame) - 5, 6):
                    if int.from_bytes(frame[i:i + 2], 'big') == 1:
                        value = int.from_bytes(frame[i + 2:i + 6], 'big')
                        self.hpack['rx' if direction == 'tx' else 'tx'].max_allowed_table_size = min(value, 65536)

    def h2_response(self, info, complete):
        if info['status'] is None: return
        self.emit(dict(kind='response', id=info['id'], status=info['status'], statusText='', httpVersion='HTTP/2',
                       headers=headers(info['response_headers']), durationMs=(time.monotonic() - info['start']) * 1000,
                       **info['response_body'].event(complete)))

    def h2_end(self, stream, direction):
        info = self.streams[stream]
        info[direction + '_end'] = True
        if direction == 'tx': self.emit(dict(kind='request_update', id=info['id'], **info['body'].event()))
        else: self.h2_response(info, True)
        if info['tx_end'] and info['rx_end']: self.streams.pop(stream, None)

    def close(self):
        if self.protocol is None:
            for direction, data in self.prelude.items(): self.raw(direction, data, 'Начало HTTP-сеанса не попало в захват')
        if self.protocol == 'h1':
            try: self.http1('rx', b'')
            except Exception as exc:
                self.raw('rx', reason="Ошибка завершения HTTP: " + str(exc), stack_trace=traceback.format_exc(),
                         stage="http-eof", error=str(exc))
        self.abort('TLS-соединение закрыто до полного ответа')

    def abort(self, reason, stack_trace="", direction="", stage="capture"):
        for item in list(self.pending) + list(self.streams.values()):
            self.emit(dict(kind='error', id=item['id'], message=reason, stage=stage, stackTrace=stack_trace,
                           direction=direction, peer=self.peer, channel=self.key, **self.snapshot(direction or 'rx')))
        if self.protocol is None:
            emitted = False
            for side in ('tx', 'rx'):
                if self.raw_bytes[side]:
                    self.raw(side, reason=reason, stack_trace=stack_trace, stage=stage, error=reason)
                    emitted = True
            if not emitted: self.raw(direction or 'unknown', reason=reason, stack_trace=stack_trace, stage=stage, error=reason)
        self.pending.clear(); self.streams.clear()
        self.request_body = self.response_body = None
        self.prelude = {'tx': bytearray(), 'rx': bytearray()}
        self.h2_buffer = {'tx': bytearray(), 'rx': bytearray()}
        self.h2_blocks.clear()
        self.protocol = 'raw'

    def buffered_bytes(self):
        items = list(self.pending) + list(self.streams.values())
        bodies = [self.request_body, self.response_body] + [item.get(name) for item in items for name in ('body', 'response_body')]
        unique = {id(body): body for body in bodies if body is not None}
        return (sum(len(body.data) for body in unique.values())
                + sum(len(value) for value in self.raw_history.values())
                + sum(len(v) for buffers in (self.prelude, self.h2_buffer) for v in buffers.values())
                + sum(len(block[0]) for block in self.h2_blocks.values()))


class NativeDecoder:
    def __init__(self, emit, identity):
        self.emit = emit
        self.identity = identity
        self.channels = OrderedDict()

    def feed(self, message, data=None):
        key = message.get('channel')
        if not key: return
        if message['kind'] in ('tls_close', 'tls_gap'):
            channel = self.channels.get(key)
            if channel is None and message['kind'] == 'tls_gap':
                channel = Channel(key, self.emit, self.identity)
                self.channels[key] = channel
            if channel:
                if message.get('host'): channel.host = message['host']
                if message.get('peer'): channel.peer = message['peer']
                if message['kind'] == 'tls_close': channel.close(); self.channels.pop(key, None)
                else: channel.abort(message.get('message') or 'Фрагмент TLS пропущен; HTTP-сеанс неполон',
                                    message.get('stackTrace', ''), message.get('direction', ''), 'tls-capture')
            return
        if key not in self.channels:
            if len(self.channels) >= 128:
                _, old = self.channels.popitem(last=False); old.abort('Лимит соединений захвата')
            self.channels[key] = Channel(key, self.emit, self.identity)
        self.channels.move_to_end(key)
        self.channels[key].feed(message['direction'], data or b'', message.get('host', ''), message.get('peer'))
        while sum(c.buffered_bytes() for c in self.channels.values()) > 64 * 1024 * 1024:
            _, old = self.channels.popitem(last=False); old.abort('Лимит памяти захвата')

    def close(self):
        for channel in self.channels.values(): channel.abort('Захват остановлен до полного ответа')
        self.channels.clear()
