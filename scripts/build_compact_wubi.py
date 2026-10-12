#!/usr/bin/env python3
"""Build/validate deterministic SKWB0001 Wubi data. Python 3.8+, stdlib only.

No YAML library, expressions, import_tables, encoder, or schema is executed. Only
strict UTF-8 tab-delimited dictionary rows after the standalone '...' are read.
All source hashes/counts are pinned by the manifest. No network access is used.
"""
import argparse
from collections import defaultdict
from array import array
from bisect import bisect_left, bisect_right
from itertools import groupby
import hashlib
import json
from pathlib import Path
import re
import struct
import sys
from typing import NamedTuple

MAGIC = b"SKWB0001"
VERSION = 1
HEADER_SIZE = 64
MAX_PREFIX_COUNT = 30
HEADER = struct.Struct(">8s10I16s")
INDEX = struct.Struct(">III")
REFERENCE = struct.Struct(">II")
LENGTH = struct.Struct(">H")
CODE = re.compile(r"[a-z]{1,4}\Z")
INTEGER = re.compile(r"-?[0-9]+\Z")
MAX_SIGNED_INT = (1 << 31) - 1


class BuildError(ValueError):
    """Rejected input or binary. A failure never silently drops a source row."""


class Entry(NamedTuple):
    text: str
    code: str
    weight: int


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def encode_code(code):
    if not CODE.fullmatch(code):
        raise BuildError("code must be 1-4 lowercase ASCII letters: %r" % code)
    key = 0
    for index in range(4):
        key = key * 27 + (ord(code[index]) - 96 if index < len(code) else 0)
    return key


def decode_code(key):
    if not 0 < key < 27 ** 4:
        raise BuildError("invalid code key: %r" % key)
    digits = [0] * 4
    for index in range(3, -1, -1):
        key, digits[index] = divmod(key, 27)
    text = []
    padded = False
    for digit in digits:
        if digit == 0:
            padded = True
        elif padded:
            raise BuildError("non-trailing zero in code key")
        else:
            text.append(chr(digit + 96))
    if not text:
        raise BuildError("empty code key")
    return "".join(text)


def parse_dictionary(data, source_name, supplement=False, weight_zero_fallbacks=()):
    try:
        # This does not strip/normalize text: only a file-level UTF-8 BOM is allowed.
        content = data.decode("utf-8-sig", errors="strict")
    except UnicodeDecodeError as exc:
        raise BuildError("%s: invalid UTF-8: %s" % (source_name, exc)) from exc
    # Split only at real line separators, preserving other Unicode text verbatim.
    lines = content.split("\n")
    if lines and lines[-1] == "":
        lines.pop()
    counts = dict(total_lines=len(lines), header_lines=0, delimiter_lines=0,
                  blank_lines=0, comment_lines=0, valid_rows=0)
    entries = []
    fallbacks = {item["line"]: item for item in weight_zero_fallbacks}
    used_fallbacks = set()
    started = False
    header_opened = False
    for line_number, original in enumerate(lines, 1):
        line = original[:-1] if original.endswith("\r") else original
        where = "%s:%d" % (source_name, line_number)
        if not started:
            if line.strip() == "---":
                header_opened = True
            if line.strip() == "...":
                if not header_opened:
                    raise BuildError(where + ": missing opening YAML header marker")
                started = True
                counts["delimiter_lines"] += 1
            else:
                counts["header_lines"] += 1
            continue
        if not line.strip():
            counts["blank_lines"] += 1
            continue
        if line.startswith("#"):
            counts["comment_lines"] += 1
            continue
        parts = line.split("\t")
        if len(parts) not in (2, 3, 4):
            raise BuildError(where + ": expected 2-4 tab-delimited fields")
        text, code = parts[:2]
        if not text or any(ord(c) < 32 for c in text):
            raise BuildError(where + ": empty text or embedded control character")
        if not CODE.fullmatch(code):
            raise BuildError(where + ": expected a 1-4-letter lowercase Wubi code")
        if len(text.encode("utf-8")) > 65535:
            raise BuildError(where + ": UTF-8 text exceeds uint16 byte length")
        weight = 0
        if len(parts) >= 3:
            fallback = fallbacks.get(line_number)
            if fallback is not None:
                if (text, code, parts[2]) != (fallback["text"], fallback["code"], fallback["raw_weight"]):
                    raise BuildError(where + ": pinned weight compatibility row changed")
                used_fallbacks.add(line_number)
            else:
                if not INTEGER.fullmatch(parts[2]):
                    raise BuildError(where + ": weight must be a signed integer")
                weight = int(parts[2])
                if not -(1 << 63) <= weight < (1 << 63):
                    raise BuildError(where + ": weight is outside Kotlin Long range")
        if supplement and weight != 0:
            raise BuildError(where + ": supplement weight must be zero")
        entries.append(Entry(text, code, weight))
        counts["valid_rows"] += 1
    if set(fallbacks) != used_fallbacks:
        raise BuildError(source_name + ": pinned weight compatibility row not encountered")
    if not started:
        raise BuildError(source_name + ": missing terminating YAML header marker")
    if sum(counts[k] for k in counts if k != "total_lines") != len(lines):
        raise BuildError(source_name + ": internal line accounting error")
    return entries, counts


