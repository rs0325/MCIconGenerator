"""取得済みの固定LimaとUbuntu debを検証し、host-helper用データを別領域へ準備する。"""
import argparse,hashlib,json,shutil,tarfile
from pathlib import Path
if not __debug__:raise RuntimeError("検証を省略するPython最適化設定は使用できません")
p=argparse.ArgumentParser();p.add_argument('--lima',type=Path,required=True);p.add_argument('--lima-source',type=Path,required=True);p.add_argument('--debs',type=Path,required=True);p.add_argument('--deb-copyrights',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
def digest(path):
 with path.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()
assert not a.output.exists(),'既存payloadは上書きしない'
copyrights=[('containerd', 55702, '68592c95770d75949ec612178bcbc7bc8a57a6eccd6cfd3bb5990219c716a863'), ('docker.io', 47578, 'e0f85dd2076ae0024c669dd5c399a0f6bde6be8dc6015dcdffd4cc24b503e64e'), ('runc', 8887, '4b28f3745ae2a9eb2d332058d10b0b08cf3802d8347be90cc0faf8c811939ca6')]
copyright_data={}
for name,size,sha in copyrights:
 file=a.deb_copyrights/name/'copyright';assert file.is_file() and not file.is_symlink() and file.resolve()==a.deb_copyrights.resolve()/name/'copyright','deb copyrightのpathが一致しない'
 with file.open('rb') as source:data=source.read(size+1)
 assert len(data)==size and hashlib.sha256(data).hexdigest()==sha,'deb copyrightの固定size/SHAが一致しない'
 copyright_data[name]=data
assert a.lima.stat().st_size==38328082 and digest(a.lima)=='9e9eacce88f37e185c346bad73aa6136f738d8cdf8c3bb23cd42b071824bc66e'
assert a.lima_source.stat().st_size==7887831 and digest(a.lima_source)=='d551efb52115ba006c1052d5c929e4d3afac363c78c9cfca6975af1f85c1426a'
packages=[
 ('runc','1.3.4-0ubuntu1~24.04.1',8578418,'725a27bbdd5f58d7059ed25d88f14f2d45f6a1ce089585eddc715e04d4a3f79f'),
 ('containerd','2.2.1-0ubuntu1~24.04.3',25160084,'e12495f100f025750ca91c07bab3a58c674afb1576fe55516608a8daa4d067bd'),
 ('docker.io','29.1.3-0ubuntu3~24.04.2',32026672,'fb0b4f8f4738d3d610b511f50808abd49376ee715a81364e7dbd8a2c60c580d6')]
for name,version,size,sha in packages:
 file=a.debs/f'{name}_{version}_arm64.deb';assert file.is_file() and not file.is_symlink() and file.stat().st_size==size and digest(file)==sha
# VZ/aarch64だけを扱う。追加arch guestagent、driver、template、shell wrapperは含めない。
selected={'bin/limactl','share/lima/lima-guestagent.Linux-aarch64.gz','share/doc/lima/LICENSE','share/doc/lima/VERSION'}
a.output.mkdir(parents=True);seen=set()
with tarfile.open(a.lima,'r:gz') as archive:
 for member in archive:
  name=member.name.removeprefix('./')
  if name not in selected:continue
  assert name not in seen and member.isfile() and not member.linkname;seen.add(name)
  target=a.output/name;target.parent.mkdir(parents=True,exist_ok=True)
  with archive.extractfile(member) as source,target.open('xb') as output:shutil.copyfileobj(source,output)
  target.chmod(0o755 if name=='bin/limactl' else 0o644)
assert seen==selected and (a.output/'share/doc/lima/VERSION').read_text().strip()=='v2.2.1'
debs=[]
for name,version,size,sha in packages:
 filename=f'{name}_{version}_arm64.deb';target=a.output/'offline/debs'/f'{name}.deb';target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(a.debs/filename,target);target.chmod(0o644)
 debs.append({'package':name,'version':version,'architecture':'arm64','path':target.relative_to(a.output).as_posix(),'sourceFilename':filename,'sourceUrl':'https://snapshot.ubuntu.com/ubuntu/20261017T000000Z/'+{'runc':'pool/main/r/runc-app/','containerd':'pool/main/c/containerd-app/','docker.io':'pool/universe/d/docker.io-app/'}[name]+filename})
for name,size,sha in copyrights:
 target=a.output/'share/doc/ubuntu'/name/'copyright';target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(copyright_data[name]);target.chmod(0o644)
sources=a.output/'sources';sources.mkdir();shutil.copyfile(a.lima_source,sources/'lima-v2.2.1.tar.gz')
with tarfile.open(a.lima_source,'r:gz') as archive:
 member=archive.getmember('lima-2.2.1/NOTICE');assert member.isfile() and not member.linkname and member.size<1048576
 notice_file=a.output/'share/doc/lima/NOTICE'
 with archive.extractfile(member) as source,notice_file.open('xb') as output:shutil.copyfileobj(source,output)
 notice_file.chmod(0o644)
notice={'lima':{'version':'2.2.1','url':'https://github.com/lima-vm/lima/releases/download/v2.2.1/lima-2.2.1-Darwin-arm64.tar.gz','sha256':digest(a.lima),'license':'share/doc/lima/LICENSE','sourceUrl':'https://github.com/lima-vm/lima/archive/refs/tags/v2.2.1.tar.gz','sourceArchive':'sources/lima-v2.2.1.tar.gz','sourceSha256':digest(a.lima_source)},'ubuntuSnapshot':'https://snapshot.ubuntu.com/ubuntu/20261017T000000Z/','limits':['deb署名済索引の直接照合・source対応は別の保全済み証拠を参照','全guest/kernel/JRE/rendererのlicense/source完了を示さない','実offline dpkg・VM起動・Intel/OS下限は未検証','Lima同梱依存ライセンスの網羅監査は未完了']}
notice['offlineDebCopyrights']=[{'package':name,'version':next(version for package,version,_,_ in packages if package==name),'path':f'share/doc/ubuntu/{name}/copyright','bytes':size,'sha256':sha} for name,size,sha in copyrights]
(a.output/'NOTICE.json').write_text(json.dumps(notice,ensure_ascii=False,indent=2)+'\n')
files=[]
for file in sorted(a.output.rglob('*')):
 if file.is_file():files.append({'path':file.relative_to(a.output).as_posix(),'bytes':file.stat().st_size,'sha256':digest(file),'executable':file.name=='limactl'})
(a.output/'payload.json').write_text(json.dumps({'schemaVersion':1,'limaVersion':'2.2.1','files':files,'debs':debs},ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'files':len(files),'bytes':sum(row['bytes'] for row in files),'status':'local-preparation-only-no-vm'},ensure_ascii=False))
