"""実unit/property関数を固定bus mockへ結合し、interface回帰を検査する。"""
from pathlib import Path
import os,shlex,subprocess,tempfile,unittest
ROOT=Path(__file__).resolve().parents[1]

def functions(source):
    parts=[]
    for signature in ['std::string property(', 'Unit unit(']:
        start=source.index(signature);end=source.index('\n}\n',start)+3;parts.append(source[start:end])
    start=source.index('std::string hexBytes(');end=source.index('\n',start);return source[start:end]+'\n'+''.join(parts)

MOCK=r'''
#include "reader.hpp"
#include <cassert>
#include <cstring>
#include <cstdlib>
#include <climits>
#include <memory>
#include <iostream>
using namespace mcicon;
struct Error {};
struct BusError {Error value;};
struct Message {void* value=nullptr;};
struct Bus {void* bus=nullptr;void budget(){}};
static std::string mode;
static std::vector<std::string> calls;
static const char* object="/org/freedesktop/systemd1/unit/user_401000_2eservice";
static void need(bool value,const char* stage,const char* message){if(!value)throw Failure(stage,message);}
static int sd_bus_call_method(void*,const char* destination,const char* path,const char* interface,const char* method,Error*,void** reply,const char* signature,const char* name){
    assert(std::string(destination)=="org.freedesktop.systemd1"&&std::string(path)=="/org/freedesktop/systemd1"&&std::string(interface)=="org.freedesktop.systemd1.Manager"&&std::string(method)=="GetUnit"&&std::string(signature)=="s"&&std::string(name)=="user@1000.service");*reply=reinterpret_cast<void*>(1);return 1;
}
static int sd_bus_message_read(void*,const char* signature,const char** value){assert(std::string(signature)=="o");*value=object;return 1;}
static int sd_bus_get_property_string(void*,const char* destination,const char* path,const char* interface,const char* name,Error*,char** value){
    assert(std::string(destination)=="org.freedesktop.systemd1"&&std::string(path)==object);calls.push_back(std::string(interface)+"."+name);
    std::string result;
    if(std::string(name)=="ControlGroup"){
        if(std::string(interface)!="org.freedesktop.systemd1.Service"||mode=="missing")return -53;
        if(mode=="wrong-type")return -22;
        result=mode=="wrong-group"?"/unrelated.service":"/user.slice/user-1000.slice/user@1000.service";
    }else{assert(std::string(interface)=="org.freedesktop.systemd1.Unit");if(std::string(name)=="Id")result="user@1000.service";else{assert(std::string(name)=="ActiveState");result="active";}}
    *value=strdup(result.c_str());assert(*value);return 1;
}
static int sd_bus_get_property_trivial(void*,const char*,const char* path,const char* interface,const char* name,Error*,char signature,uint32_t* value){assert(std::string(path)==object&&std::string(interface)=="org.freedesktop.systemd1.Service"&&std::string(name)=="MainPID"&&signature=='u');calls.push_back(std::string(interface)+"."+name);*value=42;return 1;}
static int sd_bus_get_property(void*,const char*,const char* path,const char* interface,const char* name,Error*,void** reply,const char* signature){assert(std::string(path)==object&&std::string(interface)=="org.freedesktop.systemd1.Unit"&&std::string(name)=="InvocationID"&&std::string(signature)=="ay");calls.push_back(std::string(interface)+"."+name);*reply=reinterpret_cast<void*>(2);return 1;}
static int sd_bus_message_read_array(void*,char type,const void** bytes,size_t* size){static const std::array<unsigned char,16> id={0xbb,0xbb,0xbb,0xbb,0xbb,0xbb,0xbb,0xbb,0xbb,0xbb,0xbb,0xbb,0xbb,0xbb,0xbb,0xbb};assert(type=='y');*bytes=id.data();*size=id.size();return 1;}
'''
END=r'''
int main(int argc,char** argv){
    assert(argc==2);mode=argv[1];Bus bus;
    try{auto result=unit(bus,1000);assert(mode=="valid");assert(result.pid==42&&result.group=="/user.slice/user-1000.slice/user@1000.service");assert((calls==std::vector<std::string>{"org.freedesktop.systemd1.Unit.Id","org.freedesktop.systemd1.Unit.ActiveState","org.freedesktop.systemd1.Service.ControlGroup","org.freedesktop.systemd1.Service.MainPID","org.freedesktop.systemd1.Unit.InvocationID"}));}
    catch(const Failure& error){assert(mode!="valid");if(mode=="old-interface"||mode=="missing")assert(error.stage=="dbus-property"&&error.native==53);else if(mode=="wrong-type")assert(error.stage=="dbus-property"&&error.native==22);else assert(mode=="wrong-group");}
    std::cout<<"bus mock "<<mode<<" PASS\n";
}
'''

class MainBusTest(unittest.TestCase):
    def test_service_control_group_and_old_interface_rejected(self):
        source=(ROOT/'src/main.cpp').read_text();current=functions(source)
        literal='result.group=property(bus,object,"org.freedesktop.systemd1.Service","ControlGroup")'
        self.assertEqual(current.count(literal),1)
        previous=current.replace(literal,literal.replace('systemd1.Service','systemd1.Unit'))
        with tempfile.TemporaryDirectory(prefix='manager-bus-mock-') as temp:
            root=Path(temp)
            for name,body,modes in [('current',current,['valid','missing','wrong-type','wrong-group']),('previous',previous,['old-interface'])]:
                file=root/(name+'.cpp');file.write_text(MOCK+body+END);output=root/name
                argv=shlex.split(os.environ.get('CXX','c++'))+['-std=c++20','-O2','-g0','-Wall','-Wextra','-Werror','-Wpedantic','-I'+str(ROOT/'src'),str(file),str(ROOT/'src/reader.cpp'),'-o',str(output)]
                result=subprocess.run(argv,capture_output=True,timeout=30);self.assertEqual(result.returncode,0,result.stderr.decode())
                for mode in modes:
                    result=subprocess.run([str(output),mode],capture_output=True,timeout=3);self.assertEqual(result.returncode,0,result.stderr.decode())
                    self.assertIn(('bus mock '+mode+' PASS').encode(),result.stdout)

if __name__=='__main__':unittest.main()
