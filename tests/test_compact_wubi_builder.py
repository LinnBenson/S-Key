#!/usr/bin/env python3
"""Stdlib unit + full pinned-source integration tests; no Android/JVM required."""
import importlib.util
from contextlib import redirect_stdout, redirect_stderr
import io
import json
import os
from pathlib import Path
import struct
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
SPEC = importlib.util.spec_from_file_location("build_compact_wubi", ROOT / "scripts/build_compact_wubi.py")
builder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(builder)
E = builder.Entry


def source(rows, header="---\nname: fixture\n...\n"):
    return (header + rows).encode("utf-8")


def source_spec(filename, content, role, rows):
    return dict(filename=filename, bytes=len(content), sha256=builder.sha256(content),
                role=role, valid_rows=rows)


def fixture_manifest(specs):
    return dict(schema_version=1, format="SKWB0001", max_prefix_count=30, sources=specs)


class CodeTests(unittest.TestCase):
    def test_known_big_endian_code_keys(self):
        self.assertEqual(builder.encode_code("a"), 27 ** 3)
        self.assertEqual(builder.encode_code("ab"), 27 ** 3 + 2 * 27 ** 2)
        self.assertEqual(builder.encode_code("zzzz"), 27 ** 4 - 1)
        self.assertEqual(struct.pack(">I", builder.encode_code("a")), b"\0\0L\xe3")

    def test_all_valid_keys_round_trip_and_sort(self):
        # Exercise every possible one- to four-letter code, not just source keys.
        previous = ""
        count = 0
        for key in range(1, 27 ** 4):
            try:
                code = builder.decode_code(key)
            except builder.BuildError:
                continue
            self.assertEqual(builder.encode_code(code), key)
            self.assertGreater(code, previous)
            previous = code
            count += 1
        self.assertEqual(count, sum(26 ** length for length in range(1, 5)))

    def test_invalid_codes_and_padding(self):
        for code in ("", "A", "a b", "abcde", "a'", "五笔", "a\n"):
            with self.subTest(code=code), self.assertRaises(builder.BuildError):
                builder.encode_code(code)
        for key in (0, -1, 27 ** 4, 1, 27 ** 3 + 1):
            with self.subTest(key=key), self.assertRaises(builder.BuildError):
                builder.decode_code(key)


class ParserTests(unittest.TestCase):
    def test_unicode_and_whitespace_preserved(self):
        rows = "甲\ta\t5\taaaa\n𠀀𰻞\tab\n 有空格 \tabc\t0\n"
        entries, stats = builder.parse_dictionary(source(rows), "fixture")
        self.assertEqual(entries, [E("甲", "a", 5), E("𠀀𰻞", "ab", 0), E(" 有空格 ", "abc", 0)])
        self.assertEqual(stats["valid_rows"], 3)
        self.assertEqual(stats["total_lines"], sum(v for k, v in stats.items() if k != "total_lines"))

    def test_bom_crlf_comments_blank_lines(self):
        content = b"\xef\xbb\xbf" + source("#comment\n\n甲\taa\n").replace(b"\n", b"\r\n")
        entries, stats = builder.parse_dictionary(content, "fixture")
        self.assertEqual(entries, [E("甲", "aa", 0)])
        self.assertEqual(stats["comment_lines"], 1)
        self.assertEqual(stats["blank_lines"], 1)

    def test_header_is_not_executed(self):
        content = source("甲\ta\n", "---\n!!python/object/apply:os.system ['false']\nimport_tables: [untrusted]\n...\n")
        self.assertEqual(builder.parse_dictionary(content, "fixture")[0], [E("甲", "a", 0)])

    def test_missing_header_marker_fails(self):
        for content in (b"foo", b"...\nword\ta\n", b"---\nword\ta\n"):
            with self.subTest(content=content), self.assertRaises(builder.BuildError):
                builder.parse_dictionary(content, "fixture")

    def test_all_malformed_rows_fail(self):
        for row in ("bad", "\ta", "word\ta b", "word\tABCDE", "word\tabcde",
                    "word\ta\t", "word\ta\t12.5", "word\ta\t9223372036854775808",
                    "word\ta\t0\tstem\textra", "word\x00\ta", "...", "  #comment"):
            with self.subTest(row=row), self.assertRaises(builder.BuildError):
                builder.parse_dictionary(source(row + "\n"), "fixture")

    def test_invalid_utf8_and_oversized_text_fail(self):
        for content in (b"---\n...\n\xff\ta\n", source("字" * 21846 + "\ta\n")):
            with self.assertRaises(builder.BuildError):
                builder.parse_dictionary(content, "fixture")

    def test_pinned_weight_fallback_only(self):
        fallback = dict(line=4, text="χ", code="zzxx", raw_weight="450.5")
        entries, _ = builder.parse_dictionary(source("χ\tzzxx\t450.5\n"), "fixture", weight_zero_fallbacks=[fallback])
        self.assertEqual(entries, [E("χ", "zzxx", 0)])
        with self.assertRaises(builder.BuildError):
            builder.parse_dictionary(source("χ\tzzxx\t450.6\n"), "fixture", weight_zero_fallbacks=[fallback])
        with self.assertRaises(builder.BuildError):
            builder.parse_dictionary(source("甲\ta\n"), "fixture", weight_zero_fallbacks=[fallback])

    def test_supplement_nonzero_weight_fails(self):
        with self.assertRaises(builder.BuildError):
            builder.parse_dictionary(source("甲\ta\t1\n"), "fixture", supplement=True)


class MergeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.base_data = source("甲\ta\t5\n相同\tab\t0\n保留\ta\t0\n")
        self.extra_data = source("相同\tab\n新词\tab\n新词\tab\n相同\tac\n")
        self.base = self.root / "base.yaml"
        self.base.write_bytes(self.base_data)
        (self.root / "extra.yaml").write_bytes(self.extra_data)
        self.manifest = fixture_manifest([
            source_spec("base.yaml", self.base_data, "baseline", 3),
            source_spec("extra.yaml", self.extra_data, "supplement", 4),
        ])

    def test_first_wins_exact_dedup_without_word_filter(self):
        entries, reports = builder.load_sources(self.base, self.root, self.manifest)
        self.assertEqual(entries, [E("甲", "a", 5), E("相同", "ab", 0), E("保留", "a", 0), E("新词", "ab", 0), E("相同", "ac", 0)])
        self.assertEqual(reports[1]["duplicate_rows"], 2)
        self.assertEqual(reports[1]["duplicates_previous_sources"], 1)
        self.assertEqual(reports[1]["duplicates_within_source"], 1)

    def test_source_order_breaks_ties_after_weight_and_before_row_order(self):
        base_data = source("基础高\tabcd\t100\n基础同\tabcd\t0\n基础低\tabcd\t-1\n")
        self.base.write_bytes(base_data)
        specs = [source_spec("base.yaml", base_data, "baseline", 3)]
        for name, texts in (("high", ("优先甲", "优先乙")), ("it", ("技术",)),
                            ("xzqh", ("行政",)), ("ciyu", ("词语",)),
                            ("cities", ("名城",)), ("chengyu", ("成语",))):
            data = source("".join(text + "\tabcd\n" for text in texts))
            (self.root / (name + ".yaml")).write_bytes(data)
            spec = source_spec(name + ".yaml", data, "supplement", len(texts))
            if name == "high":
                spec["exact_priority"] = True
            specs.append(spec)
        manifest = fixture_manifest(specs)
        entries, _ = builder.load_sources(self.base, self.root, manifest)
        ordinary = ["基础高", "基础同", "优先甲", "优先乙", "技术", "行政", "词语", "名城", "成语", "基础低"]
        self.assertEqual([entry.text for entry in entries], ordinary)
        binary, _ = builder.compile_sources(self.base, self.root, manifest)
        exact, prefixes, _ = builder.decode_binary(binary)
        self.assertEqual(exact["abcd"], ["优先甲", "优先乙"] +
                         [text for text in ordinary if text not in ("优先甲", "优先乙")])
        for prefix in ("a", "ab", "abc"):
            self.assertEqual(prefixes[prefix], [(text, "abcd") for text in ordinary])
        previous_order = [specs[index] for index in (0, 6, 5, 4, 2, 3, 1)]
        previous_binary, _ = builder.compile_sources(self.base, self.root, fixture_manifest(previous_order))
        previous_exact, previous_prefixes, _ = builder.decode_binary(previous_binary)
        self.assertNotEqual(exact["abcd"], previous_exact["abcd"])
        self.assertNotEqual(prefixes["abc"], previous_prefixes["abc"])
        self.assertEqual(set(exact["abcd"]), set(previous_exact["abcd"]))
        self.assertEqual(prefixes["abc"][0][0], previous_prefixes["abc"][0][0])
        self.assertEqual(prefixes["abc"][-1][0], previous_prefixes["abc"][-1][0])

    def test_changed_bytes_or_counts_fail(self):
        self.base.write_bytes(self.base_data + b"\n")
        with self.assertRaises(builder.BuildError):
            builder.load_sources(self.base, self.root, self.manifest)
        self.base.write_bytes(self.base_data)
        self.manifest["sources"][0]["valid_rows"] += 1
        with self.assertRaises(builder.BuildError):
            builder.load_sources(self.base, self.root, self.manifest)

    def test_manifest_path_traversal_and_order_fail(self):
        self.manifest["sources"][1]["filename"] = "../extra.yaml"
        with self.assertRaises(builder.BuildError):
            builder.load_sources(self.base, self.root, self.manifest)
        self.manifest["sources"][1]["filename"] = "extra.yaml"
        self.manifest["sources"][1]["role"] = "baseline"
        with self.assertRaises(builder.BuildError):
            builder.load_sources(self.base, self.root, self.manifest)


