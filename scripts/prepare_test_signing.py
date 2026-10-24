#!/usr/bin/env python3
"""Prepare the authorized test signing key and export only an encrypted backup."""
from pathlib import Path
import hashlib
import hmac
import json
import os
import secrets
import subprocess
import tempfile


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True, stdout=subprocess.DEVNULL)


def main():
    root = Path(__file__).resolve().parents[1]
    key = Path.home() / '.gps3d' / 'precision-test.keystore'
    expected = root / '.github' / 'signing' / 'test-certificate.sha256'
    key.parent.mkdir(parents=True, exist_ok=True)
    os.chmod(key.parent, 0o700)
    if not key.is_file():
        if expected.is_file():
            raise RuntimeError('Restore the backed-up GPS3D test keystore; do not regenerate an existing identity.')
        run('keytool', '-genkeypair', '-keystore', key, '-storepass', 'android', '-keypass', 'android',
            '-alias', 'androiddebugkey', '-dname', 'CN=GPS3D David Precision Tests, O=GPS3D, C=MX',
            '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000', '-noprompt')
    os.chmod(key, 0o600)
    out = root / 'build' / 'signing-backup'
    out.mkdir(parents=True, exist_ok=True)
    certificate = out / 'certificate.der'
    run('keytool', '-exportcert', '-keystore', key, '-storepass', 'android', '-alias', 'androiddebugkey',
        '-file', certificate)
    digest = hashlib.sha256(certificate.read_bytes()).hexdigest()
    if expected.is_file() and digest != expected.read_text().strip():
        raise RuntimeError('The signing key does not match the backed-up test identity.')
    with tempfile.TemporaryDirectory() as temporary:
        password = Path(temporary) / 'transport-password'
        password.write_text(secrets.token_hex(64) + '\n')
        os.chmod(password, 0o600)
        encrypted = out / 'keystore.enc'
        run('openssl', 'enc', '-aes-256-cbc', '-salt', '-pbkdf2', '-iter', '200000',
            '-in', key, '-out', encrypted, '-pass', f'file:{password}')
        run('openssl', 'pkeyutl', '-encrypt', '-pubin', '-inkey', root / '.github/signing/backup-public.pem',
            '-pkeyopt', 'rsa_padding_mode:oaep', '-pkeyopt', 'rsa_oaep_md:sha256',
            '-in', password, '-out', out / 'transport-key.rsa')
        mac_key = hashlib.sha256(b'GPS3D-backup-authentication:' + password.read_bytes()).digest()
        authentication = hmac.new(mac_key, encrypted.read_bytes(), hashlib.sha256).hexdigest()
    (out / 'manifest.json').write_text(json.dumps({
        'certificate_sha256': digest, 'key_alias': 'androiddebugkey',
        'keystore_sha256': hashlib.sha256(key.read_bytes()).hexdigest(),
        'encrypted_hmac_sha256': authentication,
        'transport': 'RSA-OAEP-SHA256 + AES-256-CBC-PBKDF2-SHA256 + HMAC-SHA256',
    }, indent=2) + '\n')
    environment = os.environ.get('GITHUB_ENV')
    if environment:
        with open(environment, 'a') as target:
            target.write(f'GPS3D_DEBUG_KEYSTORE={key}\nGPS3D_EXPECTED_SIGNING_SHA256={digest}\n')
    print('GPS3D test signing certificate SHA256:', digest)
    print('Encrypted signing backup prepared; no private key is included in plain text.')


if __name__ == '__main__':
    main()
