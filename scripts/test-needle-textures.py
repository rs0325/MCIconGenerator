#!/usr/bin/env python3
"""針oracleの選択・元モデル・画素・出力契約の負対照。ゲーム・GPUは使わない。"""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import tempfile
import zipfile
import zlib

repo = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser()
p.add_argument('--out', type=Path, required=True)
a = p.parse_args()
out = a.out.resolve(); out.mkdir(parents=True, exist_ok=False)
agent = repo/'cli/build/install/mcicon/worker/worker-agent.jar'
dep = out/'qa-dependencies.jar'
with zipfile.ZipFile(agent) as source, zipfile.ZipFile(dep, 'w') as target:
    for name in source.namelist():
        if name.startswith('com/google/gson/') and not name.endswith('/'):
            target.writestr(name, source.read(name))
classes = out/'classes'; classes.mkdir()
subprocess.run(['javac', '--release', '8', '-cp', str(dep), '-d', str(classes), str(repo/'scripts/qa/NeedleTextureProbe.java')], check=True)
command = ['java', '-Djava.awt.headless=true', '-cp', str(classes)+':'+str(dep), 'io.github.rs0325.mcicon.qa.NeedleTextureProbe']
sha = lambda data: hashlib.sha256(data).hexdigest()

def png(pixels):
    def chunk(kind, data):
        return struct.pack('>I', len(data))+kind+data+struct.pack('>I', zlib.crc32(kind+data))
    raw = b''.join(b'\0'+bytes(sum(pixels[y*16:(y+1)*16], [])) for y in range(16))
    return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR', struct.pack('>IIBBBBB',16,16,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(raw))+chunk(b'IEND',b'')

pixels = [[40+x*5, 30+y*7, 20+(x+y)*3, 255] if x+y>2 else [0,0,0,0] for y in range(16) for x in range(16)]
texture = png(pixels)
model = {'parent':'minecraft:item/generated','textures':{'layer0':'minecraft:item/clock_00'},'overrides':[{'predicate':{'time':0},'model':'minecraft:item/clock'},{'predicate':{'time':0},'model':'minecraft:item/clock_00'}]}
resources = {'fixture.class':b'null-fixture','assets/minecraft/models/item/generated.json':json.dumps({'parent':'builtin/generated','gui_light':'front','display':{}}).encode(), 'assets/minecraft/models/item/clock.json':json.dumps(model).encode(), 'assets/minecraft/models/item/clock_00.json':json.dumps({'parent':'minecraft:item/generated','textures':{'layer0':'minecraft:item/clock_00'}}).encode(), 'assets/minecraft/models/item/clock_01.json':json.dumps({'parent':'minecraft:item/generated','textures':{'layer0':'minecraft:item/clock_01'}}).encode(), 'assets/minecraft/textures/item/clock_00.png':texture, 'assets/minecraft/textures/item/clock_01.png':png([[200,10,20,255]]*256)}
proof = {'passed':True,'version':'fixture','items':['clock'],'schemaVersion':1,'bytecodeInputs':[{'path':'fixture.class','sha256':sha(b'null-fixture')}],'nullBranches':[{'direction':0,'randomReached':False}],'worldLighting':{'passed':True},'sampling':{'sourceTextureSize':16,'filter':'nearest','singlePassDisplacementUpperBound':0,'twoPassDisplacementUpperBound':0},'itemDefinitions':False,'frontLighting':True,'lightMultiplier255':255}
records = []

