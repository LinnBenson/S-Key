"""Preview-only paired icon proposals; generated resources never ship in the app."""
from pathlib import Path
import xml.etree.ElementTree as E
root=Path(__file__).resolve().parents[1]
out=root/'app/src/androidTest/res/drawable'
out.mkdir(parents=True,exist_ok=True)
ns='http://schemas.android.com/apk/res/android'
E.register_namespace('android',ns)
def attr(**kw):return {f'{{{ns}}}{k}':str(v) for k,v in kw.items()}
def layer(parent,name,x=0,y=0,size=24):
    svg=E.parse(root/f'third_party/material_symbols/{name}.svg').getroot()
    vx,vy,w,h=map(float,svg.get('viewBox').split())
    g=E.SubElement(parent,'group',attr(translateX=x,translateY=y,scaleX=size/w,scaleY=size/h))
    g=E.SubElement(g,'group',attr(translateX=-vx,translateY=-vy))
    for p in svg.findall('{http://www.w3.org/2000/svg}path'):
        E.SubElement(g,'path',attr(fillColor='#FF17283E',pathData=p.get('d')))
for name in ['b','c','d']:
    for dock in [False,True]:
        v=E.Element('vector',attr(width='24dp',height='24dp',viewportWidth=24,viewportHeight=24))
        if name=='b':layer(v,'dock_to_bottom' if dock else 'picture_in_picture_alt')
        if name=='c':
            layer(v,'keyboard',y=-4 if dock else 4)
            layer(v,'arrow_downward' if dock else 'arrow_upward',x=6,y=13 if dock else -1,size=12)
        if name=='d':
            layer(v,'keyboard',x=0,y=5,size=22)
            layer(v,'close_fullscreen' if dock else 'open_in_full',x=13,y=0,size=11)
        E.indent(v)
        (out/f'floating_option_{name}_{"dock" if dock else "float"}.xml').write_text(E.tostring(v,encoding='unicode'))
