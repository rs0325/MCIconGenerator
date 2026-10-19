"""検証済payloadとJava21から、linkの無い自己完結app-imageを生成する。"""
from pathlib import Path
import argparse,subprocess,json,hashlib,shutil,os,struct,plistlib,zipfile
if not __debug__:raise RuntimeError("検証を省略するPython最適化設定は使用できません")
p=argparse.ArgumentParser();p.add_argument('--jdk',type=Path,required=True);p.add_argument('--input',type=Path,required=True);p.add_argument('--payload',type=Path,required=True);p.add_argument('--native',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
assert not a.output.exists(),'既存app-imageは上書きしない';a.output.mkdir(parents=True);work=a.output/'staging';work.mkdir();runtime=work/'runtime';inputs=work/'input';inputs.mkdir()
def run(args,timeout=90):subprocess.run([str(x) for x in args],check=True,timeout=timeout)
manifest=json.loads((a.payload/'payload.json').read_text());assert manifest['schemaVersion']==1 and manifest['limaVersion']=='2.2.1';names=[]
for row in manifest['files']:
 path=Path(row['path']);assert not path.is_absolute() and '..' not in path.parts;source=a.payload/path;assert source.is_file() and not source.is_symlink() and source.resolve().is_relative_to(a.payload.resolve());assert source.stat().st_size==row['bytes'] and hashlib.sha256(source.read_bytes()).hexdigest()==row['sha256'];names.append(row['path'])
assert len(names)==len(set(names)) and 'bin/limactl' in names and 'share/lima/lima-guestagent.Linux-aarch64.gz' in names
support=inputs/'support';support.mkdir();shutil.copy2(a.payload/'payload.json',support/'payload.json')
for row in manifest['files']:
 target=support/row['path'];target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(a.payload/row['path'],target);target.chmod(0o755 if row['executable'] else 0o644)
for jar in a.input.glob('*.jar'):shutil.copyfile(jar,inputs/jar.name)
license_dir=support/'licenses';(license_dir/'mcicon').mkdir(parents=True)
shutil.copyfile(Path(__file__).resolve().parents[3]/'LICENSE',license_dir/'mcicon/LICENSE')
jar_documents={
 'jackson-annotations-2.22.jar':['META-INF/LICENSE','META-INF/NOTICE'],
 'jackson-core-2.22.3.jar':['META-INF/LICENSE','META-INF/NOTICE','META-INF/FastDoubleParser-LICENSE','META-INF/FastDoubleParser-ThirdParty-LICENSE','META-INF/Schubfach-LICENSE'],
 'jackson-databind-2.22.3.jar':['META-INF/LICENSE','META-INF/NOTICE']}
for name,documents in jar_documents.items():
 with zipfile.ZipFile(inputs/name) as jar:
  for entry in documents:
   matches=[item for item in jar.infolist() if item.filename==entry];assert len(matches)==1 and not matches[0].is_dir() and matches[0].file_size<1048576,'Jackson文書が不足/重複しています'
   target=license_dir/'jackson'/name.removesuffix('.jar')/entry;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(jar.read(matches[0]));target.chmod(0o644)
main=list(inputs.glob('helper-macos-*.jar'));assert len(main)==1,'helper-macos JARが一意でない'
run(['/usr/bin/clang','-std=c17','-O2','-Wall','-Wextra','-Werror',a.native/'process-guard.c','-o',support/'process-guard'])
run(['/usr/bin/swiftc','-O','-module-cache-path',work/'swift-cache','-target','arm64-apple-macosx13.0',a.native/'host-probe.swift','-o',support/'host-probe'])
# libc無しの固定CPUID assemblyだけをLinux ELFへ置く。linker/guest tool導入は不要。
object_file=work/'cpu-probe.o'
run(['/usr/bin/clang','-target','x86_64-linux-gnu','-c',a.native/'cpu-probe.S','-o',object_file])
obj=object_file.read_bytes();assert obj[:6]==b'\x7fELF\x02\x01' and struct.unpack_from('<H',obj,18)[0]==62
section_offset=struct.unpack_from('<Q',obj,40)[0];section_size,section_count,names_index=struct.unpack_from('<HHH',obj,58)
sections=[struct.unpack_from('<IIQQQQIIQQ',obj,section_offset+i*section_size) for i in range(section_count)]
names_section=sections[names_index];names_data=obj[names_section[4]:names_section[4]+names_section[5]];text=None
for section in sections:
 name=names_data[section[0]:].split(b'\0',1)[0]
 assert section[1] not in [4,9] or section[5]==0,'CPU probeに未解決relocationがある'
 if name==b'.text':text=obj[section[4]:section[4]+section[5]]
assert text and len(text)<1024
size=4096+len(text);header=struct.pack('<16sHHIQQQIHHHHHH',b'\x7fELF\x02\x01\x01'+b'\0'*9,2,62,1,0x401000,64,0,0,64,56,1,0,0,0)
program=struct.pack('<IIQQQQQQ',1,5,0,0x400000,0x400000,size,size,4096)
cpu=support/'offline/cpu-probe';cpu.parent.mkdir(exist_ok=True);cpu.write_bytes((header+program).ljust(4096,b'\0')+text);cpu.chmod(0o755)
run([a.jdk/'bin/jlink','--add-modules','java.se,jdk.crypto.ec','--strip-debug','--no-header-files','--no-man-pages','--output',runtime])
links=[]
for link in sorted(runtime.rglob('*')):
 if link.is_symlink():
  target=link.resolve(strict=True);relative=link.relative_to(runtime);assert relative.parts[0]=='legal' and target.is_relative_to(runtime.resolve()) and target.is_file() and not target.is_symlink(),'未知/外向きruntime link';data=target.read_bytes();links.append({'path':relative.as_posix(),'target':os.readlink(link),'sha256':hashlib.sha256(data).hexdigest()});link.unlink();link.write_bytes(data);link.chmod(0o644)
# jpackageが除去する公式limactlの署名を戻し、outer sealだけを整える。
origin=(a.payload/'payload.json').read_bytes()
run([a.jdk/'bin/jpackage','--type','app-image','--name','MCIconHelper','--input',inputs,'--main-jar',main[0].name,'--main-class','io.github.rs0325.mcicon.helper.macos.Main','--runtime-image',runtime,'--dest',a.output/'image'])
app=a.output/'image/MCIconHelper.app';signing=[]
for row in manifest['files']:
 file=app/'Contents/app/support'/row['path'];data=file.read_bytes();digest=hashlib.sha256(data).hexdigest()
 if len(data)!=row['bytes'] or digest!=row['sha256']:
  assert row['path']=='bin/limactl','未知のpayloadが署名で変更された'
  signing.append({'path':row['path'],'jpackageBytes':len(data),'jpackageSha256':digest,'restoredOfficialBytes':row['bytes'],'restoredOfficialSha256':row['sha256']})
  shutil.copy2(a.payload/row['path'],file)
run(['/usr/bin/codesign','--force','--sign','-',app],30)
limactl=app/'Contents/app/support/bin/limactl'
run(['/usr/bin/codesign','--verify','--strict',limactl],30)
def entitlements(path):
 result=subprocess.run(['/usr/bin/codesign','-d','--entitlements',':-',str(path)],check=True,capture_output=True,timeout=10);return plistlib.loads(result.stdout)
original_entitlements=entitlements(a.payload/'bin/limactl');assert original_entitlements=={'com.apple.security.network.client':True,'com.apple.security.network.server':True,'com.apple.security.virtualization':True};assert entitlements(limactl)==original_entitlements
assert (app/'Contents/app/support/payload.json').read_bytes()==origin
for row in manifest['files']:
 file=app/'Contents/app/support'/row['path'];assert file.stat().st_size==row['bytes'] and hashlib.sha256(file.read_bytes()).hexdigest()==row['sha256'],'最終payloadが元公式manifestと一致しない'
assert not any(x.is_symlink() for x in app.rglob('*'));run(['/usr/bin/codesign','--verify','--deep','--strict',app],30)
records=[]
for file in sorted(app.rglob('*')):
 if file.is_file():records.append({'path':file.relative_to(app).as_posix(),'bytes':file.stat().st_size,'sha256':hashlib.sha256(file.read_bytes()).hexdigest(),'mode':oct(file.stat().st_mode&0o777)})
(a.output/'packaging.json').write_text(json.dumps({'status':'packaging-only-not-os-accepted','runtimeLinkMaterialization':links,'payloadSigning':{'method':'restore official limactl signature; outer app ad-hoc only, no deep signing/no entitlement change','originalManifestSha256':hashlib.sha256(origin).hexdigest(),'changedFiles':signing,'finalManifestAllFilesVerified':True,'officialInnerEntitlements':original_entitlements},'files':records,'entrypoint':'MCIconHelper.app/Contents/MacOS/MCIconHelper','limits':['DeveloperID/notarization/Gatekeeper未検証','Intel/OS下限/実VM未受入']},ensure_ascii=False,indent=2)+'\n')
print(app)
