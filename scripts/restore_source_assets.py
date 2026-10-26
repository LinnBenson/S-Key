#!/usr/bin/env python3
"""Restore the losslessly compressed fonts. No network access required."""
from pathlib import Path
import hashlib
import lzma
import os
import tempfile

ROOT = Path(__file__).resolve().parents[1]
FONTS = (
    ("OfflineKeyboardCjkSupplement.ttf", "e863734f155aab99ef3f0069d11b969829c6d9c22324f76f819873d17afb958c",
     "aae5668d88fb85be170cb07171e92c3bfe52d328390c167ffcf6c48fe054cffb", 22187516),
    ("OfflineKeyboardCjkBmp.ttf", "512faa75e6c1cf2cad4dea49ebd405dbc014208bd6d3c317bd9817889958ee03", "5bf4c1664e70b4a81880bbc7d85664eed287a6f4feab243612d6d0f625d46c45", 3271452),
)

def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()

def restore(name, compressed_hash, font_hash, font_bytes):
    source_path = ROOT / "third_party/fonts" / (name + ".xz")
    target = ROOT / "app/src/main/assets/fonts" / name
    if target.exists():
        if target.stat().st_size != font_bytes or digest(target) != font_hash:
            raise SystemExit("Existing font differs; move it aside explicitly before restoring.")
        print("Font already restored; SHA-256 verified.")
        return
    if digest(source_path) != compressed_hash:
        raise SystemExit("Compressed font SHA-256 mismatch; no output written.")
    target.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=".font-", dir=target.parent)
    temporary = Path(temporary)
    try:
        with os.fdopen(fd, "wb") as output, lzma.open(source_path, "rb") as source:
            for block in iter(lambda: source.read(1024 * 1024), b""):
                output.write(block)
        if temporary.stat().st_size != font_bytes or digest(temporary) != font_hash:
            raise SystemExit("Restored font SHA-256 mismatch; no output installed.")
        temporary.chmod(0o644)
        temporary.replace(target)
        print(f"{name} restored; {font_bytes} bytes and SHA-256 verified.")
    finally:
        temporary.unlink(missing_ok=True)

def main():
    for font in FONTS:
        restore(*font)

if __name__ == "__main__":
    main()