def load_sources(base, supplements, manifest):
    if manifest.get("schema_version") != 1 or manifest.get("format") != MAGIC.decode():
        raise BuildError("unsupported source manifest")
    if manifest.get("max_prefix_count") != MAX_PREFIX_COUNT:
        raise BuildError("manifest prefix limit differs from binary format")
    sources = manifest.get("sources", [])
    if not sources or sources[0].get("role") != "baseline":
        raise BuildError("first source must be the baseline")
    seen = set()
    merged = []
    reports = []
    for index, spec in enumerate(sources):
        role = spec.get("role")
        if role != ("baseline" if index == 0 else "supplement"):
            raise BuildError("only one baseline followed by supplements is supported")
        exact_priority = spec.get("exact_priority", False)
        if not isinstance(exact_priority, bool) or (exact_priority and role != "supplement"):
            raise BuildError("exact_priority must be a boolean and may be true only for a supplement")
        filename = spec["filename"]
        if Path(filename).name != filename or filename in ("", ".", ".."):
            raise BuildError("manifest source filename must be a basename")
        path = Path(base) if index == 0 else Path(supplements) / filename
        data = path.read_bytes()
        if len(data) != spec["bytes"] or sha256(data) != spec["sha256"]:
            raise BuildError("%s: source size/SHA-256 does not match pinned manifest" % filename)
        entries, counts = parse_dictionary(data, filename, supplement=index != 0,
                                           weight_zero_fallbacks=spec.get("weight_zero_fallbacks", ()))
        if len(entries) != spec["valid_rows"]:
            raise BuildError("%s: row count differs from pinned manifest" % filename)
        local_seen = set()
        retained = duplicate_within_source = duplicate_previous_source = 0
        for entry in entries:
            identity = (entry.text, entry.code)
            if identity in local_seen:
                duplicate_within_source += 1
            elif identity in seen:
                duplicate_previous_source += 1
            else:
                seen.add(identity)
                merged.append(entry)
                retained += 1
            local_seen.add(identity)
        reports.append(dict(filename=filename, role=role, sha256=spec["sha256"],
                            bytes=len(data), retained_rows=retained,
                            duplicate_rows=len(entries) - retained,
                            duplicates_within_source=duplicate_within_source,
                            duplicates_previous_sources=duplicate_previous_source,
                            line_accounting=counts,
                            weight_zero_fallbacks=spec.get("weight_zero_fallbacks", [])))
        if exact_priority:
            # Take priority order from this source, before global weight ordering or
            # first-source deduplication. An existing ordinary-source pair is promoted too.
            reports[-1]["exact_priority"] = True
            reports[-1]["exact_priorities"] = [dict(text=text, code=code) for text, code in
                                              dict.fromkeys((entry.text, entry.code) for entry in entries)]
    # Python's sort is stable, including ties at zero; supplements were appended
    # in manifest order, so baseline zero-weight rows remain ahead of supplements.
    merged.sort(key=lambda entry: -entry.weight)
    return merged, reports


def exact_priority_map(entries, exact_priorities):
    """Validate explicit full-code promotions; never invent a missing dictionary row."""
    if not isinstance(exact_priorities, (list, tuple)):
        raise BuildError("exact_priorities must be a list of text/code pairs")
    priorities = defaultdict(list)
    seen = set()
    for item in exact_priorities:
        if not isinstance(item, dict) or set(item) != {"text", "code"}:
            raise BuildError("each exact priority must contain only text and code")
        text, code = item["text"], item["code"]
        if not isinstance(code, str) or not CODE.fullmatch(code):
            raise BuildError("exact priority requires a complete 1-4-letter lowercase Wubi code")
        if not isinstance(text, str) or not text or any(ord(c) < 32 for c in text):
            raise BuildError("exact priority requires literal nonempty text")
        if (text, code) in seen:
            raise BuildError("duplicate exact priority pair: %s %s" % (text, code))
        seen.add((text, code))
        priorities[code].append(text)
    if priorities:
        matches = defaultdict(int)
        for entry in entries:
            if (entry.text, entry.code) in seen:
                matches[(entry.text, entry.code)] += 1
        for text, code in seen:
            if matches[(text, code)] != 1:
                raise BuildError("exact priority must match one retained row: %s %s" % (text, code))
    return priorities


