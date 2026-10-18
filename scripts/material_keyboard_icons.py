"""Semantically specific keyboard icons missing from Miuix's catalog."""
from pathlib import Path
import xml.etree.ElementTree as ET
MAPPING = dict(ai='auto_awesome',arrow_up='arrow_upward', arrow_down='arrow_downward', arrow_left='arrow_back', arrow_right='arrow_forward', drag_handle='drag_handle', quickedit='edit_note', globe='language', enter='keyboard_return', shift='shift', caps='keyboard_capslock', backspace='backspace', clipboard='content_paste', emoji='mood', resize='resize_diagonal', layout='open_in_full', split='split_keyboard', dictionary='menu_book', english_dictionary='dictionary', floating='floating_enter', floating_enter='floating_enter', floating_exit='floating_exit', keyboard='keyboard')
ANDROID='http://schemas.android.com/apk/res/android'
def attrs(**kw): return {f'{{{ANDROID}}}{k}':str(v) for k,v in kw.items()}
def generate(root):
    ET.register_namespace('android',ANDROID)
    source=root/'third_party/material_symbols'
    def add(parent,name,x=0,y=0,size=24):
        svg=ET.parse(source/f'{name}.svg').getroot()
        vx,vy,w,h=map(float,svg.attrib['viewBox'].split())
        position=ET.SubElement(parent,'group',attrs(translateX=x,translateY=y,scaleX=size/w,scaleY=size/h))
        origin=ET.SubElement(position,'group',attrs(translateX=-vx,translateY=-vy))
        paths=svg.findall('{http://www.w3.org/2000/svg}path')
        assert paths, name
        for path in paths: ET.SubElement(origin,'path',attrs(fillColor='#FF000000',pathData=path.attrib['d']))
    for name in sorted(set(MAPPING.values())):
        vector=ET.Element('vector',attrs(width='24dp',height='24dp',viewportWidth=24,viewportHeight=24))
        if name=='resize_diagonal':
            # User-selected D: six circular dots forming a bottom-right resize grip.
            # Custom vector artwork; normalized and centered by ToolIcon.
            for x,y in [(18,6),(12,12),(18,12),(6,18),(12,18),(18,18)]:
                r=1.4
                data=f'M{x-r},{y}a{r},{r} 0 1,0 {2*r},0a{r},{r} 0 1,0 {-2*r},0Z'
                ET.SubElement(vector,'path',attrs(fillColor='#FF000000',pathData=data))
        elif name=='split_keyboard':
            group=ET.SubElement(vector,'group',attrs(rotation=90,pivotX=12,pivotY=12))
            add(group,'splitscreen')
        elif name=='floating_enter':
            add(vector,'keyboard',y=4)
            add(vector,'arrow_upward',x=6,y=-1,size=12)
        elif name=='floating_exit':
            add(vector,'keyboard',y=-4)
            add(vector,'arrow_downward',x=6,y=13,size=12)
        else: add(vector,name)
        ET.indent(vector)
        credit = 'Custom six-dot resize grip, selected option D.' if name == 'resize_diagonal' else 'Official Material Symbols Rounded, Apache-2.0.'
        (root/f'app/src/main/res/drawable/ic_keyboard_{name}.xml').write_text('<?xml version="1.0" encoding="utf-8"?>\n<!-- ' + credit + ' -->\n'+ET.tostring(vector,encoding='unicode')+'\n')
