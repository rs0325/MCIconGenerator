"""保存World QAの元JSONとGPU値の参照位置を照合する。"""
import hashlib
import json


def pointer(document,path):
    value=document
    for key in path.split('/')[1:]:
        key=key.replace('~1','/').replace('~0','~')
        value=value[int(key)] if isinstance(value,list) else value[key]
    return value


def validate(observation,record,source):
    if observation['passed'] is not True or observation['sourceSha256']!=source:raise ValueError('同じclientのWorld根拠ではありません')
    data=record.read_bytes()
    if hashlib.sha256(data).hexdigest()!=observation['recordSha256']:raise ValueError('元World QA record hashが一致しません')
    document=json.loads(data);path=observation['pointer'];value=pointer(document,path)
    if observation['measurement']=='fullBrightGpuRgba':
        metadata=pointer(document,path.rsplit('/entries/',1)[0])
        raw=value['fullBrightGpuRgba']
        if value.get('lightingCompatible') is not True:raise ValueError('元Worldの照明適用が合格していません')
        if observation.get('guiLightmapTextureEnabled')!=value.get('guiLightmapTextureEnabled') or observation.get('guiLightmapSource')!=value.get('guiLightmapSource'):raise ValueError('元GUIの照明分岐が一致しません')
        if 'appliedSampler2TextureId' in value and (value['appliedSampler2TextureId']!=value['lightmapTextureId'] or value['appliedSampler2Observations']<=0):raise ValueError('元GUIの適用Sampler2が一致しません')
    elif observation['measurement']=='gpu-target fullBrightArgb=-197380':
        metadata=document
        if metadata['capture']['lightmapProbe']!='gpu-target':raise ValueError('CPU値をGPU採取として扱いません')
        raw=[(value>>shift)&255 for shift in (16,8,0,24)]
    else:raise ValueError('未知のWorld GPU採取方式です')
    if metadata.get('exitCode')!=0 or metadata.get('officialInputsModified') is not False:raise ValueError('元World QAの実行/入力条件が合格していません')
    if metadata['passed'] is not True or metadata['version']!=observation['version'] or metadata['sourceSha256']!=source or metadata['imageId']!=observation['imageId']:raise ValueError('元Worldのsource/version/image条件が一致しません')
    if raw!=observation['rawGpuRgba'] or raw not in ([252,252,252,255],[255,255,255,255]):raise ValueError('元WorldのGPU値参照が一致しません')
    return observation


def validate_image(observation,image):
    if observation['imageId']!=image:raise ValueError('期待照明と実Docker imageが一致しません')
