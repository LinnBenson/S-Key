#!/usr/bin/env python3
"""Run portable host Wubi diagnostics serially. Never builds Android or accesses network."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys


def executable(name, override):
    if override:
        return override
    home = os.environ.get("JAVA_HOME")
    suffix = ".exe" if os.name == "nt" else ""
    if home and (Path(home) / "bin" / (name + suffix)).exists():
        return str(Path(home) / "bin" / (name + suffix))
    value = shutil.which(name)
    if not value:
        raise ValueError("JDK 17+ %s is required; set JAVA_HOME or --%s" % (name, name))
    return value


def stdlib(root):
    homes = []
    if os.environ.get("GRADLE_USER_HOME"):
        homes.append(Path(os.environ["GRADLE_USER_HOME"]))
    homes.extend((root / ".gradle-home", Path.home() / ".gradle"))
    for home in homes:
        folder = home / "caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.0.21"
        matches = sorted(folder.glob("*/kotlin-stdlib-2.0.21.jar"))
        if matches:
            return str(matches[0])
    raise ValueError("Kotlin stdlib 2.0.21 not found; pass its jar with --classpath")


def run_logged(command, output):
    print("Running", output.name, flush=True)
    with output.open("w", encoding="utf-8") as stream:
        subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT, check=True)
    print(output.read_text(encoding="utf-8"), end="", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--classes", type=Path, help="Already-built Android Kotlin class directory")
    parser.add_argument("--classpath", help="Extra runtime jars, using the platform path separator")
    parser.add_argument("--java", help="Path to Java 17+ executable")
    parser.add_argument("--javac", help="Path to matching javac executable")
    parser.add_argument("--output", type=Path, help="Diagnostic files, default build/compact_wubi_benchmark")
    parser.add_argument("--mode", choices=("all", "parity", "heap", "latency"), default="parity")
    parser.add_argument("--heap-repeats", type=int, default=2)
    args = parser.parse_args()
    if not 1 <= args.heap_repeats <= 10:
        parser.error("--heap-repeats must be between 1 and 10")
    root = args.project_root.resolve()
    classes = (args.classes or root / "app/build/tmp/kotlin-classes/debug").resolve()
    if not (classes / "io/secure/keyboard/engine/CompactWubiTable.class").is_file():
        parser.error("Production Kotlin classes are missing. Run ./gradlew compileDebugKotlin first, or pass --classes.")
    output = (args.output or root / "build/compact_wubi_benchmark").resolve()
    output.mkdir(parents=True, exist_ok=True)
    own_classes = output / "classes"
    own_classes.mkdir(exist_ok=True)
    source = Path(__file__).resolve().parent
    assets = root / "app/src/main/assets/dictionaries"
    java, javac = executable("java", args.java), executable("javac", args.javac)
    extra = args.classpath or stdlib(root)
    classpath = os.pathsep.join((str(own_classes), str(classes), extra))
    # The preparer exits before any JVM starts, so no Python dictionary objects
    # or source strings can contaminate Java memory measurements.
    reference = output / "merged-reference.dict.yaml"
    priorities = output / "exact-priority-reference.tsv"
    run_logged([sys.executable, str(source / "prepare_reference.py"), "--project-root", str(root),
                "--output", str(reference), "--priorities-output", str(priorities)], output / "prepare-reference.log")
    harnesses = ("CompactMemoryDiagnostic", "CompactParityDiagnostic", "CompactLatencyDiagnostic", "ExactPriorityReference")
    run_logged([javac, "-J-Xmx256m", "-J-XX:ActiveProcessorCount=1", "-cp", classpath,
                "-d", str(own_classes)] + [str(source / (h + ".java")) for h in harnesses],
               output / "compile-harness.log")
    command = [java, "-Xmx512m", "-Xms64m", "-XX:ActiveProcessorCount=1", "-cp", classpath]
    inputs = [str(reference), str(assets / "compact-wubi.skwb"), str(assets / "pinyin_simp.dict.yaml")]
    if args.mode in ("all", "parity"):
        run_logged(command + ["CompactParityDiagnostic"] + inputs + [str(priorities)], output / "parity.log")
    if args.mode in ("all", "latency"):
        run_logged(command + ["CompactLatencyDiagnostic"] + inputs, output / "latency.log")
    if args.mode in ("all", "heap"):
        for repeat in range(args.heap_repeats):
            for mode, path in (("baseline", assets / "wubi86.dict.yaml"), ("merged", reference),
                               ("compact", assets / "compact-wubi.skwb")):
                run_logged(command + ["CompactMemoryDiagnostic", str(path), inputs[2],
                           str(assets / "english_frequency.tsv"), mode],
                           output / ("heap-%s-%d.log" % (mode, repeat + 1)))
    print("Host diagnostics complete. These are not Android PSS/latency measurements.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        sys.exit("ERROR: %s" % error)