def build_binary(entries, exact_priorities=()):
    priorities = exact_priority_map(entries, exact_priorities)
    # Group by code using a reference list, avoiding 185,000 heap dictionary buckets.
    ordered = sorted(entries, key=lambda entry: -entry.weight)
    by_code = sorted(ordered, key=lambda entry: entry.code)
    exact_count = sum(1 for _ in groupby(by_code, key=lambda entry: entry.code))
    prefixes = defaultdict(list)
    # Prefixes MUST use the current manifest stable-weight/source order. Exact-only priorities
    # are applied below, after these references have been selected and ordered.
    for entry in ordered:
        encode_code(entry.code)
        for length in range(1, len(entry.code)):
            prefix = entry.code[:length]
            if len(prefixes[prefix]) < MAX_PREFIX_COUNT:
                prefixes[prefix].append(entry)
    del ordered
    # Only records used by a prefix need their offsets retained during compilation.
    record_offsets = {entry: 0 for group in prefixes.values() for entry in group}
    exact_offset = HEADER_SIZE
    prefix_offset = exact_offset + exact_count * INDEX.size
    refs_offset = prefix_offset + len(prefixes) * INDEX.size
    reference_count = sum(len(group) for group in prefixes.values())
    text_offset = refs_offset + reference_count * REFERENCE.size
    text_data = bytearray()
    exact_data = bytearray()
    for code, iterator in groupby(by_code, key=lambda entry: entry.code):
        group = list(iterator)
        if len({entry.text for entry in group}) != len(group):
            raise BuildError("build_binary requires already deduplicated entries")
        if code in priorities:
            # Stable partition: move only these existing pairs to the front, in the
            # priority sources' row order, including multiple words for one full code.
            # Promotion preserves other words' relative order, source weights and current prefixes.
            ranks = {text: rank for rank, text in enumerate(priorities[code])}
            group.sort(key=lambda entry: ranks.get(entry.text, len(ranks)))
        start = text_offset + len(text_data)
        exact_data.extend(INDEX.pack(encode_code(code), start, len(group)))
        for entry in group:
            encoded = entry.text.encode("utf-8", errors="strict")
            if not encoded or len(encoded) > 65535:
                raise BuildError("text must have 1-65535 UTF-8 bytes")
            if entry in record_offsets:
                record_offsets[entry] = text_offset + len(text_data)
            text_data.extend(LENGTH.pack(len(encoded)))
            text_data.extend(encoded)
    prefix_data = bytearray()
    ref_data = bytearray()
    for code, group in sorted(prefixes.items()):
        prefix_data.extend(INDEX.pack(encode_code(code), len(ref_data) // REFERENCE.size, len(group)))
        for entry in group:
            ref_data.extend(REFERENCE.pack(record_offsets[entry], encode_code(entry.code)))
    file_length = text_offset + len(text_data)
    if file_length > MAX_SIGNED_INT:
        raise BuildError("binary exceeds signed 32-bit Android offset limit")
    header = HEADER.pack(MAGIC, VERSION, len(entries), exact_count, len(prefixes),
                         exact_offset, prefix_offset, refs_offset, text_offset,
                         file_length, MAX_PREFIX_COUNT, b"\0" * 16)
    return bytes(header + exact_data + prefix_data + ref_data + text_data)


def decode_binary(data, expected_entries=None, materialize=True, exact_priorities=()):
    """Validate every record; optional reference checking avoids materializing text maps."""
    if len(data) < HEADER_SIZE:
        raise BuildError("truncated header")
    (magic, version, rows, exact_count, prefix_count, exact_offset, prefix_offset,
     refs_offset, text_offset, file_length, max_prefix, reserved) = HEADER.unpack_from(data)
    if magic != MAGIC or version != VERSION or reserved != b"\0" * 16:
        raise BuildError("bad magic, version, or reserved header bytes")
    if max_prefix != MAX_PREFIX_COUNT:
        raise BuildError("unexpected prefix candidate limit")
    if file_length != len(data) or file_length > MAX_SIGNED_INT:
        raise BuildError("file length mismatch or unsupported size")
    if (exact_offset != HEADER_SIZE or
            prefix_offset != exact_offset + exact_count * INDEX.size or
            refs_offset != prefix_offset + prefix_count * INDEX.size or
            not refs_offset <= text_offset <= file_length or
            (text_offset - refs_offset) % REFERENCE.size):
        raise BuildError("invalid index/reference section offsets")
    reference_count = (text_offset - refs_offset) // REFERENCE.size
    expected_prefix = defaultdict(list)
    reference_exact = None
    priorities = exact_priority_map(expected_entries or (), exact_priorities)
    if expected_entries is not None:
        # Independent string-code reference, never using writer offsets/code keys.
        ordered = sorted(expected_entries, key=lambda entry: -entry.weight)
        reference_exact = iter(groupby(sorted(ordered, key=lambda entry: entry.code),
                                      key=lambda entry: entry.code))
        for entry in ordered:
            for length in range(1, len(entry.code)):
                prefix = entry.code[:length]
                if len(expected_prefix[prefix]) < MAX_PREFIX_COUNT:
                    expected_prefix[prefix].append((entry.text, entry.code))
        del ordered
    exact = {} if materialize else None
    # Packed integer arrays validate record boundaries/code ownership with <4 MiB.
    record_starts, group_starts, group_keys = array("I"), array("I"), array("I")
    cursor = text_offset
    previous_key = -1
    decoded_rows = 0
    for i in range(exact_count):
        key, start, count = INDEX.unpack_from(data, exact_offset + i * INDEX.size)
        code = decode_code(key)
        if key <= previous_key or start != cursor or count < 1:
            raise BuildError("invalid exact index order, start, or count")
        previous_key = key
        group_starts.append(start)
        group_keys.append(key)
        words = []
        for _ in range(count):
            if cursor + LENGTH.size > file_length:
                raise BuildError("truncated text length")
            length = LENGTH.unpack_from(data, cursor)[0]
            end = cursor + LENGTH.size + length
            if length == 0 or end > file_length:
                raise BuildError("empty or truncated UTF-8 record")
            try:
                word = data[cursor + LENGTH.size:end].decode("utf-8", errors="strict")
            except UnicodeDecodeError as exc:
                raise BuildError("invalid UTF-8 record") from exc
            record_starts.append(cursor)
            words.append(word)
            cursor = end
        if len(set(words)) != len(words):
            raise BuildError("duplicate text+code in exact group")
        if reference_exact is not None:
            expected_code, expected_group = next(reference_exact, (None, ()))
            if expected_code != code:
                raise BuildError("exact index differs from full reference at %s" % code)
            expected_words = [entry.text for entry in expected_group]
            if code in priorities:
                # Independent reference operation: concatenate the preferred words and
                # the remaining original words rather than using the writer's rank sort.
                # Prefix reference construction above intentionally never uses this list.
                preferred = priorities[code]
                expected_words = preferred + [word for word in expected_words if word not in preferred]
            if words != expected_words:
                raise BuildError("exact index differs from full reference at %s" % code)
        decoded_rows += count
        if materialize:
            exact[code] = words
    if decoded_rows != rows or cursor != file_length:
        raise BuildError("entry count mismatch or unreferenced trailing bytes")
    if reference_exact is not None and next(reference_exact, None) is not None:
        raise BuildError("reference has missing exact groups")
    prefixes = {} if materialize else None
    previous_key = -1
    next_ref = 0
    for i in range(prefix_count):
        key, start, count = INDEX.unpack_from(data, prefix_offset + i * INDEX.size)
        prefix = decode_code(key)
        if (key <= previous_key or start != next_ref or
                not 1 <= count <= MAX_PREFIX_COUNT or start + count > reference_count or
                len(prefix) >= 4):
            raise BuildError("invalid prefix index order, start, or count")
        previous_key = key
        candidates = []
        for j in range(start, start + count):
            offset, full_key = REFERENCE.unpack_from(data, refs_offset + j * REFERENCE.size)
            full_code = decode_code(full_key)
            boundary = bisect_left(record_starts, offset)
            owner = bisect_right(group_starts, offset) - 1
            if (boundary >= len(record_starts) or record_starts[boundary] != offset or
                    owner < 0 or group_keys[owner] != full_key or
                    not full_code.startswith(prefix) or len(full_code) <= len(prefix)):
                raise BuildError("prefix reference is not a matching full text record")
            length = LENGTH.unpack_from(data, offset)[0]
            word = data[offset + LENGTH.size:offset + LENGTH.size + length].decode("utf-8")
            candidates.append((word, full_code))
        if expected_entries is not None:
            if candidates != expected_prefix.pop(prefix, None):
                raise BuildError("prefix index differs from full reference at %s" % prefix)
        if materialize:
            prefixes[prefix] = candidates
        next_ref += count
    if next_ref != reference_count:
        raise BuildError("unreferenced prefix records")
    if expected_prefix:
        raise BuildError("reference has missing prefix groups")
    summary = dict(merged_rows=rows, exact_groups=exact_count, prefix_groups=prefix_count,
                   prefix_references=reference_count, file_bytes=file_length,
                   exact_index_bytes=exact_count * INDEX.size,
                   prefix_index_bytes=prefix_count * INDEX.size,
                   prefix_reference_bytes=reference_count * REFERENCE.size,
                   text_data_bytes=file_length - text_offset,
                   exact_index_offset=exact_offset, prefix_index_offset=prefix_offset,
                   prefix_references_offset=refs_offset, text_data_offset=text_offset)
    return exact, prefixes, summary


def validate_against_reference(data, entries, exact_priorities=()):
    _, _, summary = decode_binary(data, expected_entries=entries, materialize=False,
                                  exact_priorities=exact_priorities)
    summary["validated_exact_groups"] = summary["exact_groups"]
    summary["validated_prefix_groups"] = summary["prefix_groups"]
    summary["validated_exact_rows"] = summary["merged_rows"]
    summary["validated_prefix_references"] = summary["prefix_references"]
    return summary


def compile_sources(base, supplements, manifest):
    entries, source_reports = load_sources(base, supplements, manifest)
    # Priority data has one maintained home: all rows of explicitly marked source files.
    # Repeated pairs across marked sources retain the first priority source's order.
    priority_pairs = dict.fromkeys((item["text"], item["code"])
                                  for source in source_reports
                                  for item in source.get("exact_priorities", []))
    exact_priorities = [dict(text=text, code=code) for text, code in priority_pairs]
    binary = build_binary(entries, exact_priorities)
    summary = validate_against_reference(binary, entries, exact_priorities)
    summary["baseline_rows"] = source_reports[0]["retained_rows"]
    summary["added_supplement_rows"] = sum(s["retained_rows"] for s in source_reports[1:])
    for key, expected in manifest.get("expected", {}).items():
        if summary.get(key) != expected:
            raise BuildError("%s expected %s, got %s" % (key, expected, summary.get(key)))
    report = dict(format=MAGIC.decode(), endian="big", sha256=sha256(binary),
                  max_prefix_count=MAX_PREFIX_COUNT, counts=summary, sources=source_reports,
                  input_rows=sum(s["line_accounting"]["valid_rows"] for s in source_reports),
                  duplicate_rows=sum(s["duplicate_rows"] for s in source_reports),
                  rejected_rows=0, frequency_filtered_rows=0, character_filtered_rows=0,
                  exact_priorities=exact_priorities,
                  exact_priority_policy="At build time only, rows from sources marked exact_priority=true lead their complete-code exact groups in priority-source and row order; retain every other row's relative order. Prefixes use static weight descending, then the current manifest source order and source row order for ties; exact-only promotion does not affect prefix selection.",
                  validation="All exact groups and rows match the stable-weight reference with explicit exact-only priorities. All prefix groups and references match the current manifest stable-weight/source-order reference without exact-only promotion.",
                  determinism="No timestamps, absolute paths, random values or locale-dependent ordering in output.",
                  licensing=manifest.get("supplement_license_status", "See source manifest."))
    return binary, report


def main(argv=None):
    project = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, default=project,
                        help="Repository root for default baseline, supplements, manifest and output paths.")
    parser.add_argument("--base", type=Path)
    parser.add_argument("--supplements", type=Path)
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--verify-only", action="store_true", help="Rebuild in memory and require existing output bytes to match; never rewrite.")
    args = parser.parse_args(argv)
    args.base = args.base or args.source_root / "app/src/main/assets/dictionaries/wubi86.dict.yaml"
    args.supplements = args.supplements or args.source_root / "third_party/wubi_supplements"
    args.manifest = args.manifest or args.supplements / "source-manifest.json"
    args.output = args.output or args.source_root / "app/src/main/assets/dictionaries/compact-wubi.skwb"
    try:
        manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
        binary, report = compile_sources(args.base, args.supplements, manifest)
        if args.verify_only:
            if args.output.read_bytes() != binary:
                raise BuildError("existing asset is not the deterministic output of pinned sources")
        else:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_bytes(binary)
        if args.report and not args.verify_only:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"verified" if args.verify_only else "built": str(args.output),
                          "sha256": report["sha256"], **report["counts"]}, ensure_ascii=False))
    except (BuildError, OSError, ValueError, KeyError, TypeError, struct.error) as exc:
        parser.exit(1, "ERROR: %s\n" % exc)
    return 0


if __name__ == "__main__":
    sys.exit(main())
