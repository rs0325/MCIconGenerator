"""固定synthetic入力だけで生成/拒否と既descriptor schemaへの接続を検証する。"""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys

import argparse

B=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('inventory_builder',B/'build-inventory.py');builder=importlib.util.module_from_spec(spec);spec.loader.exec_module(builder)
parser=argparse.ArgumentParser();parser.add_argument('--output',type=Path,required=True);parser.add_argument('--schema',type=Path,default=Path(__file__).resolve().parents[3]/'schemas/managed-runtime-v1.schema.json');args=parser.parse_args()
F=args.output;F.mkdir(mode=0o700)
TREE=F/'helper-tree';TREE.mkdir();(TREE/'Contents').mkdir()
body={'Contents/helper.jar':b'synthetic-jar','Contents/LICENSE':b'synthetic license\n','Contents/NOTICE':b'synthetic notice\n'}
for name,raw in body.items():(TREE/name).write_bytes(raw)
def hashed(raw):return hashlib.sha256(raw).hexdigest()
manifest={'schemaVersion':1,'finalBytesConfirmed':True,'host':{'os':'macos','arch':'aarch64'},'minimumOsVersion':'15.7.7',
    'sourceCommit':'a'*40,'archivePrefix':'MCIconHelper.app',
    'artifact':{'id':'macos-aarch64-helper','role':'host-helper','format':'zip','version':'1.1.0','bytes':123,'sha256':'b'*64,'unpackedBytes':321},
    'files':[{'path':name,'bytes':len(raw),'sha256':hashed(raw),'mode':'0o644'} for name,raw in body.items()]}
component={'id':'synthetic','name':'合成component','version':'1.1.0','files':list(body),'licenseFiles':['Contents/LICENSE'],
    'noticeFiles':['Contents/NOTICE'],'sourceArchiveIds':['synthetic-source'],
    'externalNotices':[{'archiveId':'synthetic-source','path':'notices/LICENSE','kind':'license','sha256':hashed(b'external license')} ]}
mapping={'schemaVersion':1,'components':[component]}
source_manifest={'files':[{'relativePath':'notices/LICENSE','bytes':len(b'external license'),'sha256':hashed(b'external license')}]}
source_path=F/'source-manifest.json';source_path.write_bytes(builder.encode(source_manifest))
publication={'artifactUrl':'https://fixtures.example.invalid/host-helper.zip','inventoryPath':'inventories/macos-aarch64.json',
    'fileManifestSha256':'0'*64,'sources':[{'id':'synthetic-source','url':'https://fixtures.example.invalid/source.zip','bytes':456,'sha256':'c'*64,
        'manifestPath':str(source_path),'manifestSha256':hashed(source_path.read_bytes())}]}
results=[]
def inputs(name,m=None,c=None,p=None):
    folder=F/name;folder.mkdir();m=copy.deepcopy(manifest if m is None else m);c=copy.deepcopy(mapping if c is None else c);p=copy.deepcopy(publication if p is None else p)
    paths=[folder/'manifest.json',folder/'components.json',folder/'publication.json']
    raw=builder.encode(m);paths[0].write_bytes(raw);p['fileManifestSha256']=hashed(raw)
    paths[1].write_bytes(builder.encode(c));paths[2].write_bytes(builder.encode(p))
    return paths

def reject(name,m=None,c=None,p=None,change_files=None,expected=''):
    paths=inputs(name,m,c,p)
    if change_files:change_files(paths)
    try:builder.generate(TREE,*paths)
    except (ValueError,FileNotFoundError) as e:
        assert expected in str(e),(name,str(e))
        results.append({'name':name,'status':'PASS-expected-rejection','errorType':type(e).__name__});return
    raise AssertionError(name+' was not rejected')