class BinaryTests(unittest.TestCase):
    def setUp(self):
        self.entries = [E("低", "abcd", 0), E("甲", "a", 20), E("高", "abcd", 10),
                        E("同权甲", "abce", 0), E("同权乙", "abcf", 0), E("𠀀", "zzzz", 0)]
        self.binary = builder.build_binary(self.entries)

    def test_header_layout_and_round_trip(self):
        fields = builder.HEADER.unpack_from(self.binary)
        self.assertEqual(fields[0:3], (b"SKWB0001", 1, 6))
        self.assertEqual(fields[5], 64)
        self.assertEqual(fields[9], len(self.binary))
        self.assertEqual(fields[10:12], (30, b"\0" * 16))
        exact, prefixes, summary = builder.decode_binary(self.binary)
        self.assertEqual(exact["abcd"], ["高", "低"])
        self.assertEqual(prefixes["a"], [("高", "abcd"), ("低", "abcd"), ("同权甲", "abce"), ("同权乙", "abcf")])
        self.assertEqual(exact["zzzz"], ["𠀀"])
        self.assertEqual(summary["merged_rows"], 6)
        self.assertEqual(builder.validate_against_reference(self.binary, self.entries)["validated_exact_rows"], 6)

    def test_prefix_cap_does_not_cap_exact_rows(self):
        entries = [E("字%d" % index, "abcd", 100 - index) for index in range(50)]
        exact, prefixes, _ = builder.decode_binary(builder.build_binary(entries))
        self.assertEqual(len(exact["abcd"]), 50)
        for prefix in ("a", "ab", "abc"):
            self.assertEqual(prefixes[prefix], [("字%d" % index, "abcd") for index in range(30)])
        self.assertNotIn("abcd", prefixes)

    def test_deterministic_bytes(self):
        self.assertEqual(self.binary, builder.build_binary(list(self.entries)))

    def test_duplicate_records_rejected(self):
        with self.assertRaises(builder.BuildError):
            builder.build_binary([E("甲", "aa", 3), E("甲", "aa", 0)])

    def test_header_corruption_rejected(self):
        for offset, value in ((0, ord("X")), (11, 2), (48, 1)):
            corrupt = bytearray(self.binary)
            corrupt[offset] = value
            with self.subTest(offset=offset), self.assertRaises(builder.BuildError):
                builder.decode_binary(corrupt)
        for offset, value in ((12, 7), (24, 65), (28, 64), (36, len(self.binary) + 1),
                              (40, len(self.binary) - 1), (44, 31)):
            corrupt = bytearray(self.binary)
            struct.pack_into(">I", corrupt, offset, value)
            with self.subTest(offset=offset), self.assertRaises(builder.BuildError):
                builder.decode_binary(corrupt)
        with self.assertRaises(builder.BuildError):
            builder.decode_binary(self.binary[:63])

    def test_exact_record_corruption_rejected(self):
        for offset, value in ((64, 1), (68, 64), (72, 0)):
            corrupt = bytearray(self.binary)
            struct.pack_into(">I", corrupt, offset, value)
            with self.subTest(offset=offset), self.assertRaises(builder.BuildError):
                builder.decode_binary(corrupt)
        text_start = struct.unpack_from(">I", self.binary, 36)[0]
        corrupt = bytearray(self.binary)
        corrupt[text_start + 2] = 0xff
        with self.assertRaises(builder.BuildError):
            builder.decode_binary(corrupt)
        corrupt = bytearray(self.binary)
        struct.pack_into(">H", corrupt, text_start, 65535)
        with self.assertRaises(builder.BuildError):
            builder.decode_binary(corrupt)

    def test_prefix_corruption_rejected(self):
        prefix_start = struct.unpack_from(">I", self.binary, 28)[0]
        refs_start = struct.unpack_from(">I", self.binary, 32)[0]
        text_start = struct.unpack_from(">I", self.binary, 36)[0]
        for offset, value in ((prefix_start + 4, 1), (prefix_start + 8, 31),
                              (refs_start, text_start + 1),
                              (refs_start + 4, builder.encode_code("zzzz"))):
            corrupt = bytearray(self.binary)
            struct.pack_into(">I", corrupt, offset, value)
            with self.subTest(offset=offset), self.assertRaises(builder.BuildError):
                builder.decode_binary(corrupt)

    def test_reference_detects_ranking_mismatch(self):
        swapped = [E(entry.text, entry.code, -entry.weight) for entry in self.entries]
        with self.assertRaises(builder.BuildError):
            builder.validate_against_reference(self.binary, swapped)

    def test_exact_only_priority_keeps_prefixes_and_independent_reference(self):
        priorities = [dict(text="低", code="abcd")]
        promoted = builder.build_binary(self.entries, priorities)
        exact, prefixes, summary = builder.decode_binary(promoted)
        old_exact, old_prefixes, old_summary = builder.decode_binary(self.binary)
        self.assertEqual(exact["abcd"], ["低", "高"])
        self.assertEqual({k: v for k, v in exact.items() if k != "abcd"},
                         {k: v for k, v in old_exact.items() if k != "abcd"})
        self.assertEqual(prefixes, old_prefixes)
        self.assertEqual(summary, old_summary)
        builder.validate_against_reference(promoted, self.entries, priorities)
        with self.assertRaises(builder.BuildError):
            builder.validate_against_reference(promoted, self.entries)
        with self.assertRaises(builder.BuildError):
            builder.validate_against_reference(self.binary, self.entries, priorities)
        self.assertEqual(promoted, builder.build_binary(self.entries, priorities))

    def test_invalid_or_missing_priority_pair_is_rejected(self):
        for priorities in (None, {}, [{"text": "低"}],
                           [{"text": "低", "code": "abcde"}],
                           [{"text": "低", "code": "ABCD"}],
                           [{"text": "", "code": "abcd"}],
                           [{"text": "缺失", "code": "abcd"}],
                           [{"text": "低", "code": "abcd"}] * 2):
            with self.subTest(priorities=priorities), self.assertRaises(builder.BuildError):
                builder.build_binary(self.entries, priorities)


class HighPrioritySourceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.base = self.root / "base.yaml"
        self.base_data = source("原首\tabcd\t100\n普通乙\tabcd\t90\n重复项\tabcd\t80\n普通丙\tabcd\t70\n无关\tzzzz\t60\n")
        self.base.write_bytes(self.base_data)
        self.high = self.root / "high.yaml"

    def compile(self, rows, marked=True):
        high_data = source(rows)
        self.high.write_bytes(high_data)
        high_spec = source_spec(self.high.name, high_data, "supplement", len(rows.splitlines()))
        high_spec["exact_priority"] = marked
        manifest = fixture_manifest([source_spec(self.base.name, self.base_data, "baseline", 5),
                                     high_spec])
        binary, report = builder.compile_sources(self.base, self.root, manifest)
        return binary, report, manifest

    def test_priority_source_promotes_existing_pairs_and_same_code_rows_in_file_order(self):
        rows = "重复项\tabcd\n高优甲\tabcd\n高优乙\tabcd\n重复项\tabcd\n"
        binary, report, manifest = self.compile(rows)
        exact, prefixes, _ = builder.decode_binary(binary)
        self.assertEqual(exact["abcd"], ["重复项", "高优甲", "高优乙", "原首", "普通乙", "普通丙"])
        self.assertEqual(exact["zzzz"], ["无关"])
        self.assertEqual(report["exact_priorities"],
                         [dict(text=t, code="abcd") for t in ("重复项", "高优甲", "高优乙")])
        self.assertEqual(report["sources"][-1]["retained_rows"], 2)
        self.assertEqual(report["sources"][-1]["duplicate_rows"], 2)
        ordinary_binary, _, _ = self.compile(rows, marked=False)
        ordinary_exact, ordinary_prefixes, _ = builder.decode_binary(ordinary_binary)
        self.assertEqual(ordinary_exact["abcd"], ["原首", "普通乙", "重复项", "普通丙", "高优甲", "高优乙"])
        self.assertEqual(prefixes, ordinary_prefixes)
        again, again_report = builder.compile_sources(self.base, self.root, manifest)
        self.assertEqual(binary, again)
        self.assertEqual(report, again_report)

    def test_editing_only_priority_rows_changes_priority_and_removing_restores_ordinary_order(self):
        first, _, _ = self.compile("高优甲\tabcd\n重复项\tabcd\n")
        changed, _, _ = self.compile("重复项\tabcd\n高优甲\tabcd\n")
        self.assertEqual(builder.decode_binary(first)[0]["abcd"][:2], ["高优甲", "重复项"])
        self.assertEqual(builder.decode_binary(changed)[0]["abcd"][:2], ["重复项", "高优甲"])
        removed, report, _ = self.compile("")
        self.assertEqual(report["exact_priorities"], [])
        self.assertEqual(builder.decode_binary(removed)[0]["abcd"], ["原首", "普通乙", "重复项", "普通丙"])

    def test_short_complete_codes_promote_only_their_exact_groups(self):
        self.base_data = source("短首\ta\t100\n二首\tab\t90\n三首\tabc\t80\n四首\tabcd\t70\n无关\tzzzz\t60\n")
        self.base.write_bytes(self.base_data)
        rows = "短优\ta\n二优\tab\n三优\tabc\n四优\tabcd\n"
        binary, _, _ = self.compile(rows)
        ordinary_binary, _, _ = self.compile(rows, marked=False)
        exact, prefixes, _ = builder.decode_binary(binary)
        ordinary_exact, ordinary_prefixes, _ = builder.decode_binary(ordinary_binary)
        for code, first in (("a", "短优"), ("ab", "二优"), ("abc", "三优"), ("abcd", "四优")):
            self.assertEqual(exact[code], [first, ordinary_exact[code][0]])
        self.assertEqual(prefixes, ordinary_prefixes)
        self.assertEqual(prefixes["a"], [("二首", "ab"), ("三首", "abc"), ("四首", "abcd"),
                                          ("二优", "ab"), ("三优", "abc"), ("四优", "abcd")])

    def test_invalid_priority_marker_is_rejected(self):
        for marker in ("true", 1, None):
            with self.subTest(marker=marker), self.assertRaises(builder.BuildError):
                self.compile("高优甲\tabcd\n", marked=marker)
        _, _, manifest = self.compile("高优甲\tabcd\n")
        manifest["sources"][0]["exact_priority"] = True
        with self.assertRaises(builder.BuildError):
            builder.compile_sources(self.base, self.root, manifest)


class CommandLineTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        assets = self.root / "app/src/main/assets/dictionaries"
        assets.mkdir(parents=True)
        supplements = self.root / "third_party/wubi_supplements"
        supplements.mkdir(parents=True)
        content = source("甲\ta\t5\n乙\tab\n")
        (assets / "wubi86.dict.yaml").write_bytes(content)
        manifest = fixture_manifest([source_spec("wubi86.dict.yaml", content, "baseline", 2)])
        self.manifest = supplements / "source-manifest.json"
        self.manifest.write_text(json.dumps(manifest), encoding="utf-8")
        self.output = assets / "compact-wubi.skwb"
        self.report = supplements / "BUILD_REPORT.json"

    def test_source_root_defaults_and_verify_only_no_rewrite(self):
        with redirect_stdout(io.StringIO()):
            self.assertEqual(builder.main(["--source-root", str(self.root), "--report", str(self.report)]), 0)
        before_output = self.output.read_bytes()
        before_report = self.report.read_bytes()
        before_modified = self.output.stat().st_mtime_ns
        with redirect_stdout(io.StringIO()):
            self.assertEqual(builder.main(["--source-root", str(self.root), "--report", str(self.report), "--verify-only"]), 0)
        self.assertEqual(self.output.read_bytes(), before_output)
        self.assertEqual(self.output.stat().st_mtime_ns, before_modified)
        self.assertEqual(self.report.read_bytes(), before_report)
        self.output.write_bytes(b"mismatching output")
        with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as raised:
            builder.main(["--source-root", str(self.root), "--verify-only"])
        self.assertEqual(raised.exception.code, 1)
        self.assertEqual(self.output.read_bytes(), b"mismatching output")

    def test_expected_count_failure_never_creates_output(self):
        manifest = json.loads(self.manifest.read_text())
        manifest["expected"] = {"merged_rows": 999}
        self.manifest.write_text(json.dumps(manifest), encoding="utf-8")
        with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            builder.main(["--source-root", str(self.root)])
        self.assertFalse(self.output.exists())


