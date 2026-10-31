"""包装と元source/loader条件を人工ELFだけで確認する。導入はしない。"""
from pathlib import Path
import sys,struct,subprocess,tempfile,json,hashlib,io,tarfile,lzma,unittest
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT/'scripts'))
from elf import inspect
from package import archive,member,LICENSES
PREFIXES=["/private/mcicon-test-build"]

def fixture():
    b=bytearray(2048);b[:7]=b'\x7fELF\x02\x01\x01';struct.pack_into('<HH',b,16,3,62);struct.pack_into('<Q',b,32,64);struct.pack_into('<HH',b,54,56,5)
    headers=[(1,5,0,0x400000,0,len(b),len(b),4096),(3,4,400,0,0,28,28,1),(2,6,512,0,0,112,112,8),(0x6474e551,6,0,0,0,0,0,16),(0x6474e552,4,0,0,0,0,0,1)]
    for i,h in enumerate(headers):struct.pack_into('<IIQQQQQQ',b,64+56*i,*h)
    interp=b'/lib64/ld-linux-x86-64.so.2\0';b[400:400+len(interp)]=interp;struct.pack_into('<Q',b,64+56+32,len(interp))
    strings=b'libc.so.6\0libsystemd.so.0\0libcrypto.so.3\0';b[800:800+len(strings)]=strings
    entries=[(5,0x400000+800),(10,len(strings)),(1,0),(1,10),(1,26),(0x6ffffffb,1),(0,0)]
    for i,pair in enumerate(entries):struct.pack_into('<qQ',b,512+i*16,*pair)
    return bytes(b)

def loader_fixture(name=b'ld-linux-x86-64.so.2'):
    b=bytearray(fixture());strings=b'libc.so.6\0libsystemd.so.0\0libcrypto.so.3\0';offset=len(strings);value=strings+name+b'\0';b[800:800+len(value)]=value;struct.pack_into('<qQ',b,512+16,10,len(value));struct.pack_into('<qQ',b,512+6*16,1,offset);struct.pack_into('<qQ',b,512+7*16,0,0);struct.pack_into('<QQ',b,64+56*2+32,128,128);return bytes(b)

