#!/usr/bin/env python3
"""Fetch the official, hash-pinned sherpa-onnx AAR; never accept an unverified binary."""
import argparse
import hashlib
import os
from pathlib import Path
import sys
import tempfile
import urllib.request

VERSION = "1.13.8"
FILENAME = f"sherpa-onnx-{VERSION}.aar"
URL = f"https://github.com/k2-fsa/sherpa-onnx/releases/download/v{VERSION}/{FILENAME}"
SHA256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
SIZE_BYTES = 50_129_134
ROOT = Path(__file__).resolve().parents[1]


def verified(path):
    if not path.is_file() or path.stat().st_size != SIZE_BYTES:
        return False
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest() == SHA256


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify-only", action="store_true", help="Check the existing file without network access")
    parser.add_argument("--destination", type=Path, default=ROOT / "app" / "libs" / FILENAME)
    args = parser.parse_args()
    destination = args.destination
    if verified(destination):
        print(f"Verified {FILENAME}: {SHA256}")
        return 0
    if args.verify_only:
        print(f"Missing or invalid dependency: expected {SIZE_BYTES} bytes and SHA-256 {SHA256}", file=sys.stderr)
        return 1
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(prefix=FILENAME + ".", suffix=".download", dir=destination.parent, delete=False) as output:
            temporary = Path(output.name)
            request = urllib.request.Request(URL, headers={"User-Agent": "offline-keyboard-source-bootstrap/0.1.1"})
            with urllib.request.urlopen(request, timeout=60) as response:
                count = 0
                while True:
                    block = response.read(1024 * 1024)
                    if not block:
                        break
                    count += len(block)
                    if count > SIZE_BYTES:
                        raise ValueError("Official dependency exceeded the pinned byte size")
                    output.write(block)
            output.flush()
            os.fsync(output.fileno())
        if not verified(temporary):
            raise ValueError("Dependency size or SHA-256 mismatch; downloaded file rejected")
        os.replace(temporary, destination)
        temporary = None
        print(f"Downloaded and verified {FILENAME}: {SHA256}")
        return 0
    except Exception as error:
        print(f"Dependency setup failed: {error}", file=sys.stderr)
        return 1
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    raise SystemExit(main())
