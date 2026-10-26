#!/usr/bin/env python3
"""Build a deterministic source-only ZIP below 32 MiB, excluding keys, binaries and local state."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MAX_BYTES = 32 * 1024 * 1024
ROOT_FILES = (
    ".gitignore", "LICENSE", "NOTICE", "README.md", "build.gradle.kts", "settings.gradle.kts",
    "gradle.properties", "gradlew", "gradlew.bat", "app/build.gradle.kts",
)
TREES = ("app/src/main", "gradle/wrapper", "third_party", "scripts", "docs/architecture")
FORBIDDEN_PARTS = {".git", ".gradle", ".gradle-home", ".kotlin", "build", "__pycache__", "validation", "deliverables", "test-results", "test-reports", "reports"}
FORBIDDEN_SUFFIXES = {".aar", ".apk", ".aab", ".dex", ".so", ".onnx", ".wav", ".mp3", ".ogg", ".jks", ".keystore", ".pem", ".key", ".p12", ".pyc"}


KEY_SOUND_ASSETS = {
    "app/src/main/res/raw/" + name + ".ogg"
    for name in ("bong_001", "click_001", "click_002", "click_003", "click_004", "click_005")
}


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", default="0.1.3")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", args.version):
        parser.error("version must have the form 0.1.1")
    target = args.output or ROOT / "deliverables" / f"offline-keyboard-source-{args.version}.zip"
    files = {ROOT / name for name in ROOT_FILES}
    for tree in TREES:
        files.update(path for path in (ROOT / tree).rglob("*") if path.is_file())
    payload = {}
    for path in sorted(files):
        relative = path.relative_to(ROOT)
        if (relative.name in {"TEST_RESULTS.md", "REPORT.md", "REPORT.txt"}
                or (relative.parts[:2] == ("docs", "verification"))
                or (relative.parent.as_posix() == "docs" and re.fullmatch(r".*-review-.*\.txt", relative.name))):
            continue
        # Rebuilt losslessly by restore_source_assets.py; ship only the pinned .xz.
        if relative.parent.as_posix() == "app/src/main/assets/fonts" and relative.suffix == ".ttf":
            continue
        if any(part in FORBIDDEN_PARTS for part in relative.parts):
            continue
        if path.is_symlink() or (path.suffix.lower() in FORBIDDEN_SUFFIXES
                                 and relative.as_posix() not in KEY_SOUND_ASSETS):
            raise ValueError(f"Refusing forbidden source-package file: {relative}")
        if path.suffix.lower() == ".jar" and relative.as_posix() != "gradle/wrapper/gradle-wrapper.jar":
            raise ValueError(f"Unexpected binary: {relative}")
        payload[relative.as_posix()] = path.read_bytes()
    payload["SOURCE_PACKAGE.json"] = (json.dumps({
        "version": args.version,
        "contents": "Source, Gradle wrapper, static dictionaries, notices and build scripts; no model, recording, signing key or AAR",
        "voice_dependency_setup": "python3 scripts/fetch_voice_dependency.py",
        "validation_results": "excluded; store reports outside the source repository",
    }, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    payload["SOURCE_MANIFEST.sha256"] = "".join(f"{sha256(data)}  {name}\n" for name, data in sorted(payload.items())).encode("utf-8")
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_name(target.name + ".tmp")
    try:
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for name, data in sorted(payload.items()):
                info = zipfile.ZipInfo("offline-keyboard/" + name, date_time=(1980, 1, 1, 0, 0, 0))
                info.create_system = 3
                executable = name == "gradlew" or name.endswith(".sh") or name.startswith("scripts/") and name.endswith(".py")
                info.external_attr = (0o100755 if executable else 0o100644) << 16
                info.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
        if temporary.stat().st_size >= MAX_BYTES:
            raise ValueError("Source package must remain below 32 MiB")
        temporary.replace(target)
    finally:
        temporary.unlink(missing_ok=True)
    print(f"{target.name}: {target.stat().st_size} bytes; {len(payload)} files")
    print(f"SHA-256: {sha256(target.read_bytes())}")
    print("Validation results: excluded; application source and build tools only")


if __name__ == "__main__":
    main()
