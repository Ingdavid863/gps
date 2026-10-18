#!/usr/bin/env python3
"""Recover a test keystore from its authenticated, RSA-encrypted CI backup."""
import argparse
import hashlib
import hmac
import json
from pathlib import Path
import os
import subprocess
import tempfile


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True, stdout=subprocess.DEVNULL)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--private-key', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise RuntimeError('Refusing to overwrite an existing signing keystore.')
    manifest = json.loads((args.input / 'manifest.json').read_text())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    os.chmod(args.output.parent, 0o700)
    with tempfile.TemporaryDirectory() as temporary:
        password = Path(temporary) / 'transport-password'
        run('openssl', 'pkeyutl', '-decrypt', '-inkey', args.private_key,
            '-pkeyopt', 'rsa_padding_mode:oaep', '-pkeyopt', 'rsa_oaep_md:sha256',
            '-in', args.input / 'transport-key.rsa', '-out', password)
        encrypted = args.input / 'keystore.enc'
        mac_key = hashlib.sha256(b'GPS3D-backup-authentication:' + password.read_bytes()).digest()
        actual = hmac.new(mac_key, encrypted.read_bytes(), hashlib.sha256).hexdigest()
        if not hmac.compare_digest(actual, manifest['encrypted_hmac_sha256']):
            raise RuntimeError('Signing backup authentication failed.')
        recovered = Path(temporary) / 'recovered.keystore'
        run('openssl', 'enc', '-d', '-aes-256-cbc', '-pbkdf2', '-iter', '200000',
            '-in', encrypted, '-out', recovered, '-pass', f'file:{password}')
        if hashlib.sha256(recovered.read_bytes()).hexdigest() != manifest['keystore_sha256']:
            raise RuntimeError('Recovered keystore checksum mismatch.')
        certificate = Path(temporary) / 'certificate.der'
        run('keytool', '-exportcert', '-keystore', recovered, '-storepass', 'android',
            '-alias', manifest['key_alias'], '-file', certificate)
        digest = hashlib.sha256(certificate.read_bytes()).hexdigest()
        if digest != manifest['certificate_sha256']:
            raise RuntimeError('Recovered signing certificate mismatch.')
        args.output.write_bytes(recovered.read_bytes())
        os.chmod(args.output, 0o600)
    print('Recovered GPS3D test keystore; certificate SHA256:', digest)


if __name__ == '__main__':
    main()
