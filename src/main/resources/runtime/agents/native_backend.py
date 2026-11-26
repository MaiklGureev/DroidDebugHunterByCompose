"""Native TLS collector: server transport, or built-in Gadget injection for debuggable apps."""
import argparse
import json
from pathlib import Path
import sys
import threading
import traceback
import uuid

import frida
from tls_decoder import NativeDecoder
from capture_diagnostics import raw_fields

lock = threading.RLock()


def emit(event):
    with lock:
        print('DDH_HTTP_EVENT:' + json.dumps(event, ensure_ascii=True), flush=True)


def main(args):
    if frida.__version__ != '17.26.2': raise RuntimeError('Frida client must be 17.26.2')
    if args.device:
        device = frida.get_device_manager().get_device(args.device, timeout=15)
    else:
        device = frida.get_device_manager().add_remote_device(args.host)
    options = {'gadget': args.gadget} if args.gadget else {}
    pid = device.spawn(args.package, **options) if args.spawn else next(a.pid for a in device.enumerate_applications() if a.identifier == args.package and a.pid)
    session = None
    spawned = args.spawn
    decoder = NativeDecoder(emit, {'packageName': args.package, 'uid': args.uid, 'appId': str(int(args.uid) % 100000) if args.uid else ''})
    stopped = threading.Event()
    try:
        session = device.attach(pid)
        session.on('detached', lambda *unused: stopped.set())
        script = session.create_script((Path(__file__).parent / 'native_tls.js').read_text(encoding='utf-8'))

        def message(value, data):
            with lock:
                if value['type'] == 'send':
                    event = value['payload']
                    if event.get('kind') == 'identity':
                        decoder.identity['uid'] = event['uid']; decoder.identity['appId'] = str(int(event['uid']) % 100000)
                    elif event.get('kind', '').startswith('tls_'):
                        try: decoder.feed(event, data)
                        except Exception as error:
                            emit(dict(decoder.identity, kind='diagnostic', id='native-error-' + uuid.uuid4().hex,
                                      source='Frida · Native TLS', stage='native-decoder', channel=event.get('channel', ''),
                                      direction=event.get('direction', ''), peer=event.get('peer'),
                                      error=str(error), message='Ошибка обработки TLS-фрагмента', stackTrace=traceback.format_exc(),
                                      **raw_fields(data)))
                    else: emit(event)
                elif value['type'] == 'error':
                    emit(dict(decoder.identity, kind='diagnostic', id='agent-error-' + uuid.uuid4().hex,
                              source='Frida · Native TLS', stage='frida-agent',
                              error=value.get('description', 'Agent error'), message='Ошибка JS-агента Frida',
                              stackTrace=value.get('stack', ''), **raw_fields()))

        script.on('message', message)
        script.load()
        if spawned: device.resume(pid); spawned = False
        emit({'kind': 'ready', 'message': 'Native TLS: подключён к процессу; ожидаю открытые HTTP/1 или HTTP/2 данные TLS.'})
        threading.Thread(target=lambda: (sys.stdin.readline(), stopped.set()), daemon=True).start()
        stopped.wait()
    finally:
        with lock: decoder.close()
        if spawned:
            try: device.resume(pid)
            except Exception: pass
        if session:
            try: session.detach()
            except Exception: pass


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--host', default='127.0.0.1:27042')
    parser.add_argument('--device')
    parser.add_argument('--gadget')
    parser.add_argument('--package', required=True)
    parser.add_argument('--uid', default='')
    parser.add_argument('--spawn', action='store_true')
    try: main(parser.parse_args())
    except Exception as error:
        emit({'kind': 'unsupported', 'message': str(error)})
        sys.exit(1)
