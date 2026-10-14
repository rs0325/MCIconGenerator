import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

spec=importlib.util.spec_from_file_location('dynamic',Path(__file__).with_name('world_dynamic_reference.py'))
dynamic=importlib.util.module_from_spec(spec);spec.loader.exec_module(dynamic)

class WorldDynamicReferenceTest(unittest.TestCase):
    def test_original_png_and_initial_frame_across_pack_formats(self):
        for version,metadata,expected in [('1.13',None,{'pack_format':4}),('1.16.5',6,{'pack_format':6}),('1.21.4',{'resource':46},{'pack_format':46}),('26.2',{'resource_major':88,'resource_minor':0},{'min_format':[88,0],'max_format':[88,0]}),('26.3',{'resource_major':97,'resource_minor':1},{'min_format':[97,1],'max_format':[97,1]})]:
            with self.subTest(version=version),tempfile.TemporaryDirectory() as temporary:
                root=Path(temporary);client=root/'client.jar';pack=root/'pack.zip';texture='assets/minecraft/textures/block/test.png'
                with zipfile.ZipFile(client,'w') as archive:
                    if metadata is None:archive.writestr('pack.mcmeta',json.dumps({'pack':{'pack_format':4}}))
                    else:archive.writestr('version.json',json.dumps({'pack_version':metadata}))
                    archive.writestr(texture,b'original PNG bytes');archive.writestr(texture+'.mcmeta',json.dumps({'animation':{'frames':[{'index':2,'time':8},0],'interpolate':True,'frametime':4}}))
                before=client.read_bytes();records=dynamic.frame_pack(client,pack,version)
                self.assertEqual(client.read_bytes(),before);self.assertEqual(records[0]['initialFrame'],2)
                self.assertTrue(dynamic.frame_pack_passed(version,records))
                with zipfile.ZipFile(pack) as archive:
                    self.assertEqual(archive.read(texture),b'original PNG bytes')
                    value=json.loads(archive.read(texture+'.mcmeta'))['animation'];self.assertEqual(value['frames'],[2,2] if version in ('26.2','26.3') else [2]);self.assertFalse(value['interpolate']);self.assertEqual(value['frametime'],4)
                    value=json.loads(archive.read('pack.mcmeta'))['pack'];value.pop('description');self.assertEqual(value,expected)
        value={'initialFrame':2,'frameIndices':[2,2],'frameTime':4}
        self.assertTrue(dynamic.frame_pack_passed('26.2',[value]))
        self.assertFalse(dynamic.frame_pack_passed('1.21.11',[value]))
        self.assertFalse(dynamic.frame_pack_passed('26.2',[dict(value,frameIndices=[2,3])]))
        self.assertFalse(dynamic.frame_pack_passed('26.2',[dict(value,frameTime=0)]))

    def test_wrong_pack_source_or_unapplied_glint_is_rejected(self):
        resource='minecraft:textures/block/test.png';item='minecraft:enchanted_book'
        value={'frameZeroResourceSources':{resource:'file/mcicon-frame-zero.zip'},'glintClockCallSites':2,'fixedGlintMillis':1000,'captures':[{'id':'empty'},{'id':item,'hasFoil':True,'glintClockCalls':2,'fixedGlintMillis':1000}]}
        self.assertTrue(dynamic.controlled_capture_passed('1.13',[item],value,[resource],True,True))
        for field,bad in [('glintClockCallSites',1),('fixedGlintMillis',0),('frameZeroResourceSources',{resource:'vanilla'})]:
            changed=dict(value,**{field:bad});self.assertFalse(dynamic.controlled_capture_passed('1.13',[item],changed,[resource],True,True))
        for field,bad in [('hasFoil',False),('glintClockCalls',0),('fixedGlintMillis',0)]:
            changed=dict(value,captures=[{'id':'empty'},dict(value['captures'][1],**{field:bad})]);self.assertFalse(dynamic.controlled_capture_passed('1.13',[item],changed,[resource],True,True))
        changed=dict(value,frameZeroResourceSources={resource:'other-mcicon-frame-zero.zip'})
        self.assertFalse(dynamic.controlled_capture_passed('1.13',[item],changed,[resource],True,True))
        changed=dict(value,captures=[{'id':'empty'},{'id':item},{'id':item}])
        self.assertFalse(dynamic.controlled_capture_passed('1.13',[item],changed,[resource],True,True))
        self.assertTrue(dynamic.controlled_capture_passed('1.13',[],{},[],False,False))

    def test_gpu_lightmap_requires_observed_texel_and_gui_texture_identity(self):
        value={'fullBrightArgb':-197380,'fullBrightCpuRgba':[252,252,252,255],'fullBrightGpuRgba':[252,252,252,255],'fullBrightGpuTexel':[15,15],'lightmapTextureId':7,'guiLightmapShaderTexture':7,'appliedSampler2TextureId':7,'appliedSampler2Observations':1}
        baseline=[252,252,252]
        self.assertTrue(dynamic.gpu_lightmap_matches(value,baseline))
        for field,bad in [('fullBrightGpuRgba',[255,255,255,255]),('guiLightmapShaderTexture',8),('fullBrightGpuTexel',[0,0]),('appliedSampler2TextureId',8),('appliedSampler2Observations',0)]:
            self.assertFalse(dynamic.gpu_lightmap_matches(dict(value,**{field:bad}),baseline))
        self.assertFalse(dynamic.gpu_lightmap_matches({'fullBrightCpuRgba':[252,252,252,255]},baseline))
        for rgba in [None,'252,252,252,255',[252,252,252],[252,252,252,256],[252,252,252,True]]:
            self.assertFalse(dynamic.gpu_lightmap_matches(dict(value,fullBrightGpuRgba=rgba,fullBrightCpuRgba=rgba),baseline))
        self.assertFalse(dynamic.gpu_lightmap_matches(dict(value,fullBrightGpuRgba=[255,255,255,255],fullBrightCpuRgba=[255,255,255,255]),baseline))
        self.assertFalse(dynamic.gpu_lightmap_matches(dict(value,fullBrightArgb=-1,fullBrightGpuRgba=[255,255,255,255],fullBrightCpuRgba=[255,255,255,255]),baseline))
        self.assertFalse(dynamic.gpu_lightmap_matches(dict(value,fullBrightArgb=-1),baseline))
        self.assertFalse(dynamic.gpu_lightmap_matches(value,[255,255,255]))

if __name__=='__main__':unittest.main()
