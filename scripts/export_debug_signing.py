"""Export the app's debug identity encrypted to its owner's recipient certificate.

The owner explicitly authorized this signing-key export on 2026-10-29.
Only the public recipient certificate is committed to the private repository.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess


def export(keystore, apk, recipient, output):
    assert keystore.is_file() and apk.is_file() and recipient.is_file()
    output.mkdir(parents=True, exist_ok=True, mode=0o700)
    encrypted = output / 'debug.keystore.p7m'
    subprocess.run(['openssl', 'cms', '-encrypt', '-binary', '-aes-256-cbc',
                    '-in', str(keystore), '-outform', 'DER', '-out', str(encrypted),
                    str(recipient)], check=True, capture_output=True)
    assert encrypted.stat().st_size > 0
    certificate = subprocess.run(['keytool', '-list', '-rfc', '-keystore', str(keystore),
                                  '-storepass', 'android', '-alias', 'androiddebugkey'],
                                 check=True, capture_output=True, text=True).stdout
    match = re.search(r'-----BEGIN CERTIFICATE-----\s*(.*?)\s*-----END CERTIFICATE-----',
                      certificate, re.S)
    assert match, 'Missing signing certificate'
    der = base64.b64decode(re.sub(r'\s+', '', match.group(1)), validate=True)
    metadata = {'format': 'OpenSSL CMS EnvelopedData DER / AES-256-CBC',
                'keystore_alias': 'androiddebugkey', 'keystore_password': 'android',
                'key_password': 'android', 'commit': os.environ.get('GITHUB_SHA', 'local-check'),
                'run_id': os.environ.get('GITHUB_RUN_ID'),
                'apk_sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
                'signing_certificate_sha256': hashlib.sha256(der).hexdigest(),
                'encrypted_keystore_sha256': hashlib.sha256(encrypted.read_bytes()).hexdigest()}
    (output / 'metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
    print('Encrypted debug signing backup created.')


if __name__ == '__main__':
    root = Path(__file__).resolve().parents[1]
    local_key = root / 'signing/huanban-debug.keystore'
    parser = argparse.ArgumentParser()
    parser.add_argument('--keystore', type=Path, default=local_key if local_key.exists() else Path.home() / '.android/debug.keystore')
    parser.add_argument('--apk', type=Path, default=root / 'app/build/outputs/apk/debug/app-debug.apk')
    parser.add_argument('--recipient', type=Path, default=root / '.github/ci-signing-recipient.pem')
    parser.add_argument('--output', type=Path, default=root / 'signing-export')
    args = parser.parse_args()
    export(args.keystore, args.apk, args.recipient, args.output)
