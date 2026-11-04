"""配布候補ELFのloader/dependency/実行stackを非実行で確認する。"""
import struct

def inspect(body, private_prefixes):
    assert private_prefixes and all(private_prefixes),'private build prefixes required'
    assert not any(prefix.encode('utf-8') in body for prefix in private_prefixes),'private build path remains'
    assert len(body)>=64 and body[:7]==b'\x7fELF\x02\x01\x01'
    assert struct.unpack_from('<HH',body,16)==(3,62),'amd64 PIE ELF required'
    offset=struct.unpack_from('<Q',body,32)[0];size,count=struct.unpack_from('<HH',body,54);assert size==56 and 0<count<=64 and offset+size*count<=len(body)
    headers=[struct.unpack_from('<IIQQQQQQ',body,offset+i*size) for i in range(count)]
    for h in headers:assert h[2]+h[5]<=len(body) and not (h[0]==1 and h[1]&3==3),'invalid/RWX load segment'
    interps=[h for h in headers if h[0]==3];assert len(interps)==1
    h=interps[0];interpreter=body[h[2]:h[2]+h[5]];assert interpreter==b'/lib64/ld-linux-x86-64.so.2\0'
    stacks=[h for h in headers if h[0]==0x6474e551];assert len(stacks)==1 and stacks[0][1]&1==0,'executable/unknown stack'
    dynamics=[h for h in headers if h[0]==2];assert len(dynamics)==1;h=dynamics[0];assert h[5]%16==0
    entries=[]
    for i in range(h[2],h[2]+h[5],16):
        tag,value=struct.unpack_from('<qQ',body,i)
        if tag==0:break
        entries.append((tag,value))
    else:raise AssertionError('unterminated dynamic table')
    addr=[x for tag,x in entries if tag==5];length=[x for tag,x in entries if tag==10];assert len(addr)==len(length)==1
    loads=[h for h in headers if h[0]==1 and h[3]<=addr[0] and addr[0]+length[0]<=h[3]+h[5]];assert len(loads)==1
    h=loads[0];start=h[2]+addr[0]-h[3];strings=body[start:start+length[0]]
    def string(i):
        assert i<len(strings);end=strings.index(b'\0',i);return strings[i:end].decode('ascii','strict')
    needed=[string(x) for tag,x in entries if tag==1];assert len(needed)==len(set(needed))
    assert {'libc.so.6','libsystemd.so.0','libcrypto.so.3'}<=set(needed)
    assert set(needed)<={'libc.so.6','libm.so.6','libsystemd.so.0','libcrypto.so.3','ld-linux-x86-64.so.2'},'unexpected runtime dependency'
    assert not any(tag in [15,29,22] or tag==30 and x&4 for tag,x in entries),'RPATH/RUNPATH/text relocation forbidden'
    assert any(h[0]==0x6474e552 for h in headers),'RELRO required'
    assert any(tag==0x6ffffffb and x&1 for tag,x in entries) or any(tag==24 for tag,x in entries),'NOW binding required'
    section_offset=struct.unpack_from('<Q',body,40)[0];section_size,section_count,string_index=struct.unpack_from('<HHH',body,58)
    assert section_count<=4096
    if section_count:
        assert section_size==64 and string_index<section_count and section_offset+section_size*section_count<=len(body)
        sections=[struct.unpack_from('<IIQQQQIIQQ',body,section_offset+i*section_size) for i in range(section_count)]
        header=sections[string_index];assert header[4]+header[5]<=len(body);names=body[header[4]:header[4]+header[5]]
        for header in sections:
            assert header[0]<len(names);end=names.index(b'\0',header[0]);name=names[header[0]:end]
            assert not name.startswith((b'.debug',b'.zdebug')),'debug section remains'
    return {'arch':'amd64','type':'PIE','interpreter':interpreter[:-1].decode(),'needed':needed,'rpath':[],'nonExecutableStack':True,'targetExecuted':False}
