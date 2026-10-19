"""既JDKと明示seed/public trustから、自己完結Mac app-image候補を生成する。署名鍵は生成しない。"""
from pathlib import Path
import argparse,subprocess,shutil,hashlib,json,sys,platform
p=argparse.ArgumentParser();p.add_argument('--jdk',type=Path,required=True);p.add_argument('--input',type=Path,required=True);p.add_argument('--seed',type=Path,required=True);p.add_argument('--trust',type=Path,required=True);p.add_argument('--version',required=True);p.add_argument('--owner',choices=['homebrew','winget','installer','archive'],required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
if sys.platform!='darwin':raise RuntimeError('初段app-imageの受入対象はMacのみです')
if a.output.exists():raise RuntimeError('既存候補は上書きしません')
for name in ['stable.json','stable.json.sig','tool.zip']:
 source=a.seed/name
 if not source.is_file() or source.is_symlink():raise RuntimeError('明示seed通常fileが必要です')
if not a.trust.is_file() or a.trust.is_symlink() or a.trust.stat().st_size>1024:raise RuntimeError('明示public trust通常fileが必要です')
main='bootstrap-'+a.version+'.jar'
if not (a.input/main).is_file():raise RuntimeError('同版bootstrap JARがありません')
a.output.mkdir(parents=True,mode=0o700);stage=a.output/'staging';stage.mkdir(mode=0o700);inputs=stage/'input';inputs.mkdir();runtime=stage/'runtime'
for source in a.input.glob('*.jar'):
 if source.is_symlink():raise RuntimeError('input JAR linkは拒否します')
 shutil.copyfile(source,inputs/source.name)
(inputs/'seed').mkdir()
for name in ['stable.json','stable.json.sig','tool.zip']:shutil.copyfile(a.seed/name,inputs/'seed'/name)
shutil.copyfile(a.trust,inputs/'trust.der')
arch={'arm64':'aarch64','aarch64':'aarch64','x86_64':'x86_64'}.get(platform.machine())
if arch is None:raise RuntimeError('未受入host archです')
info={'schemaVersion':1,'bootstrapVersion':a.version,'bootstrapProtocol':1,'owner':a.owner,'host':{'os':'macos','arch':arch}}
(inputs/'bootstrap-package.json').write_text(json.dumps(info,ensure_ascii=False,indent=2)+'\n')
repo=Path(__file__).resolve().parents[2];shutil.copyfile(repo/'LICENSE',inputs/'LICENSE');shutil.copyfile(repo/'THIRD_PARTY_NOTICES.md',inputs/'THIRD_PARTY_NOTICES.md');shutil.copytree(repo/'licenses',inputs/'licenses')
def run(args):subprocess.run([str(v) for v in args],stdin=subprocess.DEVNULL,check=True,timeout=120)
run([a.jdk/'bin/jlink','--add-modules','java.se,jdk.crypto.ec','--strip-debug','--no-header-files','--no-man-pages','--output',runtime])
for link in sorted(runtime.rglob('*')):
 if link.is_symlink():
  target=link.resolve(strict=True)
  if link.relative_to(runtime).parts[0]!='legal' or not target.is_file() or not target.is_relative_to(runtime.resolve()):raise RuntimeError('runtimeの未知/外向きlinkを拒否します')
  data=target.read_bytes();link.unlink();link.write_bytes(data)
run([a.jdk/'bin/jpackage','--type','app-image','--name','MCIconBootstrap','--app-version',a.version,'--input',inputs,'--main-jar',main,'--main-class','io.github.rs0325.mcicon.bootstrap.Main','--runtime-image',runtime,'--dest',a.output/'image'])
app=a.output/'image/MCIconBootstrap.app';run(['/usr/bin/codesign','--verify','--deep','--strict',app])
records=[]
for file in sorted(app.rglob('*')):
 if file.is_symlink():raise RuntimeError('app-imageにlinkが残っています')
 if file.is_file():records.append({'path':file.relative_to(app).as_posix(),'bytes':file.stat().st_size,'sha256':hashlib.sha256(file.read_bytes()).hexdigest()})
(a.output/'packaging.json').write_text(json.dumps({'status':'local-packaging-only-not-production-trust-or-installer-accepted','entrypoint':str(app/'Contents/MacOS/MCIconBootstrap'),'package':info,'files':records,'privateKeyGenerated':False,'limits':['入力seed署名の実validator受入は起動時に別確認','無Java物理host/実installer/PATH/uninstall/正式OS下限は未受入','本番trust/署名公開/DeveloperID/notarization無し']},ensure_ascii=False,indent=2)+'\n')
print(app)