def case(name, source_change=None, output_change=None, manifest_change=None, prepared_change=None, expected=True, prepare_expected=True, modern=False, item='clock'):
    folder=out/name;folder.mkdir();inputs=copy.deepcopy(resources);local_proof=copy.deepcopy(proof);local_proof['items']=[item]
    if modern:
        local_proof['itemDefinitions']=True
        inputs['assets/minecraft/items/'+item+'.json']=json.dumps({'model':{'type':'minecraft:range_dispatch','property':'minecraft:time' if item=='clock' else 'minecraft:compass',('source' if item=='clock' else 'target'):'random' if item=='clock' else 'none','scale':64 if item=='clock' else 32,'entries':[{'threshold':0,'model':{'type':'minecraft:model','model':'minecraft:item/clock_00'}},{'threshold':0.5,'model':{'type':'minecraft:model','model':'minecraft:item/clock_01'}}]}}).encode()
    if source_change:source_change(inputs)
    client=folder/'client.jar'
    with zipfile.ZipFile(client,'w') as jar:
        for path,data in inputs.items():jar.writestr(path,data)
    local_proof['sourceSha256']=sha(client.read_bytes());local_proof['worldLighting']['sourceSha256']=local_proof['sourceSha256'];proof_path=folder/'proof.json';proof_path.write_text(json.dumps(local_proof))
    prepare=subprocess.run(command+[str(client),str(proof_path),'--prepare'],capture_output=True,text=True)
    (folder/'prepare.log').write_text(prepare.stderr)
    if bool(prepare.returncode==0)!=prepare_expected:raise AssertionError(name+'の事前検査結果が不正です')
    record={'name':name,'prepareExitCode':prepare.returncode,'expectedPrepareSuccess':prepare_expected}
    if prepare_expected:
        prepared=json.loads(prepare.stdout);prepared_path=folder/'prepared.json'
        if prepared_change:prepared_change(prepared)
        prepared_path.write_text(json.dumps(prepared))
        export=folder/'export';export.mkdir();data=pixels.copy()
        data=copy.deepcopy(data)
        if output_change:output_change(data)
        image=png(data);image_path='assets/minecraft/textures/item/'+item+'.png';target=export/image_path;target.parent.mkdir(parents=True);target.write_bytes(image)
        manifest={'sourceSha256':local_proof['sourceSha256'],'entries':[{'id':'minecraft:'+item,'status':'success','path':image_path,'sha256':sha(image)}]}
        if manifest_change:manifest_change(manifest)
        (export/'manifest.json').write_text(json.dumps(manifest))
        compare=subprocess.run(command+[str(client),str(proof_path),str(export),str(prepared_path)],capture_output=True,text=True)
        (folder/'compare.log').write_text(compare.stderr)
        record.update(compareExitCode=compare.returncode,expectedCompareSuccess=expected)
        if bool(compare.returncode==0)!=expected:raise AssertionError(name+'の比較結果が不正です')
    records.append(record);print(name,'PASS',flush=True)

case('baseline')
case('rgb_boundary',output_change=lambda p:p[255].__setitem__(0,p[255][0]+1))
case('rgb_over_tolerance',output_change=lambda p:p[255].__setitem__(0,p[255][0]+2),expected=False)
case('alpha',output_change=lambda p:p[255].__setitem__(3,254),expected=False)
case('wrong_frame',output_change=lambda p:p.__setitem__(255,[200,10,20,255]),expected=False)
case('png_hash',manifest_change=lambda m:m['entries'][0].__setitem__('sha256','0'*64),expected=False)
case('png_path',manifest_change=lambda m:m['entries'][0].__setitem__('path','../clock.png'),expected=False)
case('source_hash',manifest_change=lambda m:m.__setitem__('sourceSha256','0'*64),expected=False)
case('prepared_model',prepared_change=lambda m:m['entries'][0].__setitem__('model','minecraft:item/clock_01'),expected=False)
case('prepared_provenance',prepared_change=lambda m:m.__setitem__('preparedBeforePng',False),expected=False)
case('prepared_selection',prepared_change=lambda m:m['entries'][0]['selection'][0].__setitem__('index',0),expected=False)

def change_json(inputs,path,change):
    data=json.loads(inputs[path]);change(data);inputs[path]=json.dumps(data).encode()
