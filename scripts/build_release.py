#!/usr/bin/env python3
"""Build and verify locally signed APKs without copying secrets into source."""
import os
from pathlib import Path
import subprocess
from verify_dictionary_apk import verify_apk

SOURCE = Path(__file__).resolve().parents[1]
ROOT = SOURCE.parent
KEY = Path(os.environ.get("SKEY_SIGNING_DIR", str(ROOT / "key")))
JAVA = Path(os.environ.get("JAVA_HOME", "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"))
env = dict(os.environ, JAVA_HOME=str(JAVA))
env["PATH"] = str(JAVA / "bin") + os.pathsep + env.get("PATH", "")


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True, env=env)


if __name__ == "__main__":
    for name in ("s-key-release.p12", "store-password.txt"):
        if not (KEY / name).is_file():
            raise SystemExit(f"Missing signing file: {name}; restore the existing key backup.")
    sdk = next(
        line.split("=", 1)[1].strip()
        for line in (SOURCE / "local.properties").read_text().splitlines()
        if line.startswith("sdk.dir=")
    )
    tools = Path(sdk) / "build-tools" / "34.0.0"
    run(SOURCE / "gradlew", "-p", SOURCE, "assembleRelease")
    apks = sorted((SOURCE / "app/build/outputs/apk/release").glob("*-unsigned.apk"))
    if not apks:
        raise SystemExit("No unsigned release APKs found.")
    output = ROOT / "package"
    output.mkdir(exist_ok=True)
    for apk in apks:
        target = output / apk.name.replace("-unsigned.apk", "-signed.apk")
        run(tools / "zipalign", "-f", "-p", "4", apk, target)
        run(
            tools / "apksigner", "sign",
            "--ks", KEY / "s-key-release.p12",
            "--ks-key-alias", "s-key-release",
            "--ks-pass", "file:" + str(KEY / "store-password.txt"),
            target,
        )
        run(tools / "apksigner", "verify", "--verbose", "--print-certs", target)
        verify_apk(target)
        print(f"Verified: {target}", flush=True)
