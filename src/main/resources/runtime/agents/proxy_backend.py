"""Local HTTP(S)/WireGuard backend. stdout is a JSONL protocol, never a key/config dump."""
import argparse
import asyncio
import base64
from datetime import datetime, timezone
import json
import logging
from pathlib import Path
import re
import sys
import threading
import traceback
from capture_diagnostics import raw_fields
from capture_setup import atomic_write

MARKER = "DDH_HTTP_EVENT:"
BODY_LIMIT = 1024 * 1024
_output_lock = threading.Lock()


def emit(event):
    with _output_lock:
        print(MARKER + json.dumps(event, ensure_ascii=True), flush=True)


def startup_error(exc):
    message = str(exc)
    if re.search(r"PrivateKey|server_key|client_key", message, re.IGNORECASE):
        return "Не удалось подготовить ключи WireGuard. Проверьте файл ключей в папке данных захвата."
    return re.sub(r"(?<![A-Za-z0-9+/])[A-Za-z0-9+/]{43}=", "[ключ скрыт]", message)[:2000]


def body_event(data, available=True):
    if data is None:
        return {"body": "", "encoding": "text", "complete": False}
    complete = available and len(data) <= BODY_LIMIT
    data = data[:BODY_LIMIT]
    try:
        return {"body": data.decode("utf-8"), "encoding": "text", "complete": complete}
    except UnicodeDecodeError:
        return {"body": base64.b64encode(data).decode("ascii"), "encoding": "base64", "complete": complete}


def filtered_config(config, endpoint, package):
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)*", package):
        raise ValueError("Invalid Android package for VPN")
    if not re.fullmatch(r"(?:[A-Za-z0-9][A-Za-z0-9.\-]*|\[[a-fA-F0-9:]+\]):[0-9]{1,5}", endpoint):
        raise ValueError("Invalid VPN endpoint")
    # Both IP families are routed: do not silently leak the selected app's IPv6 traffic.
    config = re.sub(r"(?m)^Endpoint\s*=.*$", "Endpoint = " + endpoint, config)
    config = re.sub(r"(?m)^AllowedIPs\s*=.*$", "AllowedIPs = 0.0.0.0/0, ::/0", config)
    config = config.replace("[Interface]", "[Interface]\nIncludedApplications = " + package, 1)
    return config + "\n"