selected='assets/minecraft/models/item/clock_00.json'
case('parent',source_change=lambda r:change_json(r,selected,lambda m:m.__setitem__('parent','minecraft:item/handheld')),prepare_expected=False)
case('gui_transform',source_change=lambda r:change_json(r,selected,lambda m:m.__setitem__('display',{'gui':{'scale':[2,2,2]}})),prepare_expected=False)
case('layers',source_change=lambda r:change_json(r,selected,lambda m:m['textures'].__setitem__('layer1','minecraft:item/clock_01')),prepare_expected=False)
case('animation',source_change=lambda r:r.__setitem__('assets/minecraft/textures/item/clock_00.png.mcmeta',b'{"animation":{}}'),prepare_expected=False)
case('modern_baseline',modern=True)
case('modern_tint',modern=True,source_change=lambda r:change_json(r,'assets/minecraft/items/clock.json',lambda d:d['model']['entries'][0]['model'].__setitem__('tints',[{'type':'minecraft:constant','value':0}])),prepare_expected=False)
case('range_threshold',modern=True,source_change=lambda r:change_json(r,'assets/minecraft/items/clock.json',lambda d:d['model']['entries'][0].__setitem__('threshold',0.75)),prepare_expected=False)
case('override_order',source_change=lambda r:change_json(r,'assets/minecraft/models/item/clock.json',lambda d:d['overrides'][-1].__setitem__('model','minecraft:item/clock_01')),expected=False)
case('range_property',modern=True,source_change=lambda r:change_json(r,'assets/minecraft/items/clock.json',lambda d:d['model'].__setitem__('property','minecraft:unknown')),prepare_expected=False)
def dimension(inputs):
    path='assets/minecraft/items/clock.json';data=json.loads(inputs[path]);fallback=data['model']
    data['model']={'type':'minecraft:select','property':'minecraft:context_dimension','cases':[{'when':'minecraft:overworld','model':{'type':'minecraft:model','model':'minecraft:item/clock_01'}}],'fallback':fallback}
    inputs[path]=json.dumps(data).encode()
case('null_dimension',modern=True,source_change=dimension)

def component(inputs):
    path='assets/minecraft/items/compass.json';data=json.loads(inputs[path]);on_false=data['model']
    data['model']={'type':'minecraft:condition','property':'minecraft:has_component','component':'minecraft:lodestone_tracker','on_true':{'type':'minecraft:model','model':'minecraft:item/clock_01'},'on_false':on_false}
    inputs[path]=json.dumps(data).encode()
case('component_absent',modern=True,item='compass',source_change=component)

# null経路で未対応呼出しへ進む場合は、乱数や推測した0へ置き換えず失敗する。
import importlib.util
spec=importlib.util.spec_from_file_location('needle_bytecode',repo/'scripts/needle-bytecode.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
for name,code,success in [
    ('null_return',[(0,'aload_3','',''),(1,'ifnonnull','5',''),(4,'fconst_0','',''),(5,'freturn','','')],True),
    ('null_nonzero',[(0,'iconst_1','',''),(1,'freturn','','')],False),
    ('null_random',[(0,'invokestatic','#1','Method java/lang/Math.random:()D'),(3,'fconst_0','',''),(4,'freturn','','')],False),
    ('null_unknown_call',[(0,'aload_1','',''),(1,'invokevirtual','#1','Method Fixture.unknown:(I)F'),(4,'freturn','','')],False),
]:
    checker=module.Bytecode.__new__(module.Bytecode);checker.trace=[];checker.name=lambda suffix:'Fixture'
    checker.read=lambda owner:('',{'(LStack;LWorld;LEntity;I)F':[code]})
    try:checker.null_direction('Fixture');actual=True
    except (ValueError,IndexError):actual=False
    if actual!=success:raise AssertionError(name+'のnull判定が不正です')
    records.append({'name':name,'expectedSuccess':success,'passed':True});print(name,'PASS',flush=True)
(out/'report.json').write_text(json.dumps({'gameWorkersStarted':0,'productionClassesOnClasspath':False,'cases':records,'passed':True},indent=2)+'\n')
