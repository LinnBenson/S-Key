#!/usr/bin/env python3
"""Restore the losslessly compressed build53 font. No network access required."""
from pathlib import Path
import hashlib
import lzma
import os
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "third_party/fonts/OfflineKeyboardCjkSupplement.ttf.xz"
TARGET = ROOT / "app/src/main/assets/fonts/OfflineKeyboardCjkSupplement.ttf"
COMPRESSED_SHA256 = "e863734f155aab99ef3f0069d11b969829c6d9c22324f76f819873d17afb958c"
FONT_SHA256 = "aae5668d88fb85be170cb07171e92c3bfe52d328390c167ffcf6c48fe054cffb"
FONT_BYTES = 22187516

def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()

def main():
    if TARGET.exists():
        if TARGET.stat().st_size != FONT_BYTES or digest(TARGET) != FONT_SHA256:
            raise SystemExit("Existing font differs; move it aside explicitly before restoring.")
        print("Font already restored; SHA-256 verified.")
        return
    if digest(SOURCE) != COMPRESSED_SHA256:
        raise SystemExit("Compressed font SHA-256 mismatch; no output written.")
    TARGET.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=".font-", dir=TARGET.parent)
    temporary = Path(temporary)
    try:
        with os.fdopen(fd, "wb") as output, lzma.open(SOURCE, "rb") as source:
            for block in iter(lambda: source.read(1024 * 1024), b""):
                output.write(block)
        if temporary.stat().st_size != FONT_BYTES or digest(temporary) != FONT_SHA256:
            raise SystemExit("Restored font SHA-256 mismatch; no output installed.")
        temporary.chmod(0o644)
        temporary.replace(TARGET)
        print("Font restored; 22,187,516 bytes and SHA-256 verified.")
    finally:
        temporary.unlink(missing_ok=True)

if __name__ == "__main__":
    main()