class CaptureAddon:
    def __init__(self, args):
        self.args = args
        self.first_vpn_flow = True

    def identity(self):
        return {"source": "VPN · allowlist " + self.args.package if self.args.mode == "wireguard" else "HTTP(S) proxy",
                "packageName": self.args.package if self.args.mode == "wireguard" else "", "uid": "", "appId": ""}

    def diagnostic(self, flow, stage, error, stack_trace="", raw=None, direction=""):
        server = getattr(flow, 'server_conn', None)
        request = getattr(flow, 'request', None)
        address = getattr(server, 'address', None)
        url = "tls://" + str(address[0]) if address else ""
        try:
            if request is not None: url = request.pretty_url
        except Exception: pass
        emit(dict(self.identity(), kind='diagnostic', id=str(flow.id), method='RAW', url=url,
                  message='Ошибка захвата или сетевого соединения', error=str(error), stage=stage,
                  stackTrace=stack_trace, peer=address, channel=str(flow.id), direction=direction, **raw_fields(raw)))

    def running(self):
        from mitmproxy import ctx
        try:
            servers = list(ctx.master.addons.get("proxyserver").servers)
            if not servers:
                raise RuntimeError("Не создан сервер захвата.")
            for server in servers:
                # mitmproxy records bind failures instead of raising them from Master.run().
                # Validate explicitly because raw framework logs can contain VPN private keys.
                if not server.is_running:
                    raise RuntimeError(startup_error(server.last_exception) if server.last_exception else "Сервер не открыл порт захвата.")
            if self.args.mode == "wireguard":
                server = next(s for s in servers if hasattr(s, "client_conf"))
                raw_config = server.client_conf()
                if not raw_config:
                    raise RuntimeError("WireGuard не создал клиентскую конфигурацию.")
                config = filtered_config(raw_config, self.args.endpoint, self.args.package)
                target = Path(self.args.config_out)
                target.parent.mkdir(parents=True, exist_ok=True)
                atomic_write(target, config.encode("utf-8"))
        except Exception as exc:
            emit({"kind": "unsupported", "message": "Прокси/VPN не запущен: " + startup_error(exc)})
            ctx.master.shutdown()
            return
        emit({"kind": "ready", "message": "VPN-сервер готов; импортируйте конфигурацию и включите туннель на Android." if self.args.mode == "wireguard" else "HTTP(S)-прокси готов.",
              "certificate": str(Path(ctx.options.confdir) / "mitmproxy-ca-cert.cer")})

    def request(self, flow):
        try: self.capture_request(flow)
        except Exception as error:
            self.diagnostic(flow, 'proxy-request', error, traceback.format_exc(), getattr(flow.request, 'raw_content', None), 'tx')

    def capture_request(self, flow):
        event = {"kind": "request", "id": flow.id, "method": flow.request.method, "url": flow.request.pretty_url,
                 "startedAt": datetime.fromtimestamp(flow.request.timestamp_start, timezone.utc).isoformat(),
                 "httpVersion": flow.request.http_version, "headers": [{"name": k, "value": v} for k, v in flow.request.headers.items(multi=True)],
                 "hasBody": bool(flow.request.raw_content) or "content-length" in flow.request.headers,
                 "source": "VPN · WireGuard" if self.args.mode == "wireguard" else "HTTP(S) proxy",
                 # An ordinary proxy cannot infer Android package/UID from an IP connection.
                 "packageName": "", "appId": "", "uid": ""}
        # VPN metadata is scoped to an explicitly allowlisted client configuration; label its provenance.
        if self.args.mode == "wireguard":
            event["packageName"] = self.args.package
            event["source"] = "VPN · allowlist " + self.args.package
            if self.first_vpn_flow:
                emit({"kind": "status", "message": "VPN-трафик поступает. Пакет указан по allowlist конфигурации, UID из IP-пакета не определяется."})
                self.first_vpn_flow = False
        event.update(body_event(flow.request.raw_content))
        emit(event)

    def response(self, flow):
        try: self.capture_response(flow)
        except Exception as error:
            self.diagnostic(flow, 'proxy-response', error, traceback.format_exc(), getattr(flow.response, 'raw_content', None), 'rx')

    def capture_response(self, flow):
        # Response content is only for display, so mitmproxy may decode compression for readability.
        try:
            data = flow.response.content
        except Exception as error:
            data = flow.response.raw_content
            self.diagnostic(flow, 'response-decompression', error, traceback.format_exc(), data, 'rx')
        event = {"kind": "response", "id": flow.id, "status": flow.response.status_code,
                 "statusText": flow.response.reason, "httpVersion": flow.response.http_version,
                 "headers": [{"name": k, "value": v} for k, v in flow.response.headers.items(multi=True)],
                 "durationMs": max(0, ((flow.response.timestamp_end or flow.response.timestamp_start) - flow.request.timestamp_start) * 1000)}
        event.update(body_event(data))
        emit(event)

    def error(self, flow):
        request = getattr(flow, 'request', None)
        self.diagnostic(flow, 'http-flow', flow.error, raw=getattr(request, 'raw_content', None))

    def tls_failed_client(self, data):
        self.tls_failure(data, 'tls-client')

    def tls_failed_server(self, data):
        self.tls_failure(data, 'tls-server')

    def tls_failure(self, data, stage):
        conn = data.conn
        server = data.context.server
        address = server.address
        host = getattr(conn, 'sni', None) or (address[0] if address else '')
        emit(dict(self.identity(), kind='diagnostic', id='tls-' + str(conn.id), method='TLS',
                  url='tls://' + host if host else '', channel=str(conn.id), peer=address,
                  stage=stage, error=str(conn.error or 'TLS handshake failed'),
                  message='TLS не установлен: расшифрованных HTTP-данных нет. Проверьте CA и настройки TLS приложения.',
                  stackTrace='', **raw_fields()))

    def raw_message(self, flow, protocol):
        item = flow.messages[-1]
        address = flow.server_conn.address
        fields = raw_fields(item.content)
        body, encoding = fields.pop('rawBody'), fields.pop('rawEncoding')
        host = str(address[0]) if address else ''
        if ':' in host: host = '[' + host + ']'
        emit(dict(self.identity(), kind='stream', id=str(flow.id) + ':message:' + str(len(flow.messages)), method=protocol.upper(),
                  url=protocol + '://' + host + (':' + str(address[1]) if address else ''),
                  stage=protocol + '-message', message='Данные соединения, не разобранные как HTTP', channel=str(flow.id),
                  direction='tx' if item.from_client else 'rx', peer=address, body=body, encoding=encoding, complete=False, **fields))

    def tcp_message(self, flow): self.raw_message(flow, 'tcp')
    def udp_message(self, flow): self.raw_message(flow, 'udp')
    def tcp_error(self, flow): self.diagnostic(flow, 'tcp-error', flow.error)
    def udp_error(self, flow): self.diagnostic(flow, 'udp-error', flow.error)


async def main(args):
    from mitmproxy import options
    from mitmproxy.tools.dump import DumpMaster
    # In particular, WireGuard server info contains private keys. Never log it to the UI/console.
    logging.disable(logging.CRITICAL)
    opts = options.Options(listen_host="0.0.0.0" if args.mode == "wireguard" else "127.0.0.1",
                           listen_port=args.port, confdir=args.confdir,
                           mode=[("wireguard:" + args.keyfile) if args.mode == "wireguard" else "regular"])
    master = DumpMaster(opts, with_termlog=False, with_dumper=False)
    master.options.update(body_size_limit="8m")
    if args.upstream_ca:
        master.options.update(ssl_verify_upstream_trusted_ca=args.upstream_ca)
    master.addons.add(CaptureAddon(args))
    loop = asyncio.get_running_loop()

    def control():
        # EOF also shuts down a backend whose Windows owner disappeared.
        sys.stdin.readline()
        loop.call_soon_threadsafe(master.shutdown)

    threading.Thread(target=control, daemon=True).start()
    await master.run()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=["regular", "wireguard"], default="regular")
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--confdir", required=True)
    parser.add_argument("--endpoint", default="")
    parser.add_argument("--package", default="")
    parser.add_argument("--config-out", default="")
    parser.add_argument("--keyfile", default="")
    parser.add_argument("--upstream-ca", default="")
    try:
        asyncio.run(main(parser.parse_args()))
    except Exception as exc:
        emit({"kind": "unsupported", "message": "Прокси не запущен: " + startup_error(exc)})
        sys.exit(1)