normal=inputs('normal')
out=F/'normal-output'
cmd=[sys.executable,str(B/'build-inventory.py'),'--tree',str(TREE),'--manifest',str(normal[0]),'--components',str(normal[1]),'--publication',str(normal[2]),'--output',str(out)]
process=subprocess.run(cmd,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=10,check=True)
raw=(out/'inventory.json').read_bytes();fragment=json.loads((out/'artifact-fragment.json').read_bytes());report=json.loads(process.stdout)
assert fragment['inventorySha256']==hashed(raw) and report['smallDocumentsVerified']==2
schema=json.loads(args.schema.read_bytes())
expected={'id':'macos-aarch64-helper','role':'host-helper','url':publication['artifactUrl'],'bytes':123,'sha256':'b'*64,'unpackedBytes':321,'format':'zip','inventoryPath':publication['inventoryPath'],'inventorySha256':hashed(raw),'licenseFiles':['MCIconHelper.app/Contents/LICENSE'],'noticeFiles':['MCIconHelper.app/Contents/NOTICE'],'sourceArchives':[{'url':publication['sources'][0]['url'],'sha256':'c'*64}]}
assert fragment==expected
assert set(fragment)==set(schema['$defs']['artifact']['required'])
assert b'manifestPath' not in raw and b'/Users/' not in raw and b'/private/' not in raw
assert fragment['sourceArchives']==[{'url':publication['sources'][0]['url'],'sha256':publication['sources'][0]['sha256']}]
results.append({'name':'normal-cli-and-existing-artifact-schema','status':'PASS','inventoryBytes':len(raw)})
repeated=subprocess.run(cmd,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=10)
assert repeated.returncode!=0 and (out/'inventory.json').read_bytes()==raw
results.append({'name':'existing-output-unchanged','status':'PASS-expected-rejection'})
p=copy.deepcopy(publication);p['artifactUrl']=None;reject('unissued-artifact-url',p=p,expected='公開HTTPS URL')
p=copy.deepcopy(publication);p['sources'][0]['url']=None;reject('unissued-source-url',p=p,expected='公開HTTPS URL')
c=copy.deepcopy(mapping);c['components'][0]['licenseFiles']=['Contents/MISSING'];reject('absent-license-path',c=c,expected='license/noticeがfile manifestにありません')
c=copy.deepcopy(mapping);c['components'][0]['noticeFiles']=['Contents/MISSING'];reject('absent-notice-path',c=c,expected='license/noticeがfile manifestにありません')
c=copy.deepcopy(mapping);c['components'][0]['files'].remove('Contents/helper.jar');reject('uncovered-binary-file',c=c,expected='component未帰属')
c=copy.deepcopy(mapping);c['components'][0]['externalNotices'][0]['path']='notices/MISSING';reject('absent-external-notice',c=c,expected='外部license/notice')
c=copy.deepcopy(mapping);c['components'][0]['externalNotices'][0]['sha256']='0'*64;reject('external-notice-hash',c=c,expected='外部license/noticeのpin')
m=copy.deepcopy(manifest);m['files'][1]['sha256']='0'*64;reject('actual-document-hash',m=m,expected='本文のsize/SHA')
m=copy.deepcopy(manifest);m['finalBytesConfirmed']=False;reject('candidate-not-final',m=m,expected='最終bytes')
m=copy.deepcopy(manifest);m['files'][0]['path']='../escape';reject('unsafe-file-path',m=m,expected='相対path')
m=copy.deepcopy(manifest);m['archivePrefix']='a'*500;reject('archive-prefix-combined-path-limit',m=m,expected='相対path')
m=copy.deepcopy(manifest);m['minimumOsVersion']='13.0';reject('compile-target-not-support-minimum',m=m,expected='Mac対応範囲')
p=copy.deepcopy(publication);p['sources'][0]['manifestSha256']='0'*64;reject('source-manifest-pin',p=p,expected='source bundle manifestのpin')
def bad_manifest_pin(paths):
    p=json.loads(paths[2].read_bytes());p['fileManifestSha256']='0'*64;paths[2].write_bytes(builder.encode(p))
