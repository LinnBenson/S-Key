# SKWB0001 format

All integers are unsigned big-endian on disk, and all counts/offsets fit a signed
32-bit Android integer. All offsets are absolute file offsets except the prefix
index's reference ordinal. There is no platform padding or alignment requirement.

## Header (64 bytes)

| Offset | Bytes | Field |
| --- | --- | --- |
| 0 | 8 | ASCII `SKWB0001` |
| 8 | 4 | Version, 1 |
| 12 | 4 | Retained entry count |
| 16 | 4 | Exact-group count |
| 20 | 4 | Prefix-group count |
| 24 | 4 | Exact-index offset, 64 |
| 28 | 4 | Prefix-index offset |
| 32 | 4 | Prefix-reference section offset |
| 36 | 4 | Text-data offset |
| 40 | 4 | Complete file length |
| 44 | 4 | Prefix candidate limit, 30 |
| 48 | 16 | Reserved, all zero |

## Code keys

One to four lowercase ASCII letters; `a` = 1 through `z` = 26. Form four base-27
digits, padding missing trailing digits with zero. `a` = 1×27³; `ab` = 1×27³ +
2×27². A zero followed by a nonzero digit is invalid. Numeric order equals code
lexicographic order. A proper prefix is shorter than its full code.

## Exact index

One 12-byte record per distinct code, sorted by code key:
`uint32 codeKey, uint32 absoluteTextOffset, uint32 rowCount`.

Text records for each exact code are contiguous. All exact groups collectively
cover the complete text section once, with no gaps or trailing bytes. Within a
code, rows retain global stable descending effective-weight order by default.
For a source marked `exact_priority: true`, its deduplicated word/code rows
lead that complete-code group in manifest/source-file order. Remaining rows
keep their original relative order. Priority is compiled offline; there is no
new runtime table or binary-format version. Prefix selection uses static weight followed by the current manifest source
order and is not recomputed from promoted exact groups. Changing source order
can change ordinary equal-weight and prefix order; no binary-format change is
required.

## Prefix index and references

One 12-byte record per proper prefix, sorted by code key:
`uint32 codeKey, uint32 startReferenceOrdinal, uint32 count`.

`count` is 1–30. Index records cover the reference section contiguously. Each
8-byte reference is `uint32 absoluteTextOffset, uint32 fullCodeKey`. The offset
must point to an actual text-record start whose exact group has that full code.
Prefix groups retain the first 30 entries visited in global stable-weight order,
including distinct codes for the same text. The runtime may deduplicate display
text when combining results, just as the previous engine did.

## Text data

Each record is `uint16 utf8ByteLength` followed immediately by that many bytes.
Length is 1–65,535. Decode strict UTF-8. Strings are unnormalized and may contain
non-BMP characters. Weight is only a build-time ranking input and is not stored.

## Pinned asset layout

- File: 8,624,280 bytes, SHA-256 `009a8866d29c85c3a7b0e938578a10a82fce11ab5683048ce41889ece1ad5278`
- Exact index: offset 64, 2,230,488 bytes, 185,874 records
- Prefix index: offset 2,230,552, 193,236 bytes, 16,103 records
- Prefix references: offset 2,423,788, 2,444,616 bytes, 305,577 records
- Text data: offset 4,868,404, 3,755,876 bytes, 441,788 records

## Validation and determinism

The builder first checks each source's byte size, SHA-256, row count, code syntax,
UTF-8 and line accounting. Unsupported rows are errors, never silently discarded.
The only non-integer baseline weight is an explicit pinned compatibility row in
the manifest: `χ / zzxx / 450.5` is effective zero, matching the previous engine.
The converter executes no YAML or downloaded code.

After encoding, validation checks every header/index/record boundary, Unicode
record, count, proper-prefix relationship and reference ownership. Every exact
candidate sequence and prefix candidate sequence is compared with an independent
string-key reference derived from the merged sources. There are no timestamps,
absolute paths, random values or locale-dependent ordering in the asset/report.

The prefix limit bounds completions only. It does not delete exact-code rows or
apply a frequency filter. Original source files remain the editable/rebuildable
source of truth; do not edit binary offsets by hand.
