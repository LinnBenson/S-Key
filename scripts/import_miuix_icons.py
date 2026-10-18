"""Convert vendored official Miuix Regular paths to Android vectors; no redraws."""
from pathlib import Path
import re
import material_keyboard_icons
import xml.etree.ElementTree as ET
ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'third_party/miuix_icons'
MAPPING = dict(ai='Edit', translate='Translate', globe='WorldClock', enter='Reply', shift='ExpandLess', caps='TopDownloads', backspace='Clear', more='GridView', hide='ExpandMore', back='Back', forward='ChevronForward', clear='Close', edit='Edit', pin='Pin', unpin='Unpin', delete='Delete', clipboard='Paste', phrases='Notes', voice='Mic', emoji='Community', recent='Recent', drag_handle='More', resize='ScreenCapture', quickedit='Edit', arrow_up='ExpandLess', arrow_down='ExpandMore', arrow_left='Back', arrow_right='Forward', undo='Undo', layout='GridView', floating='ScreenMirroring', floating_enter='ScreenMirroring', floating_exit='GridView', split='VerticalSplit', dictionary='ContactsBook', english_dictionary='Translate', theme='Theme', settings='Settings', input='Tune', keyboard='GridView', check='Ok')
COMMANDS = dict(MoveTo='M', LineTo='L', HorizontalTo='H', VerticalTo='V', QuadTo='Q', CurveTo='C', ReflectiveCurveTo='S', ReflectiveQuadTo='T', Close='Z')
ANDROID='http://schemas.android.com/apk/res/android'
ET.register_namespace('android', ANDROID)
def attrs(**values): return {f'{{{ANDROID}}}{k.replace("translation", "translate")}': str(v) for k,v in values.items()}
def generate():
    for name in sorted(set(MAPPING.values())):
        source = (SOURCE / (name + '.kt')).read_text()
        regular = source.split(f'val MiuixIcons.Regular.{name}: ImageVector',1)[1].split('\nval MiuixIcons.',1)[0]
        width = re.search(r'viewportWidth = ([\d.]+)f',regular)[1]
        height = re.search(r'viewportHeight = ([\d.]+)f',regular)[1]
        vector = ET.Element('vector', attrs(width='24dp', height='24dp', viewportWidth=width, viewportHeight=height))
        group_match = re.search(r'group\((.*?)\)',regular)
        group = ET.SubElement(vector, 'group', attrs(**dict(re.findall(r'(\w+) = (-?[\d.]+)f',group_match[1])))) if group_match else vector
        paths = re.findall(r'pathData = listOf\((.*?)\n\s*\),.*?pathFillType = PathFillType\.(\w+)',regular,re.S)
        if not paths or len(paths) != regular.count("addPath("): raise ValueError(name)
        for nodes, fill_type in paths:
            parts=[]
            for command, args in re.findall(r'PathNode\.(\w+)(?:\(([^)]*)\))?',nodes):
                parts.append(COMMANDS[command] + args.replace('f','').replace(',',' '))
            ET.SubElement(group,'path',attrs(fillColor='#FF000000', fillType='evenOdd' if fill_type=='EvenOdd' else 'nonZero', pathData=' '.join(parts)))
        ET.indent(vector)
        (ROOT / f'app/src/main/res/drawable/ic_miuix_{name.lower()}.xml').write_text('<?xml version="1.0" encoding="utf-8"?>\n<!-- Miuix contributors, Apache-2.0. Generated from official Regular artwork. -->\n'+ET.tostring(vector,encoding='unicode')+'\n')
    # Rotate the library navigation chevron, preserving its stroke weight.
    forward = ET.parse(ROOT / 'app/src/main/res/drawable/ic_miuix_chevronforward.xml').getroot()
    down = ET.Element('vector', forward.attrib)
    group = ET.SubElement(down, 'group', attrs(rotation=90,
        pivotX=float(forward.get(f'{{{ANDROID}}}viewportWidth')) / 2,
        pivotY=float(forward.get(f'{{{ANDROID}}}viewportHeight')) / 2))
    for child in forward: group.append(child)
    ET.indent(down)
    (ROOT / 'app/src/main/res/drawable/ic_miuix_chevrondown.xml').write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n<!-- Official Miuix ChevronForward rotated down. Apache-2.0. -->\n' + ET.tostring(down, encoding='unicode'))
    # Optical weight correction: these Regular paths have thinner strokes after
    # normalizing visible bounds than GridView / the dedicated keyboard symbols.
    # Outline the existing library paths (no replacement glyphs or raster blur).
    for resource, width in [('mic', 32), ('chevrondown', 32)]:
        path = ROOT / f'app/src/main/res/drawable/ic_miuix_{resource}.xml'
        vector = ET.parse(path).getroot()
        for node in vector.iter('path'):
            node.attrib.update(attrs(strokeColor='#FF000000', strokeWidth=width,
                                     strokeLineJoin='round', strokeLineCap='round'))
        ET.indent(vector)
        path.write_text('<?xml version="1.0" encoding="utf-8"?>\n<!-- Miuix Apache-2.0 artwork with optical stroke-weight compensation. -->\n' + ET.tostring(vector, encoding='unicode') + '\n')
    material_keyboard_icons.generate(ROOT)
    lines = ['package io.secure.keyboard.ui','import io.secure.keyboard.R','/** Shared UI icon catalog: Miuix plus dedicated Material keyboard symbols. */','object KeyboardIcons {','    fun resource(kind: String): Int = when(kind) {']
    lines += [f'        "{kind}" -> R.drawable.' + (f'ic_keyboard_{material_keyboard_icons.MAPPING[kind]}' if kind in material_keyboard_icons.MAPPING else f'ic_miuix_{name.lower()}') for kind,name in MAPPING.items()]
    lines = [line.replace('ic_miuix_expandmore', 'ic_miuix_chevrondown') if '"hide"' in line else line for line in lines]
    lines += ['        else -> R.drawable.ic_miuix_gridview','    }','}']
    (ROOT/'app/src/main/java/io/secure/keyboard/ui/KeyboardIcons.kt').write_text('\n'.join(lines)+'\n')
if __name__ == '__main__': generate()
