#!/usr/bin/env python3
"""Compile OpenCC longest-match mappings into a memory-mappable UTF-16 trie."""
from pathlib import Path
import struct

ROOT = Path(__file__).resolve().parents[1]

def build():
    nodes = [({}, b'')]
    for name in ('STCharacters.txt', 'STPhrases.txt'):
        for line in (ROOT / 'app/src/main/assets/opencc' / name).read_text(encoding='utf-8').splitlines():
            if not line or line.startswith('#'):
                continue
            key, values = line.split('\t', 1)
            raw = key.encode('utf-16-le')
            node = 0
            for (unit,) in struct.iter_unpack('<H', raw):
                children = nodes[node][0]
                if unit not in children:
                    children[unit] = len(nodes)
                    nodes.append(({}, b''))
                node = children[unit]
            nodes[node] = (nodes[node][0], values.split(' ')[0].encode('utf-16-le'))
    edge_count = sum(len(children) for children, _ in nodes)
    value_start = 16 + len(nodes) * 16 + edge_count * 8
    records, edges, values = bytearray(), bytearray(), bytearray()
    for children, value in nodes:
        records.extend(struct.pack('<4I', len(edges) // 8, len(children), value_start + len(values), len(value) // 2))
        for unit, child in sorted(children.items()):
            edges.extend(struct.pack('<2I', unit, child))
        values.extend(value)
    output = ROOT / 'app/build/generated/traditionalIndex/opencc/conversion.skt'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(struct.pack('<4I', 0x534b5431, len(nodes), edge_count, value_start) + records + edges + values)
    print(f'Compiled OpenCC: {len(nodes)} nodes, {output.stat().st_size} bytes')

if __name__ == '__main__':
    build()
