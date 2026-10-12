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
    ".gitignore", "README.md", "REQUIREMENTS.md", "build.gradle.kts", "settings.gradle.kts",
    "gradle.properties", "gradlew", "gradlew.bat", "app/build.gradle.kts",
)
TREES = ("app/src", "gradle/wrapper", "tests", "third_party", "scripts")
FORBIDDEN_PARTS = {".git", ".gradle", ".gradle-home", ".kotlin", "build", "__pycache__", "validation", "deliverables"}
FORBIDDEN_SUFFIXES = {".aar", ".apk", ".aab", ".dex", ".so", ".onnx", ".wav", ".mp3", ".ogg", ".jks", ".keystore", ".pem", ".key", ".p12", ".pyc"}


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", default="0.1.3")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--final-results", action="store_true", help="Include the reviewed, current TEST_RESULTS.md instead of a pending-results notice")
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", args.version):
        parser.error("version must have the form 0.1.1")
    suffix = "" if args.final_results else "-preview"
    target = args.output or ROOT / "deliverables" / f"offline-keyboard-source-{args.version}{suffix}.zip"
    files = {ROOT / name for name in ROOT_FILES}
    for tree in TREES:
        files.update(path for path in (ROOT / tree).rglob("*") if path.is_file())
    payload = {}
    for path in sorted(files):
        relative = path.relative_to(ROOT)
        if any(part in FORBIDDEN_PARTS for part in relative.parts):
            continue
        if path.is_symlink() or path.suffix.lower() in FORBIDDEN_SUFFIXES:
            raise ValueError(f"Refusing forbidden source-package file: {relative}")
        if path.suffix.lower() == ".jar" and relative.as_posix() != "gradle/wrapper/gradle-wrapper.jar":
            raise ValueError(f"Unexpected binary: {relative}")
        payload[relative.as_posix()] = path.read_bytes()
    if args.final_results:
        payload["TEST_RESULTS.md"] = (ROOT / "TEST_RESULTS.md").read_bytes()
    else:
        payload["TEST_RESULTS.md"] = (
            f"# {args.version} 验证状态\n\n"
            "此源码预览包生成时，本版本的设备运行测试仍在进行中。\n"
            "这里没有把旧版本或尚未完成的验证记录当作最终结论。\n"
            "最终交付包将包含更新后的完整验证记录。\n"
        ).encode("utf-8")
    payload["SOURCE_PACKAGE.json"] = (json.dumps({
        "version": args.version,
        "contents": "Source, Gradle wrapper, static dictionaries, notices and test source; no model, recording, signing key or AAR",
        "voice_dependency_setup": "python3 scripts/fetch_voice_dependency.py",
        "validation_results": "included" if args.final_results else "pending",
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
    print("Validation results: " + ("included" if args.final_results else "PENDING; rebuild with --final-results after updating TEST_RESULTS.md"))


if __name__ == "__main__":
    main()
