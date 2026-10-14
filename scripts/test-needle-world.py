#!/usr/bin/env python3
"""照明252が残っていても、元record・GPU位置・source・imageの破損を拒否する。"""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
from qa.needle_world import validate,validate_image

with tempfile.TemporaryDirectory() as temporary:
    root=Path(temporary);record=root/'world.json'
    entry={'fullBrightGpuRgba':[252,252,252,255],'lightingCompatible':True,'appliedSampler2TextureId':5,'lightmapTextureId':5,'appliedSampler2Observations':30}
    document={'version':'fixture','sourceSha256':'source','imageId':'sha256:image','passed':True,'exitCode':0,'officialInputsModified':False,'entries':[entry]}
    observation={'version':'fixture','sourceSha256':'source','imageId':'sha256:image','passed':True,'record':'world.json','recordSha256':'','pointer':'/entries/0','rawGpuRgba':[252,252,252,255],'measurement':'fullBrightGpuRgba'}
    def write(value):
        record.write_text(json.dumps(value));return hashlib.sha256(record.read_bytes()).hexdigest()
    observation['recordSha256']=write(document)
    validate(observation,record,'source');print('baseline','PASS')
    for fault in ['record','record_hash','pointer','cpu_pointer','source','record_source','image','record_image','gpu','sampler','passed','exit','input_modified','cpu_probe']:
        changed=copy.deepcopy(observation);data=copy.deepcopy(document);path=record
        if fault=='record':path=root/'missing.json'
        if fault=='record_hash':changed['recordSha256']='wrong'
        if fault=='pointer':changed['pointer']='/entries/5'
        if fault=='cpu_pointer':changed['pointer']='/entries/0/fullBrightCpuRgba';data['entries'][0]['fullBrightCpuRgba']=[252,252,252,255]
        if fault=='source':changed['sourceSha256']='wrong'
        if fault=='record_source':data['sourceSha256']='wrong'
        if fault=='image':changed['imageId']='sha256:wrong'
        if fault=='record_image':data['imageId']='sha256:wrong'
        if fault=='gpu':data['entries'][0]['fullBrightGpuRgba']=[255,255,255,255]
        if fault=='sampler':data['entries'][0]['appliedSampler2TextureId']=99
        if fault=='passed':data['passed']=False
        if fault=='exit':data['exitCode']=1
        if fault=='input_modified':data['officialInputsModified']=True
        if fault=='cpu_probe':
            changed.update(measurement='gpu-target fullBrightArgb=-197380',pointer='/capture/captures/0/fullBrightArgb');data['capture']={'lightmapProbe':'cpu-lightPixels','captures':[{'fullBrightArgb':-197380}]}
        digest=write(data)
        if fault!='record_hash':changed['recordSha256']=digest
        try:validate(changed,path,'source')
        except (ValueError,KeyError,TypeError,IndexError,FileNotFoundError):print(fault,'PASS')
        else:raise AssertionError('破損を受理しました: '+fault)
    data=copy.deepcopy(document);data['capture']={'lightmapProbe':'gpu-target','captures':[{'fullBrightArgb':-197380}]};changed=copy.deepcopy(observation);changed.update(measurement='gpu-target fullBrightArgb=-197380',pointer='/capture/captures/0/fullBrightArgb',recordSha256=write(data));validate(changed,record,'source');print('gpu_target_baseline','PASS')

validate_image({'imageId':'sha256:world'},'sha256:world')
try:validate_image({'imageId':'sha256:world'},'sha256:actual-other')
except ValueError:print('actual_image','PASS')
else:raise AssertionError('異なる実imageを受理しました')
