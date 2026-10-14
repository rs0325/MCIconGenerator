"""独立World QAのanimationパック形式と固定glint受入を定める。"""
import hashlib
import json
import zipfile

VERSIONS=('1.13','1.14.3','1.14.4','1.16.5','1.19.4','1.20.6','1.21.4','1.21.5','1.21.11','26.1.2','26.2','26.3')
GLINT_IDS={'minecraft:enchanted_book','minecraft:enchanted_golden_apple'}


def frame_pack(client,target,version):
    records=[]
    with zipfile.ZipFile(client) as source,zipfile.ZipFile(target,'w',compression=zipfile.ZIP_DEFLATED) as pack:
        if 'version.json' not in source.namelist():metadata={'pack_format':json.loads(source.read('pack.mcmeta'))['pack']['pack_format']}
        else:
            pack_version=json.loads(source.read('version.json'))['pack_version']
            if isinstance(pack_version,int):metadata={'pack_format':pack_version}
            elif 'resource' in pack_version:metadata={'pack_format':pack_version['resource']}
            else:
                current=[pack_version['resource_major'],pack_version['resource_minor']]
                metadata={'min_format':current,'max_format':current}
        metadata['description']='MCIcon QA: 元PNGを保持した先頭animation frame'
        pack.writestr('pack.mcmeta',json.dumps({'pack':metadata}))
        for name in sorted(source.namelist()):
            if not name.startswith(('assets/minecraft/textures/item/','assets/minecraft/textures/block/')) or not name.endswith('.png.mcmeta'):continue
            original=source.read(name);value=json.loads(original)
            if 'animation' not in value:continue
            frames=value['animation'].get('frames',[0]);first=frames[0] if frames else 0
            first=first['index'] if isinstance(first,dict) else first
            indices=[first,first] if version in ('26.2','26.3') else [first]
            value['animation']['frames']=indices;value['animation']['interpolate']=False
            texture=name.removesuffix('.mcmeta');png=source.read(texture)
            pack.writestr(texture,png);pack.writestr(name,json.dumps(value))
            records.append({'path':texture,'pngSha256':hashlib.sha256(png).hexdigest(),'metadataSha256':hashlib.sha256(original).hexdigest(),'initialFrame':first,'frameIndices':indices,'frameTime':value['animation'].get('frametime',1)})
    return records


def frame_pack_passed(version,resources):
    return bool(resources) and all(
        value['frameIndices']==([value['initialFrame'],value['initialFrame']] if version in ('26.2','26.3') else [value['initialFrame']])
        and type(value['frameTime']) is int and value['frameTime']>0 for value in resources)


def controlled_capture_passed(version,ids,capture,resources,frame_zero,fixed_glint):
    if (frame_zero or fixed_glint) and [entry.get('id') for entry in capture.get('captures',[])]!=['empty',*ids]:return False
    if frame_zero:
        actual=capture.get('frameZeroResourceSources',{})
        if not resources or set(actual)!=set(resources) or not all(value in ('mcicon-frame-zero.zip','file/mcicon-frame-zero.zip') for value in actual.values()):return False
    if fixed_glint:
        expected=2 if version in ('1.13','1.14.3','1.14.4') else 1
        if capture.get('glintClockCallSites')!=expected or capture.get('fixedGlintMillis')!=1000:return False
        selected={entry['id']:entry for entry in capture.get('captures',[])}
        for item in set(ids)&GLINT_IDS:
            value=selected.get(item,{})
            if value.get('hasFoil') is not True or value.get('glintClockCalls',0)<=0 or value.get('fixedGlintMillis')!=1000:return False
    return True


def gpu_lightmap_matches(capture,baseline):
    rgba=capture.get('fullBrightGpuRgba')
    argb=capture.get('fullBrightArgb')
    return (isinstance(rgba,list) and len(rgba)==4
            and all(type(channel) is int and 0<=channel<=255 for channel in rgba)
            and type(argb) is int and rgba==[(argb>>shift)&255 for shift in (16,8,0,24)]
            and rgba[:3]==baseline
            and capture.get('fullBrightGpuTexel')==[15,15]
            and rgba==capture.get('fullBrightCpuRgba')
            and capture.get('lightmapTextureId',0)>0
            and capture.get('guiLightmapShaderTexture')==capture['lightmapTextureId']
            and capture.get('appliedSampler2TextureId')==capture['lightmapTextureId']
            and capture.get('appliedSampler2Observations',0)>0)
