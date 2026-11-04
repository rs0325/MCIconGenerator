"""検証済みELFと固定unitを、導入処理のない管理者用DEBへまとめる。"""
from pathlib import Path,PurePosixPath
import argparse,hashlib,json,io,tarfile,lzma,struct,stat
from elf import inspect
ROOT=Path(__file__).resolve().parents[1]
LICENSES={'zig-MIT.txt','libcxx.txt','libcxxabi.txt','libunwind.txt','glibc-NOTICES.txt','systemd-LGPL2.1.txt','libsystemd-copyright.txt','libcrypto-copyright.txt'}
def sha(body):return hashlib.sha256(body).hexdigest()
def regular(path,cap):
    assert path.is_file() and not path.is_symlink()
    body=path.read_bytes();assert 0<len(body)<=cap
    return body
def archive(files,directories=False):
    out=io.BytesIO()
    with tarfile.open(fileobj=out,mode='w',format=tarfile.USTAR_FORMAT) as tar:
        if directories:
            parents={str(p) for name in files for p in PurePosixPath(name).parents if str(p)!='.'}
            assert not parents.intersection(files),'directory/file name collision'
            for name in sorted(parents):
                item=tarfile.TarInfo(name);item.type=tarfile.DIRTYPE;item.mode=0o755;item.uid=item.gid=0;item.uname=item.gname='root';item.mtime=0;tar.addfile(item)
        for name,(body,mode) in sorted(files.items()):
            item=tarfile.TarInfo(name);item.size=len(body);item.mode=mode;item.uid=item.gid=0;item.uname=item.gname='root';item.mtime=0;tar.addfile(item,io.BytesIO(body))
    return lzma.compress(out.getvalue(),preset=6)
def member(name,body):
    header=(name+'/').ljust(16)+'0'.ljust(12)+'0'.ljust(6)+'0'.ljust(6)+'100644'.ljust(8)+str(len(body)).ljust(10)+'`\n'
    assert len(header)==60
    return header.encode('ascii')+body+(b'\n' if len(body)%2 else b'')
def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--binary',required=True,type=Path);parser.add_argument('--build-json',required=True,type=Path);parser.add_argument('--output',required=True,type=Path);parser.add_argument('--runtime-licenses',required=True,type=Path);parser.add_argument('--private-prefix',required=True,action='append');args=parser.parse_args()
    binary=regular(args.binary,16777216);assert binary[:6]==b'\x7fELF\x02\x01' and struct.unpack_from('<H',binary,18)[0]==62
    elf=inspect(binary,args.private_prefix)
    record=json.loads(regular(args.build_json,65536));assert record['target']=='x86_64-linux-gnu.2.39' and record['binarySha256']==sha(binary)
    for name,digest in record['sources'].items():assert name in ['src/reader.cpp','src/reader.hpp','src/main.cpp'] and sha(regular(ROOT/name,131072))==digest
    assert set(record['sources'])=={'src/reader.cpp','src/reader.hpp','src/main.cpp'}
    assert record['elf']==elf
    assert set(record['runtimeLicenses'])==LICENSES
    licenses={}
    for name,digest in record['runtimeLicenses'].items():
        body=regular(args.runtime_licenses/name,65536);assert sha(body)==digest;licenses[name]=body
    data={'usr/libexec/mcicon-manager-reader':(binary,0o755),'usr/share/mcicon-manager-reader/build.json':((json.dumps(record,sort_keys=True,indent=2)+'\n').encode(),0o644)}
    for name in ['mcicon-manager-reader.socket','mcicon-manager-reader@.service']:data['usr/lib/systemd/system/'+name]=(regular(ROOT/'packaging'/name,8192),0o644)
    for name in ['README.md','PROTOCOL.md','LICENSE','THIRD_PARTY_NOTICES.md']:data['usr/share/doc/mcicon-manager-reader/'+name]=(regular(ROOT/name,65536),0o644)
    for name,body in licenses.items():data['usr/share/doc/mcicon-manager-reader/licenses/'+name]=(body,0o644)
    control=b'Package: mcicon-manager-reader\nVersion: 0.1.0-1\nArchitecture: amd64\nMaintainer: MCIconGenerator contributors\nDepends: libc6 (>= 2.39), libsystemd0 (>= 255.4), libssl3t64 (>= 3.0.13)\nSection: admin\nPriority: optional\nDescription: Fixed UID-authorized read-only systemd user manager observer\n'
    result=b'!<arch>\n'+member('debian-binary',b'2.0\n')+member('control.tar.xz',archive({'control':(control,0o644)}))+member('data.tar.xz',archive(data,True))
    args.output.mkdir(mode=0o700);package=args.output/'mcicon-manager-reader_0.1.0-1_amd64.deb'
    with package.open('xb') as out:out.write(result)
    report={'package':package.name,'bytes':len(result),'sha256':sha(result),'maintainerScripts':False,'entries':[{'path':name,'size':len(body),'mode':mode,'sha256':sha(body)} for name,(body,mode) in sorted(data.items())],'installed':False}
    (args.output/'package.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print(json.dumps({'bytes':len(result),'sha256':sha(result),'installed':False}))
if __name__=='__main__':main()