class FullPinnedSourceTests(unittest.TestCase):
    def test_manifest_uses_requested_source_order_and_all_groups_match_independent_oracle(self):
        base = ROOT / "app/src/main/assets/dictionaries/wubi86.dict.yaml"
        supplements = ROOT / "third_party/wubi_supplements"
        manifest = json.loads((supplements / "source-manifest.json").read_text(encoding="utf-8"))
        filenames = ["wubi86.dict.yaml", "wubi86_high_priority.dict.yaml",
                     "wubi86_dict_it.dict.yaml", "wubi86_dict_xzqh.dict.yaml",
                     "wubi86_dict_ciyu.dict.yaml", "wubi86_dict_china_cultural_cities.dict.yaml",
                     "wubi86_dict_chengyu.dict.yaml"]
        self.assertEqual([item["filename"] for item in manifest["sources"]], filenames)
        # Independently parse pinned literal rows and implement the requested sort.
        # Do not reuse builder parsing, merge, code grouping, or priority helpers.
        retained = {}
        preferred = {}
        for source_index, filename in enumerate(filenames):
            path = base if source_index == 0 else supplements / filename
            content = path.read_text(encoding="utf-8-sig").split("\n...\n", 1)[1]
            for row_index, line in enumerate(content.splitlines()):
                if not line.strip() or line.startswith("#"):
                    continue
                fields = line.split("\t")
                text, code = fields[:2]
                weight = int(fields[2]) if len(fields) >= 3 and fields[2].lstrip("-").isdigit() else 0
                retained.setdefault((text, code), (weight, source_index, row_index))
                if filename == "wubi86_high_priority.dict.yaml":
                    preferred.setdefault(code, [])
                    if text not in preferred[code]:
                        preferred[code].append(text)
        ordered = sorted(retained, key=lambda pair: (-retained[pair][0], *retained[pair][1:]))
        expected_exact, expected_prefix = {}, {}
        for text, code in ordered:
            expected_exact.setdefault(code, []).append(text)
            for length in range(1, len(code)):
                bucket = expected_prefix.setdefault(code[:length], [])
                if len(bucket) < 30:
                    bucket.append((text, code))
        for code, words in preferred.items():
            expected_exact[code] = words + [word for word in expected_exact[code] if word not in words]
        exact, prefixes, _ = builder.decode_binary(
            (ROOT / "app/src/main/assets/dictionaries/compact-wubi.skwb").read_bytes())
        self.assertEqual(exact, expected_exact)
        self.assertEqual(prefixes, expected_prefix)
        self.assertEqual(len(retained), 441799)

    def test_all_pinned_rows_all_keys_and_two_identical_builds(self):
        base = Path(os.environ.get("WUBI_BASE", ROOT / "app/src/main/assets/dictionaries/wubi86.dict.yaml"))
        supplements = Path(os.environ.get("WUBI_SUPPLEMENTS", ROOT / "third_party/wubi_supplements"))
        manifest_path = Path(os.environ.get("WUBI_MANIFEST", ROOT / "third_party/wubi_supplements/source-manifest.json"))
        if not base.exists() or not supplements.exists() or not manifest_path.exists():
            self.skipTest("Set WUBI_BASE, WUBI_SUPPLEMENTS and WUBI_MANIFEST to run pinned-source integration")
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        binary, report = builder.compile_sources(base, supplements, manifest)
        self.assertEqual(report["counts"]["merged_rows"], 441799)
        self.assertEqual(report["counts"]["validated_exact_groups"], 185875)
        self.assertEqual(report["counts"]["validated_prefix_groups"], 16103)
        self.assertEqual(report["counts"]["validated_prefix_references"], 305580)
        self.assertEqual(report["input_rows"], 488447)
        self.assertEqual(report["duplicate_rows"], 46648)
        self.assertEqual(report["rejected_rows"], 0)
        self.assertEqual(report["counts"]["file_bytes"], 8624407)
        self.assertEqual(report["sha256"], "cdef877c01c7fa02816df4f54608ec0ae521d78def2c010d67f5f0a68b256cfb")
        existing = ROOT / "app/src/main/assets/dictionaries/compact-wubi.skwb"
        self.assertEqual(existing.read_bytes(), binary)
        binary_again, report_again = builder.compile_sources(base, supplements, manifest)
        self.assertEqual(binary, binary_again)
        self.assertEqual(report, report_again)

    def test_local_builtin_pairs_keep_literal_codes_and_every_original_row(self):
        base = ROOT / "app/src/main/assets/dictionaries/wubi86.dict.yaml"
        supplements = ROOT / "third_party/wubi_supplements"
        manifest = json.loads((supplements / "source-manifest.json").read_text(encoding="utf-8"))
        requested = [E("微信", "tmwy", 0), E("安卓", "pvhj", 0), E("北川", "uxkt", 0),
                     E("美团", "uglt", 0), E("抖音", "ruuj", 0), E("支付宝", "fwpg", 0),
                     E("滴滴", "iuiu", 0), E("微博", "tmfg", 0),
                     E("嗯嗯", "klkl", 0), E("哦哦", "ktkt", 0), E("好吧", "vbkc", 0)]
        local = next(item for item in manifest["sources"]
                     if item["filename"] == "wubi86_high_priority.dict.yaml")
        self.assertEqual(local["filename"], "wubi86_high_priority.dict.yaml")
        self.assertEqual(local["role"], "supplement")
        self.assertIs(local["exact_priority"], True)
        self.assertNotIn("exact_priorities", manifest)
        self.assertFalse((supplements / "wubi86_dict_local_builtin.dict.yaml").exists())
        local_rows, _ = builder.parse_dictionary((supplements / local["filename"]).read_bytes(),
                                                  local["filename"], supplement=True)
        self.assertEqual(local_rows, requested)
        provenance = next(item for item in manifest["provenance_files"]
                          if item["filename"] == local["provenance_file"])
        provenance_bytes = (supplements / provenance["filename"]).read_bytes()
        self.assertEqual(provenance["bytes"], len(provenance_bytes))
        self.assertEqual(provenance["sha256"], builder.sha256(provenance_bytes))

        merged, reports = builder.load_sources(base, supplements, manifest)
        original_manifest = dict(manifest, sources=[item for item in manifest["sources"]
                                                    if item["filename"] != local["filename"]])
        original, _ = builder.load_sources(base, supplements, original_manifest)
        requested_pairs = {(entry.text, entry.code) for entry in requested}
        self.assertEqual(len(original), 441788)
        self.assertFalse(any((entry.text, entry.code) in requested_pairs for entry in original))
        self.assertEqual([entry for entry in merged if (entry.text, entry.code) not in requested_pairs],
                         original)
        local_report = next(item for item in reports if item["filename"] == local["filename"])
        self.assertEqual(local_report["retained_rows"], 11)
        self.assertEqual(local_report["duplicate_rows"], 0)

        binary = (ROOT / "app/src/main/assets/dictionaries/compact-wubi.skwb").read_bytes()
        exact, prefixes, summary = builder.decode_binary(binary)
        # Use the CURRENT requested source tie order with promotions disabled.
        # Exact-only promotion must not change this order's proper prefixes. A source
        # reorder itself may change equal-weight exact and prefix candidates.
        ordinary_binary = builder.build_binary(merged)
        old_exact, old_prefixes, old_summary = builder.decode_binary(ordinary_binary)
        self.assertEqual(prefixes, old_prefixes)
        self.assertEqual(summary, old_summary)
        target_codes = {entry.code for entry in requested}
        self.assertEqual({k: v for k, v in exact.items() if k not in target_codes},
                         {k: v for k, v in old_exact.items() if k not in target_codes})
        self.assertEqual({code for code in exact if exact[code] != old_exact[code]},
                         target_codes - {"uxkt"})  # 北川 was already the sole candidate.
        for entry in requested:
            with self.subTest(text=entry.text, code=entry.code):
                self.assertEqual(exact[entry.code].count(entry.text), 1)
                self.assertEqual(exact[entry.code][0], entry.text)
                self.assertEqual(exact[entry.code][1:],
                                 [text for text in old_exact[entry.code] if text != entry.text])


if __name__ == "__main__":
    unittest.main(verbosity=2)