reject('file-manifest-pin',change_files=bad_manifest_pin,expected='file manifestのpin')
license_file=TREE/'Contents/LICENSE';original=license_file.read_bytes();license_file.unlink();license_file.symlink_to('NOTICE')
reject('license-link',expected='license/noticeのlink')
license_file.unlink();license_file.write_bytes(original)
# UTF-8の実bytesで4MiBを超える正常fieldを作り、発行前に拒否する。
c=copy.deepcopy(mapping);c['components'][0]['name']='あ'*(builder.MAX_INVENTORY//3+1)
reject('inventory-utf8-over-four-mib',c=c,expected='4MiB')
# 固定asset形式外の入力を拒否し、必要なescaped pathは受け付ける。
url_cases = {
    'url-uppercase-scheme': 'HTTPS://example.invalid/helper.zip',
    'url-mixed-scheme': 'hTtPs://example.invalid/helper.zip',
    'url-underscore-host': 'https://host_name.invalid/helper.zip',
    'url-edge-hyphen-host': 'https://-host.invalid/helper.zip',
    'url-empty-host-label': 'https://example..invalid/helper.zip',
    'url-numeric-tld': 'https://example.123/helper.zip',
    'url-invalid-port': 'https://example.invalid:abc/helper.zip',
    'url-authority-suffix': 'https://[::1]suffix/helper.zip',
    'url-path-brackets': 'https://example.invalid/helper[file].zip',
    'url-query-outside-asset-form': 'https://example.invalid/helper.zip?download=1',
    'url-ipv6-outside-asset-form': 'https://[::1]/helper.zip',
    'url-port-outside-asset-form': 'https://example.invalid:443/helper.zip',
    'url-unicode-outside-asset-form': 'https://example.invalid/資料.zip',
    'url-space': 'https://example.invalid/helper file.zip',
    'url-tab': 'https://example.invalid/helper\t.zip',
    'url-newline': 'https://example.invalid/helper\n.zip',
    'url-control': 'https://example.invalid/helper\x01.zip',
    'url-invalid-escape': 'https://example.invalid/helper%GG.zip',
    'url-incomplete-escape': 'https://example.invalid/helper%.zip',
    'url-empty-fragment': 'https://example.invalid/helper.zip#',
    'url-illegal-character': 'https://example.invalid/helper<file.zip',
}
for name, value in url_cases.items():
    p=copy.deepcopy(publication);p['artifactUrl']=value
    reject(name,p=p,expected='公開HTTPS URL')
p=copy.deepcopy(publication);p['sources'][0]['url']='https://example.invalid/source.zip#'
reject('source-url-empty-fragment',p=p,expected='公開HTTPS URL')
assert builder.https('https://example.invalid/helper%20file.zip')=='https://example.invalid/helper%20file.zip'
results.append({'name':'url-valid-escaped-asset-path','status':'PASS'})
# file/memberの0bytesはpackager/coreと同じく有効、archive本体は正のまま。
empty=TREE/'Contents/EMPTY';empty.write_bytes(b'')
m=copy.deepcopy(manifest);m['files'].append({'path':'Contents/EMPTY','bytes':0,'sha256':hashed(b''),'mode':'0o644'})
c=copy.deepcopy(mapping);c['components'][0]['files'].append('Contents/EMPTY')
old_source=source_path.read_bytes();sm=copy.deepcopy(source_manifest)
sm['files'].append({'relativePath':'notices/EMPTY','bytes':0,'sha256':hashed(b'')})
source_path.write_bytes(builder.encode(sm));p=copy.deepcopy(publication);p['sources'][0]['manifestSha256']=hashed(source_path.read_bytes())
c['components'][0]['externalNotices'].append({'archiveId':'synthetic-source','path':'notices/EMPTY','kind':'notice','sha256':hashed(b'')})
a=inputs('zero-file-and-source-member',m,c,p);inv,_,_=builder.generate(TREE,*a);iv=json.loads(inv)
assert next(x for x in iv['files'] if x['path'].endswith('/EMPTY'))['bytes']==0
assert iv['components'][0]['externalNotices'][-1]['bytes']==0
results.append({'name':'zero-file-and-source-member','status':'PASS'})
m['files'][-1]['bytes']=-1;reject('negative-file-size',m=m,c=c,p=p,expected='非負')
sm['files'][-1]['bytes']=-1;source_path.write_bytes(builder.encode(sm));p['sources'][0]['manifestSha256']=hashed(source_path.read_bytes())
reject('negative-source-member-size',p=p,expected='非負')
source_path.write_bytes(old_source);empty.unlink()
m=copy.deepcopy(manifest);m['artifact']['bytes']=0;reject('zero-archive-size',m=m,expected='正の整数')
report={'status':'PASS','conditions':len(results),'results':results,'realProductionArtifactGenerated':False,
    'fixtureUrlsOnly':True,'sourceBodiesOrJreRehashed':False,'vmNetworkOrSigningUsed':False,
    'sourceScriptSha256':hashed((B/'build-inventory.py').read_bytes())}
(F/'fixture-result.json').write_bytes(builder.encode(report))
print(json.dumps({'status':'PASS','conditions':len(results)},ensure_ascii=False))
