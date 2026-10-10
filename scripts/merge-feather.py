#!/usr/bin/env python3
"""Convert pinned Ornithe Tiny mappings to the spike's source-name resolver format."""
import argparse
import re
import zipfile
from pathlib import Path


def tiny(path):
    lines=zipfile.ZipFile(path).read('mappings/mappings.tiny').decode().splitlines()
    classes={}
    owner=None
    for line in lines[1:]:
        parts=line.split('\t')
        if parts[0]=='c':
            owner=parts[1]
            classes[owner]={'name':parts[2],'members':{}}
        elif len(parts)>4 and parts[0]=='' and parts[1] in ('m','f'):
            classes[owner]['members'][(parts[1],parts[2],parts[3])]=parts[4]
    return classes


def merge(base):
    official=tiny(base/'intermediary.jar')
    named=tiny(base/'feather.jar')
    named_classes={c:named.get(v['name'],{'name':v['name']})['name'] for c,v in official.items()}
    intermediary_classes={c:v['name'] for c,v in official.items()}
    primitive={'V':'void','Z':'boolean','B':'byte','S':'short','C':'char','I':'int','J':'long','F':'float','D':'double'}
    def types(descriptor):
        result=[]
        for value in re.findall(r'\[*L[^;]+;|\[*[VZBSCIJFD]',descriptor):
            dims=len(value)-len(value.lstrip('['));raw=value[dims:]
            name=named_classes.get(raw[1:-1],raw[1:-1]).replace('/','.') if raw.startswith('L') else primitive[raw]
            result.append(name+'[]'*dims)
        return result
    output=[]
    for original,clazz in official.items():
        intermediate=clazz['name']; feather=named.get(intermediate,{'members':{}})
        output.append(named_classes[original].replace('/','.')+' -> '+original.replace('/','.')+':')
        for (kind,descriptor,actual),middle in clazz['members'].items():
            mapped_descriptor=re.sub(r'L([^;]+);',lambda m:'L'+intermediary_classes.get(m.group(1),m.group(1))+';',descriptor)
            name=feather['members'].get((kind,mapped_descriptor,middle),middle)
            if kind=='f':
                output.append('    '+types(descriptor)[0]+' '+name+' -> '+actual)
            else:
                parameters,returns=descriptor.split(')')
                output.append('    '+types(returns)[0]+' '+name+'('+','.join(types(parameters[1:]))+') -> '+actual)
    (base/'mappings.txt').write_text('\n'.join(output)+'\n')


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('version');args=p.parse_args()
    merge(Path(__file__).resolve().parents[1]/'.local/probe'/args.version)
