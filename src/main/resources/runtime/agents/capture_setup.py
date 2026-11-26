"""Prepare reusable capture assets without starting a listener or changing Android."""
import argparse
import contextlib
import hashlib
import json
import os
from pathlib import Path
import re
import sys
from datetime import datetime, timezone


@contextlib.contextmanager
def asset_lock(root):
    root.mkdir(parents=True, exist_ok=True)
    with (root / '.asset-prepare.lock').open('a+b') as handle:
        handle.seek(0, 2)
        if handle.tell() == 0:
            handle.write(b'0'); handle.flush()
        handle.seek(0)
        if os.name == 'nt':
            import msvcrt
            msvcrt.locking(handle.fileno(), msvcrt.LK_LOCK, 1)
        else:
            import fcntl
            fcntl.flock(handle.fileno(), fcntl.LOCK_EX)
        try: yield
        finally:
            handle.seek(0)
            if os.name == 'nt': msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            else: fcntl.flock(handle.fileno(), fcntl.LOCK_UN)


def atomic_write(path, data):
    path = Path(path)
    if path.exists() and path.read_bytes() == data: return False
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + '.tmp-' + str(os.getpid()))
    try:
        temporary.write_bytes(data)
        temporary.replace(path)
    finally:
        if temporary.exists(): temporary.unlink()
    return True


def certificate(confdir):
    from mitmproxy.certs import CertStore
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    confdir = Path(confdir)
    private = confdir / 'mitmproxy-ca.pem'
    public = confdir / 'mitmproxy-ca-cert.cer'
    if not private.exists() and any(confdir.glob('mitmproxy-ca-cert.*')):
        raise ValueError('Публичный CA сохранился без закрытого ключа. Восстановите ключ или явно сбросьте данные захвата.')
    store = CertStore.from_store(confdir, 'mitmproxy', 2048)
    cert = store.default_ca._cert
    if cert.not_valid_after_utc <= datetime.now(timezone.utc):
        raise ValueError('Срок CA истёк. Пересоздайте CA через сброс данных захвата и заново установите его на Android.')
    if store.default_privatekey.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo) != cert.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo):
        raise ValueError('Закрытый ключ не соответствует CA. Автоматическая замена отключена; используйте явный сброс.')
    if public.exists() and x509.load_pem_x509_certificate(public.read_bytes()).fingerprint(hashes.SHA256()) != cert.fingerprint(hashes.SHA256()):
        raise ValueError('Публичный CA отличается от закрытого ключа. Автоматическая замена отключена; используйте явный сброс.')
    for target in [public, confdir / 'mitmproxy-ca-cert.pem']:
        atomic_write(target, cert.public_bytes(serialization.Encoding.PEM))
    return cert.fingerprint(hashes.SHA256()).hex().upper()


def vpn_profile(profile, endpoint, package):
    import mitmproxy_rs
    if not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)*', package): raise ValueError('Некорректный пакет приложения.')
    if not re.fullmatch(r'(?:[A-Za-z0-9][A-Za-z0-9.\-]*|\[[a-fA-F0-9:]+\]):[0-9]{1,5}', endpoint): raise ValueError('Некорректный адрес VPN.')
    if not 1024 <= int(endpoint.rsplit(':', 1)[1]) <= 65535: raise ValueError('UDP-порт VPN должен быть от 1024 до 65535.')
    keys_path = Path(profile['keys'])
    if keys_path.exists(): keys = json.loads(keys_path.read_text(encoding='utf-8'))
    else:
        if Path(profile['config']).exists() or Path(profile['metadata']).exists():
            raise ValueError('VPN-профиль сохранился без ключей WireGuard. Восстановите ключи или явно сбросьте данные захвата.')
        keys = dict(server_key=mitmproxy_rs.wireguard.genkey(), client_key=mitmproxy_rs.wireguard.genkey())
        atomic_write(keys_path, json.dumps(keys, indent=2).encode('utf-8'))
    server_public = mitmproxy_rs.wireguard.pubkey(keys['server_key'])
    mitmproxy_rs.wireguard.pubkey(keys['client_key'])
    config = ('[Interface]\nIncludedApplications = ' + package + '\nPrivateKey = ' + keys['client_key']
              + '\nAddress = 10.0.0.1/32\nDNS = 10.0.0.53\n\n[Peer]\nPublicKey = ' + server_public
              + '\nAllowedIPs = 0.0.0.0/0, ::/0\nEndpoint = ' + endpoint + '\n')
    atomic_write(Path(profile['config']), config.encode('utf-8'))
    fingerprint = hashlib.sha256(config.encode('utf-8')).hexdigest()
    atomic_write(Path(profile['metadata']), json.dumps({'version': 1, 'endpoint': endpoint, 'package': package,
                 'configSha256': fingerprint, 'keysSha256': hashlib.sha256(keys_path.read_bytes()).hexdigest()}, indent=2).encode('utf-8'))
    return fingerprint


def prepare(root, profile=None, endpoint='', package=''):
    root = Path(root)
    with asset_lock(root):
        fingerprint = certificate(root / 'proxy')
        config_hash = vpn_profile(profile, endpoint, package) if profile else ''
    return dict(certificateFingerprint=fingerprint, profileFingerprint=config_hash)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', required=True)
    parser.add_argument('--keys'); parser.add_argument('--config'); parser.add_argument('--metadata')
    parser.add_argument('--endpoint', default=''); parser.add_argument('--package', default='')
    args = parser.parse_args()
    try:
        profile = dict(keys=args.keys, config=args.config, metadata=args.metadata) if args.keys else None
        print(json.dumps(prepare(args.root, profile, args.endpoint, args.package)))
    except Exception as error:
        from proxy_backend import startup_error
        print(json.dumps({'error': startup_error(error)}, ensure_ascii=True)); sys.exit(1)