class PackageTest(unittest.TestCase):
    def test_closed_loader(self):
        v=inspect(fixture(),PREFIXES);self.assertEqual(v['needed'],['libc.so.6','libsystemd.so.0','libcrypto.so.3']);self.assertFalse(v['targetExecuted'])
    def test_direct_fixed_loader(self):
        self.assertIn('ld-linux-x86-64.so.2',inspect(loader_fixture(),PREFIXES)['needed'])
    def test_other_direct_loader_rejected(self):
        with self.assertRaises(AssertionError):inspect(loader_fixture(b'ld-linux-aarch64.so.1'),PREFIXES)
    def test_fixed_loader_wrong_interpreter_rejected(self):
        b=bytearray(loader_fixture());b[400:406]=b'/tmp64'
        with self.assertRaises(AssertionError):inspect(b,PREFIXES)
    def test_private_build_path_rejected(self):
        b=bytearray(fixture());value=PREFIXES[0].encode();b[1400:1400+len(value)]=value
        with self.assertRaises(AssertionError):inspect(b,PREFIXES)
    def test_debug_section_rejected(self):
        b=bytearray(fixture());names=b'\0.shstrtab\0.debug_info\0';b[1500:1500+len(names)]=names;struct.pack_into('<Q',b,40,1600);struct.pack_into('<HHH',b,58,64,3,1);struct.pack_into('<IIQQQQIIQQ',b,1664,1,3,0,0,1500,len(names),0,0,1,0);struct.pack_into('<IIQQQQIIQQ',b,1728,11,1,0,0,1900,1,0,0,1,0)
        with self.assertRaises(AssertionError):inspect(b,PREFIXES)
    def test_empty_prefixes_rejected(self):
        with self.assertRaises(AssertionError):inspect(fixture(),[])
    def test_wrong_arch(self):
        b=bytearray(fixture());struct.pack_into('<H',b,18,183)
        with self.assertRaises(AssertionError):inspect(b,PREFIXES)
    def test_rpath_rejected(self):
        b=bytearray(fixture());struct.pack_into('<qQ',b,512+5*16,29,0)
        with self.assertRaises(AssertionError):inspect(b,PREFIXES)
    def test_unknown_dependency(self):
        b=bytearray(fixture());b[800:809]=b'evilx.so!'
        with self.assertRaises(AssertionError):inspect(b,PREFIXES)
    def test_packaged_files_regular_root(self):
        payload=archive({'usr/libexec/fixed':(b'fixture',0o755)})
        with tarfile.open(fileobj=io.BytesIO(lzma.decompress(payload))) as tar:
            row=tar.getmembers()[0];self.assertTrue(row.isfile());self.assertEqual((row.uid,row.gid,row.mode),(0,0,0o755));self.assertEqual(tar.extractfile(row).read(),b'fixture')
        self.assertEqual(member('debian-binary',b'2.0\n')[58:60],b'`\n')
    def test_data_parents_before_files(self):
        files={'usr/share/doc/fixed/LICENSE':(b'license',0o644),'usr/share/doc/fixed/licenses/runtime':(b'notice',0o644),'usr/libexec/fixed':(b'elf',0o755)}
        payload=archive(files,True);self.assertEqual(payload,archive(files,True))
        with tarfile.open(fileobj=io.BytesIO(lzma.decompress(payload))) as tar:
            rows=tar.getmembers();names=[r.name for r in rows];self.assertEqual(len(names),len(set(names)))
            for row in rows:
                self.assertEqual((row.uid,row.gid,row.uname,row.gname,row.mtime),(0,0,'root','root',0))
                if row.isdir():self.assertEqual((row.mode,row.size),(0o755,0))
                else:
                    self.assertTrue(row.isfile());self.assertEqual((tar.extractfile(row).read(),row.mode),files[row.name])
                parent=str(Path(row.name).parent)
                if parent!='.':self.assertLess(names.index(parent),names.index(row.name))
        with self.assertRaises(AssertionError):archive({'usr':(b'file',0o644),'usr/file':(b'child',0o644)},True)
    def test_source_binding_and_no_maintainer_script(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);body=fixture();(root/'reader').write_bytes(body)
            record={'target':'x86_64-linux-gnu.2.39','binarySha256':hashlib.sha256(body).hexdigest(),'sources':{n:hashlib.sha256((ROOT/n).read_bytes()).hexdigest() for n in ['src/reader.cpp','src/reader.hpp','src/main.cpp']},'elf':inspect(body,PREFIXES)}
            (root/'licenses').mkdir();record['runtimeLicenses']={}
            for name in LICENSES:
                (root/'licenses'/name).write_bytes(b'fixture license');record['runtimeLicenses'][name]=hashlib.sha256(b'fixture license').hexdigest()
            (root/'build.json').write_text(json.dumps(record));argv=[sys.executable,str(ROOT/'scripts/package.py'),'--binary',str(root/'reader'),'--build-json',str(root/'build.json'),'--runtime-licenses',str(root/'licenses'),'--private-prefix',PREFIXES[0],'--output',str(root/'out')]
            p=subprocess.run(argv,capture_output=True,timeout=10);self.assertEqual(p.returncode,0,p.stderr.decode());v=json.loads((root/'out/package.json').read_text());self.assertFalse(v['maintainerScripts']);self.assertFalse(v['installed']);self.assertEqual(len([e for e in v['entries'] if '/licenses/' in e['path']]),8)
            name=next(iter(LICENSES));(root/'licenses'/name).write_bytes(b'changed');argv[-1]=str(root/'bad-license');p=subprocess.run(argv,capture_output=True,timeout=10);self.assertNotEqual(p.returncode,0);self.assertFalse((root/'bad-license').exists());(root/'licenses'/name).write_bytes(b'fixture license')
            record['sources']['src/main.cpp']='0'*64;(root/'build.json').write_text(json.dumps(record));argv[-1]=str(root/'bad');p=subprocess.run(argv,capture_output=True,timeout=10);self.assertNotEqual(p.returncode,0);self.assertFalse((root/'bad').exists())
if __name__=='__main__':unittest.main()
