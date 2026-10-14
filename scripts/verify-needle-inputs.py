#!/usr/bin/env python3
"""固定51版の針oracleへ渡す元bytecode・登録・照明・sampling根拠を検査する。"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import shutil
import zipfile
from qa.needle_world import validate as validate_world

repo = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('needle_bytecode', repo/'scripts/needle-bytecode.py')
bytecode = importlib.util.module_from_spec(spec); spec.loader.exec_module(bytecode)
releases = json.loads((repo/'adapters/releases.json').read_text())['versions']
p = argparse.ArgumentParser()
p.add_argument('--cache', type=Path, default=repo/'.local/cache')
p.add_argument('--out', type=Path, required=True)
p.add_argument('--versions', nargs='+', choices=[v['id'] for v in releases])
p.add_argument('--javap', default=shutil.which('javap'))
p.add_argument('--world-record',action='append',default=[],metavar='VERSION=PATH',help='private World証拠の明示immutable snapshot')
a = p.parse_args()
if not a.javap: p.error('対象JDKのjavapが必要です')
world_records={}
for value in a.world_record:
    version,path=value.split('=',1)
    if version not in {v['id'] for v in releases} or version in world_records:p.error('World記録の版が未知または重複しています')
    world_records[version]=Path(path).resolve()
out = a.out.resolve(); out.mkdir(parents=True, exist_ok=False)
contracts_path = repo/'qa/needle-source-contracts.json'
world_path = repo/'qa/needle-world-lighting.json'
contracts = {v['version']:v for v in json.loads(contracts_path.read_text())['versions']}
world = {v['version']:v for v in json.loads(world_path.read_text())['versions']}
sha = lambda data: hashlib.sha256(data).hexdigest()
old_properties = {'1.13','1.13.1','1.13.2','1.14','1.14.1','1.14.2','1.14.3','1.14.4','1.15','1.15.1','1.15.2'}
results = []
for release in releases:
    version = release['id']
    if a.versions and version not in a.versions: continue
    result = {'schemaVersion':1,'version':version,'passed':False}
    try:
        folder = a.cache.resolve()/'versions'/version
        client, metadata, mapping = folder/'client.jar', folder/'version.json', folder/'mappings.txt'
        source = client.read_bytes(); meta = metadata.read_bytes(); downloads=json.loads(meta)['downloads']
        if hashlib.sha1(meta).hexdigest()!=release['metadataSha1'] or hashlib.sha1(source).hexdigest()!=downloads['client']['sha1']:
            raise ValueError('固定metadata/clientの公式hashが一致しません')
        contract=contracts[version]
        if sha(source)!=contract['sourceSha256'] or (sha(mapping.read_bytes()) if mapping.exists() else None)!=contract['mappingSha256']:
            raise ValueError('調査済みclient/mapping契約が一致しません')
        if 'client_mappings' in downloads and hashlib.sha1(mapping.read_bytes()).hexdigest()!=downloads['client_mappings']['sha1']:
            raise ValueError('公式mappingのhashが一致しません')
        legacy_locks=json.loads((repo/'adapters/legacy-mappings.json').read_text())
        mapping_inputs=[]
        if version in legacy_locks:
            for kind in ('feather','intermediary'):
                path=a.cache.resolve()/'mappings'/version/(kind+'.jar');data=path.read_bytes()
                if hashlib.sha1(data).hexdigest()!=legacy_locks[version][kind]:raise ValueError('旧mappingの元JAR hashが一致しません: '+kind)
                mapping_inputs.append({'path':'mappings/'+version+'/'+kind+'.jar','sha256':sha(data)})
        inspector=bytecode.Bytecode(client,mapping,a.javap)
        modern=version.startswith('26.') or version.startswith('1.21.') and int(version[5:])>=4
        suffixes=['NeedleDirectionHelper'] if modern else ['ClockItem$1','CompassItem$1'] if version in old_properties else ['ItemProperties$1','CompassItemPropertyFunction' if any(n.endswith('.CompassItemPropertyFunction') for n in inspector.names) else 'ItemProperties$2']
        branches=[inspector.null_direction(suffix) for suffix in suffixes]
        verified_methods=[]
        for method in contract['methods']:
            suffix,name=method['group'].split('@',1)[0].split('.',1)
            owner=inspector.name(suffix);inspector.read(owner)
            fingerprints={sha('\n'.join(inspector.normalize(owner,m['code'])).encode()) for m in inspector.definitions[owner] if m['name']==name}
            if method['sha256'] not in fingerprints:raise ValueError('調査済みbytecodeメソッドが一致しません: '+method['group'])
            verified_methods.append(method)

        registry=inspector.name('Items'); registry_text,_=inspector.read(registry)
        text=registry_text; ids_owner=None
        if version in ('26.2','26.3'):
            ids_owner=inspector.name('ItemIds');text,_=inspector.read(ids_owner)
        items=[]; registration=[]
        for item in ('clock','compass','recovery_compass'):
            matches=list(re.finditer(r'// String '+item+r'\n',text))
            if not matches:
                if item!='recovery_compass':raise ValueError('針IDが実Itemsにありません: '+item)
                continue
            if len(matches)!=1:raise ValueError('針のItems定数が一意でありません: '+item)
            segment=text[matches[0].start():]
            assignment=re.search(r'putstatic\s+#[0-9]+\s+// Field ([^\n]+)',segment)
            if assignment is None or len(segment[:assignment.end()].splitlines())>45:
                raise ValueError('Itemsの針登録/取得結果を確認できません: '+item)
            key_field=assignment[1]
            if ids_owner:
                reference='Field '+ids_owner.replace('.','/')+'.'+key_field
                start=registry_text.find(reference)
                if start<0:raise ValueError('ItemIdsとItemsの登録が結び付きません')
                segment=registry_text[start:]
                assignment=re.search(r'putstatic\s+#[0-9]+\s+// Field ([^\n]+)',segment)
                if assignment is None or 'Method registerItem:' not in segment[:assignment.end()]:raise ValueError('ItemIdsを実registryへ登録しません')
            items.append(item);registration.append({'id':'minecraft:'+item,'field':assignment[1],'keyField':key_field if ids_owner else None,'code':segment[:assignment.end()]})
        binding=[]
        if modern:
            helper=inspector.name('NeedleDirectionHelper')
            for suffix in ('Time','CompassAngleState'):
                owner=inspector.name(suffix);text,_=inspector.read(owner)
                if 'extends '+helper not in text:raise ValueError('針propertyがnull helperを継承しません: '+suffix)
                binding.append({'class':owner,'nullHelper':helper})
            compass=inspector.name('CompassAngle');compass_text,_=inspector.read(compass)
            state=inspector.name('CompassAngleState')
            if not re.search(r'Method '+re.escape(state.replace('.','/'))+r'\.[^:]+:\([^)]*\)F',compass_text):raise ValueError('CompassAngleがnull helperへ渡しません')
            property_registry=inspector.name('RangeSelectItemModelProperties');properties,_=inspector.read(property_registry)
            for key,suffix in [('time','Time'),('compass','CompassAngle')]:
                owner=inspector.name(suffix)
                match=re.search(r'// String '+key+r'\n',properties)
                if match is None or not re.search(r'Field '+re.escape(owner.replace('.','/'))+r'\.',properties[match.end():match.end()+600]):raise ValueError('definition propertyの型登録が一致しません: '+key)
                binding.append({'property':'minecraft:'+key,'class':owner,'registryClass':property_registry})
            if not version.startswith('26.') or version=='26.1':
                dimension=inspector.name('ContextDimension');inspector.read(dimension)
                candidates=[m['code'] for m in inspector.definitions[dimension] if m['name']=='get' and m['descriptor'].endswith(')'+inspector.descriptor('net.minecraft.resources.ResourceKey'))]
                if len(candidates)!=1 or inspector.evaluate(dimension,candidates[0],{0:'property',1:'default-stack',2:None,3:None,4:0,5:'gui'}) is not None:raise ValueError('context_dimensionのnull fallbackが一致しません')
                binding.append({'property':'minecraft:context_dimension','nullResult':True,'class':dimension})
        else:
            for index,item in enumerate(('clock','compass')):
                owner=inspector.name(('ClockItem' if item=='clock' else 'CompassItem') if version in old_properties else 'ItemProperties')
                text,_=inspector.read(owner);property_name='time' if item=='clock' else 'angle'
                matches=list(re.finditer(r'// String '+property_name+r'\n',text))
                if not matches:raise ValueError('針propertyの登録がありません: '+property_name)
                helper=branches[index]['class']
                segments=[text[m.start():].split('\n\n',1)[0][:2200] for m in matches]
                selected=[s for s in segments if re.search(r'(class |Method )'+re.escape(helper)+r'(?:\.|\n)',s)]
                if not selected:raise ValueError('登録propertyとnull helperが結び付きません: '+property_name)
                binding.append({'class':owner,'property':property_name,'nullHelper':helper,'code':selected[0]})
        observation=world[version]
        validate_world(observation,world_records.get(version,repo/observation['record']),sha(source))
        raw=observation['rawGpuRgba']
        if raw not in ([252,252,252,255],[255,255,255,255]):raise ValueError('未知の生GPU照明値です')
        legacy=version.startswith(('1.13','1.14'))
        if legacy and observation.get('guiLightmapTextureEnabled') is not False:raise ValueError('旧GUIでlightmap無効の採取根拠がありません')
        light=255 if legacy else raw[0]
        newest=version.startswith('26.') or version=='1.21.11'
        sprite=inspector.name('TextureAtlasSprite');sprite_text,_=inspector.read(sprite)
        edge_inset=0.01 if 'double 0.009999999776482582d' in sprite_text else 0
        face_shrink=0 if version.startswith('1.13') or newest else 4/128
        sprite_shrink=2*edge_inset/16
        shrink=sprite_shrink+face_shrink*(1-sprite_shrink)
        with zipfile.ZipFile(client) as jar:
            for entry in contract['inputs']+contract['shaderInputs']:
                if sha(jar.read(entry['path']))!=entry['sha256']:raise ValueError('調査済み元bytecodeが変わりました: '+entry['path'])
            for owner in inspector.cache:
                path=owner.replace('.','/')+'.class'
                if not any(e['path']==path for e in contract['inputs']):raise ValueError('未調査のnull/登録bytecodeです: '+path)
            model_inputs=[]
            if not modern:
                base_path='assets/minecraft/models/item/clock.json';base_model=json.loads(jar.read(base_path))
                referenced={entry['model'] for entry in base_model['overrides']}
                textures=set()
                for model_id in sorted(referenced):
                    model_path='assets/minecraft/models/'+model_id.removeprefix('minecraft:')+'.json'
                    data=jar.read(model_path);model=json.loads(data)
                    if model['parent'] not in ('item/generated','minecraft:item/generated') or set(model['textures'])!={'layer0'}:raise ValueError('clock全frameが単一generatedではありません')
                    textures.add(model['textures']['layer0'].removeprefix('minecraft:'))
                    model_inputs.append({'path':model_path,'sha256':sha(data)})
                if textures!={f'item/clock_{frame:02d}' for frame in range(64)}:raise ValueError('旧clockの依存modelが64textureを覆いません')
            frame_inputs=[]
            for frame in range(64):
                path=f'assets/minecraft/textures/item/clock_{frame:02d}.png';data=jar.read(path)
                if data[:8]!=b'\x89PNG\r\n\x1a\n' or data[16:24]!=b'\0\0\0\x10\0\0\0\x10':raise ValueError('atlas下限のclock frameが16pxではありません')
                frame_inputs.append({'path':path,'sha256':sha(data)})
            atlas_path='assets/minecraft/atlases/'+('items' if newest else 'blocks')+'.json'
            atlas_inputs=[]
            if atlas_path in jar.namelist():
                data=jar.read(atlas_path);atlas=json.loads(data)
                if not any(s.get('type') in ('directory','minecraft:directory') and s.get('source')=='item' and s.get('prefix')=='item/' for s in atlas['sources']):raise ValueError('同atlasにitem全textureを収容しません')
                atlas_inputs.append({'path':atlas_path,'sha256':sha(data)})
            else:
                # atlas JSON以前は全overrideモデルを依存として集める元ModelBakeryの調査済みbytecodeに限定する。
                if not any('ModelBakery' in e['group'] for e in contract['methods']):raise ValueError('旧atlasのmodel依存経路が未調査です')
            if items!=['clock','compass']+(['recovery_compass'] if 'assets/minecraft/textures/item/recovery_compass_16.png' in jar.namelist() else []):raise ValueError('実Itemsと候補資源の母集団が一致しません')
        deferred=version.startswith('26.') or version.startswith('1.21.') and int(version[5:])>=6
        displacement=7.5*shrink
        two_pass=displacement
        if displacement>=0.5 or two_pass>=0.5:raise ValueError('16pxの元texel中心が不変ではありません')
        result.update(sourceSha256=sha(source),metadataSha256=sha(meta),mappingInputs=mapping_inputs,mappingSha256=contract['mappingSha256'],sourceContractSha256=sha(contracts_path.read_bytes()),bytecodeInputs=contract['inputs'],bytecodeMethods=verified_methods,shaderInputs=contract['shaderInputs'],items=items,registration=registration,propertyBinding=binding,nullBranches=branches,itemDefinitions=modern,frontLighting=not legacy and version not in ('1.15','1.15.1'),lightMultiplier255=light,worldLighting=observation,guiPosition=[80,121] if version in ['1.20','1.20.1','1.20.2','1.20.3','1.20.4','1.20.5','1.20.6','1.21','1.21.1','1.21.2','1.21.3'] else [0,0],sampling={'sourceTextureSize':16,'filter':'nearest','clockFrames':64,'atlasMaximumSideLowerBound':128,'atlasInputs':atlas_inputs,'modelInputs':model_inputs,'frameInputs':frame_inputs,'faceUvShrinkRatioUpperBound':face_shrink,'spriteEdgeInsetTexels':edge_inset,'spriteUvShrinkRatio':sprite_shrink,'uvShrinkRatioUpperBound':shrink,'singlePassDisplacementUpperBound':displacement,'deferred':deferred,'guiScale':1 if deferred else None,'guiAtlasSlotPixels':16 if deferred else None,'runtimeObservationRequired':deferred,'twoPassDisplacementUpperBound':two_pass,'condition':'7.5s<0.5。deferredは生成前のscale1・slot16を元GUI入口で検査する','scope':'16pxだけ。64px以上へ一般化しない。'},gameClassesLoaded=False,gpuUsed=False,passed=True)
    except Exception as error:
        result['failure']=str(error)
    results.append(result);(out/(version+'.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(version,'PASS' if result['passed'] else 'FAIL',result.get('failure',''),flush=True)
report={'versions':results,'gameWorkersStarted':0,'passed':all(v['passed'] for v in results),'sourcesSha256':{str(f.relative_to(repo)):sha(f.read_bytes()) for f in [Path(__file__).resolve(),repo/'scripts/needle-bytecode.py',repo/'scripts/qa/needle_world.py',contracts_path,world_path]}}
(out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
raise SystemExit(not report['passed'])
