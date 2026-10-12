#!/usr/bin/env python3
"""Prepare legacy-reader input in a separate process, then release all Python indexes."""
import argparse
import importlib.util
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--priorities-output", type=Path, required=True)
    args = parser.parse_args()
    root = args.project_root.resolve()
    spec = importlib.util.spec_from_file_location("wubi_builder", root / "scripts/build_compact_wubi.py")
    builder = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(builder)
    sources = root / "third_party/wubi_supplements"
    manifest = json.loads((sources / "source-manifest.json").read_text(encoding="utf-8"))
    entries, reports = builder.load_sources(
        root / "app/src/main/assets/dictionaries/wubi86.dict.yaml", sources, manifest,
    )
    expected_rows = manifest["expected"]["merged_rows"]
    if len(entries) != expected_rows:
        raise ValueError("Pinned diagnostic requires all %d merged rows" % expected_rows)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8", newline="\n") as stream:
        stream.write("---\nname: merged_diagnostic_reference\n...\n")
        for entry in entries:
            stream.write("%s\t%s\t%d\n" % (entry.text, entry.code, entry.weight))
    # Keep the Reader's original weights/order intact. Priority cannot be represented
    # there without also changing proper-prefix order, so supply a separate test oracle.
    priorities = dict.fromkeys((row["text"], row["code"])
                               for report in reports for row in report.get("exact_priorities", []))
    args.priorities_output.parent.mkdir(parents=True, exist_ok=True)
    with args.priorities_output.open("w", encoding="utf-8", newline="\n") as stream:
        for text, code in priorities:
            stream.write("%s\t%s\n" % (text, code))
    print(json.dumps({"reference": str(args.output), "rows": len(entries),
                      "sources": len(reports), "exact_priorities": len(priorities),
                      "priorities_reference": str(args.priorities_output),
                      "scope": "temporary benchmark inputs only; priorities applied by parity oracle, not Reader weights"}))


if __name__ == "__main__":
    main()
