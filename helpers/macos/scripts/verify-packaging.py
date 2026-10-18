"""実VMを起動せず、app-imageの移動・TMP環境・全tree・ZIP権限復元を確認する。"""
from pathlib import Path
import argparse,hashlib,json,os,shutil,stat,subprocess,time,zipfile
if not __debug__:raise RuntimeError('Python最適化による検証省略は使用できません')
p=argparse.ArgumentParser();p.add_argument('--image',type=Path,required=True);p.add_argument('--record',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();assert not a.output.exists();a.output.mkdir(parents=True);a.output=a.output.resolve();a.image=a.image.resolve();record=json.loads(a.record.read_text());expected={row['path']:row for row in record['files']};directories={p.relative_to(a.image).as_posix() for p in a.image.rglob('*') if p.is_dir()};deadline=time.monotonic()+180;results=[]
def sha(path):
 with path.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()
def verify(tree,owner_modes=False):
 assert time.monotonic()<deadline,'packaging検証の期限超過';files={}
 for file in tree.rglob('*'):
  assert not file.is_symlink(),'treeにlinkがある'
  if file.is_file():files[file.relative_to(tree).as_posix()]=file
 assert set(files)==set(expected) and {p.relative_to(tree).as_posix() for p in tree.rglob('*') if p.is_dir()}==directories,'tree集合が不一致'
 for name,file in files.items():
  row=expected[name];assert file.stat().st_size==row['bytes'] and sha(file)==row['sha256'],'tree bytesが不一致: '+name
  required=int(row['mode'],8)
  if owner_modes:assert bool(file.stat().st_mode&0o100)==bool(required&0o100),'owner実行権限が不一致'
  else:assert file.stat().st_mode&0o777==required,'modeが不一致'
def launch(tree,name):
 root=a.output/(name+'-root');root.mkdir();temporary=a.output/(name+'-tmp');temporary.mkdir();entry=tree/'Contents/MacOS/MCIconHelper';argv=[str(entry),'--protocol','1','--root',str(root),'--lease',str(root/'jobs/00000000-0000-0000-0000-000000000001/lease.json'),'preflight'];environment={key:str(temporary) for key in ['TMP','TEMP','TMPDIR']};r=subprocess.run(argv,env=environment,capture_output=True,timeout=10)
 (a.output/(name+'.stdout')).write_bytes(r.stdout);(a.output/(name+'.stderr')).write_bytes(r.stderr);value=json.loads(r.stdout);assert r.returncode==1 and value['status']=='failed' and value['diagnostics'][0]['detail']=='所有fileを読み取れません: lease.json' and not r.stderr
 results.append({'name':name,'argv':argv,'environment':environment,'exit':r.returncode,'stdoutSha256':hashlib.sha256(r.stdout).hexdigest(),'scope':'invalid-lease-only-no-vm'})
verify(a.image);relocated=a.output/'relocated with space/MCIconHelper.app';shutil.copytree(a.image,relocated);verify(relocated);subprocess.run(['/usr/bin/codesign','--verify','--deep','--strict',str(relocated)],check=True,timeout=15);launch(relocated,'relocated-TMP-only')
choices=[next(name for name in expected if name.endswith('.jar')),next(name for name in expected if '/runtime/' in name and name.endswith('/lib/modules')),next(name for name in expected if name.endswith('/MCIconHelper.cfg'))]
for name in choices:
 file=relocated/name;data=file.read_bytes();assert data;file.write_bytes(bytes([data[0]^1])+data[1:])
 try:
  try:verify(relocated)
  except AssertionError:results.append({'name':'tree-tamper-rejected','path':name})
  else:raise AssertionError('改変を見逃した')
 finally:file.write_bytes(data)
verify(relocated);link=relocated/'Contents/app/fixture-link';link.symlink_to(Path(choices[0]).name)
try:
 try:verify(relocated)
 except AssertionError:results.append({'name':'tree-link-rejected'})
 else:raise AssertionError('linkを見逃した')
finally:link.unlink()
archive=a.output/'helper.zip'
with zipfile.ZipFile(archive,'x',compression=zipfile.ZIP_DEFLATED,compresslevel=3) as output:
 for name in sorted(directories):output.write(a.image/name,'MCIconHelper.app/'+name+'/')
 for name in sorted(expected):output.write(a.image/name,'MCIconHelper.app/'+name)
roundtrip=a.output/'roundtrip';roundtrip.mkdir()
with zipfile.ZipFile(archive) as source:
 for entry in source.infolist():
  relative=Path(entry.filename);mode=entry.external_attr>>16;assert not relative.is_absolute() and '..' not in relative.parts and relative.parts[0]=='MCIconHelper.app' and not stat.S_ISLNK(mode)
  target=roundtrip/relative
  if entry.is_dir():target.mkdir(parents=True,exist_ok=True)
  else:
   target.parent.mkdir(parents=True,exist_ok=True)
   with source.open(entry) as src,target.open('xb') as dst:shutil.copyfileobj(src,dst)
   target.chmod(0o744 if mode&0o111 else 0o644)
app=roundtrip/'MCIconHelper.app';verify(app,True);subprocess.run(['/usr/bin/codesign','--verify','--deep','--strict',str(app)],check=True,timeout=15);launch(app,'zip-owner-executable');verify(a.image)
(a.output/'validation.json').write_text(json.dumps({'status':'packaging-fixture-only-no-vm','records':results,'archive':{'bytes':archive.stat().st_size,'sha256':sha(archive)},'limits':['本scriptのtree改変拒否はcore RuntimeGeneration受入の代替ではない','実VM/daemon/Intel/OS下限/隔離属性/DeveloperID/notarizationは未受入']},ensure_ascii=False,indent=2)+'\n');print(json.dumps({'status':'PASS-packaging-only-no-vm','records':len(results),'archiveBytes':archive.stat().st_size},ensure_ascii=False))
