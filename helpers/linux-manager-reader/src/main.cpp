#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include "reader.hpp"
#include <systemd/sd-bus.h>
#include <openssl/evp.h>
#include <openssl/crypto.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <fcntl.h>
#include <poll.h>
#include <unistd.h>
#include <cerrno>
#include <chrono>
#include <climits>
#include <cstring>
#include <memory>
#include <sstream>

using namespace mcicon;
namespace {
using Clock=std::chrono::steady_clock;
Clock::time_point end;
int closeError=0;
struct Fd {
    int value;
    explicit Fd(int value):value(value){if(value<0)throw Failure("open","fixed read open failed",errno);}
    ~Fd(){if(close(value)<0&&!closeError)closeError=errno;}
    Fd(const Fd&)=delete;Fd& operator=(const Fd&)=delete;
};
void check(){if(Clock::now()>=end)throw Failure("deadline","reader deadline");}
uint64_t remaining(){check();return std::max<int64_t>(1,std::chrono::duration_cast<std::chrono::microseconds>(end-Clock::now()).count());}
void need(bool value,const char* stage,const char* message){if(!value)throw Failure(stage,message);}
std::string hexBytes(const unsigned char* bytes,size_t size){static const char* h="0123456789abcdef";std::string out;for(size_t i=0;i<size;i++){out+=h[bytes[i]>>4];out+=h[bytes[i]&15];}return out;}
struct stat info(int fd){struct stat s{};if(fstat(fd,&s)<0)throw Failure("stat","fstat failed",errno);return s;}
std::string readFd(int fd,size_t cap){
    std::string result;std::array<char,65536> buffer{};
    for(;;){check();ssize_t n=read(fd,buffer.data(),std::min(buffer.size(),cap+1-result.size()));if(n<0){if(errno==EINTR)continue;throw Failure("read","fixed read failed",errno);}if(!n)return result;result.append(buffer.data(),static_cast<size_t>(n));need(result.size()<=cap,"read","fixed read limit");}
}
std::string procRead(int directory,const char* name,size_t cap){Fd fd(openat(directory,name,O_RDONLY|O_CLOEXEC|O_NOFOLLOW|O_NONBLOCK));return readFd(fd.value,cap);}
std::string bootId(){Fd fd(open("/proc/sys/kernel/random/boot_id",O_RDONLY|O_CLOEXEC|O_NOFOLLOW));auto text=readFd(fd.value,128);need(!text.empty()&&text.back()=='\n',"boot","invalid boot record");text.pop_back();return text;}
File fileInfo(const struct stat& s,std::string hash={}){
    return {static_cast<uint64_t>(s.st_dev),static_cast<uint64_t>(s.st_ino),static_cast<uint64_t>(s.st_size),s.st_uid,s.st_mode,s.st_mtim.tv_sec*1000000000LL+s.st_mtim.tv_nsec,s.st_ctim.tv_sec*1000000000LL+s.st_ctim.tv_nsec,std::move(hash)};
}
std::unique_ptr<Fd> componentFd(size_t index){
    auto directory=std::make_unique<Fd>(open("/",O_PATH|O_DIRECTORY|O_CLOEXEC));auto path=std::string(paths[index]).substr(1);size_t first=0;
    for(;;){check();auto stop=path.find('/',first);std::string part=path.substr(first,stop==std::string::npos?stop:stop-first);
        auto flags=stop==std::string::npos?O_RDONLY|O_NONBLOCK:O_PATH|O_DIRECTORY;
        auto next=std::make_unique<Fd>(openat(directory->value,part.c_str(),flags|O_CLOEXEC|O_NOFOLLOW));auto st=info(next->value);
        need(st.st_uid==0&&(st.st_mode&0022)==0,"component","unsafe fixed path owner/mode");
        if(stop==std::string::npos){need(S_ISREG(st.st_mode)&&(st.st_mode&06000)==0&&st.st_size>0&&st.st_size<=8388608,"component","invalid fixed component");if(index<3)need((st.st_mode&0111)!=0,"component","fixed executable mode");return next;}
        need(S_ISDIR(st.st_mode),"component","fixed ancestor is not directory");directory=std::move(next);first=stop+1;
    }
}
std::array<File,5> components(bool hash){
    std::array<File,5> result{};
    for(size_t i=0;i<5;i++){
        auto fd=componentFd(i);auto before=fileInfo(info(fd->value));std::string digest;
        if(hash){
            need(OPENSSL_init_crypto(OPENSSL_INIT_NO_LOAD_CONFIG,nullptr)==1,"hash","crypto initialization failed");
            std::unique_ptr<EVP_MD_CTX,decltype(&EVP_MD_CTX_free)> context(EVP_MD_CTX_new(),EVP_MD_CTX_free);need(context&&EVP_DigestInit_ex(context.get(),EVP_sha256(),nullptr)==1,"hash","SHA256 init failed");
            std::array<unsigned char,65536> buffer{};uint64_t bytes=0;
            for(;;){check();ssize_t n=read(fd->value,buffer.data(),buffer.size());if(n<0){if(errno==EINTR)continue;throw Failure("hash","component read failed",errno);}if(!n)break;bytes+=n;need(bytes<=before.size,"hash","component size changed");need(EVP_DigestUpdate(context.get(),buffer.data(),n)==1,"hash","SHA256 update failed");}
            need(bytes==before.size,"hash","short component read");std::array<unsigned char,32> value{};unsigned int size=0;need(EVP_DigestFinal_ex(context.get(),value.data(),&size)==1&&size==32,"hash","SHA256 final failed");digest=hexBytes(value.data(),size);
        }
        auto after=fileInfo(info(fd->value));need(before==after,"component","component changed during read");after.hash=std::move(digest);result[i]=std::move(after);
    }
    return result;
}
Process managerProcess(uint32_t pid){
    need(pid>1&&pid<=INT_MAX,"manager","invalid MainPID");auto prefix="/proc/"+std::to_string(pid);Fd directory(open(prefix.c_str(),O_PATH|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW));
    auto before=startTicks(procRead(directory.value,"stat",4096),pid);auto uid=uidValues(procRead(directory.value,"status",65536));auto group=controlGroup(procRead(directory.value,"cgroup",16384));
    std::array<char,4097> link{};ssize_t length=readlinkat(directory.value,"exe",link.data(),link.size());if(length<0)throw Failure("manager","manager exe readlink failed",errno);need(static_cast<size_t>(length)<link.size(),"manager","exe link limit");
    std::string exe(link.data(),length);need(exe==paths[2],"manager","unexpected manager executable");
    Fd executable(openat(directory.value,"exe",O_PATH|O_CLOEXEC));auto ex=info(executable.value);need(S_ISREG(ex.st_mode),"manager","manager executable is not regular");
    Fd namespaces(openat(directory.value,"ns",O_PATH|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC));Fd user(openat(namespaces.value,"user",O_RDONLY|O_CLOEXEC));auto ns=info(user.value);
    need(startTicks(procRead(directory.value,"stat",4096),pid)==before,"manager","manager start changed during read");
    return {pid,before,uid,exe,group,static_cast<uint64_t>(ex.st_dev),static_cast<uint64_t>(ex.st_ino),static_cast<uint64_t>(ns.st_dev),static_cast<uint64_t>(ns.st_ino)};
}
struct Bus {
    sd_bus* bus=nullptr;
    Bus(){
        int r=sd_bus_new(&bus);if(r<0)throw Failure("dbus","system bus allocation failed",-r);
        try{
            r=sd_bus_set_address(bus,"unix:path=/run/dbus/system_bus_socket");if(r<0)throw Failure("dbus","fixed bus address failed",-r);
            r=sd_bus_set_bus_client(bus,1);if(r<0)throw Failure("dbus","bus client setup failed",-r);
            r=sd_bus_set_method_call_timeout(bus,remaining());if(r<0)throw Failure("dbus","bus timeout setup failed",-r);
            r=sd_bus_start(bus);if(r<0)throw Failure("dbus","system bus start failed",-r);
            for(;;){
                check();r=sd_bus_is_ready(bus);if(r<0)throw Failure("dbus","bus readiness failed",-r);if(r>0)break;
                r=sd_bus_process(bus,nullptr);if(r<0)throw Failure("dbus","bus initialization failed",-r);
                if(r==0){r=sd_bus_wait(bus,remaining());if(r<0)throw Failure("dbus","bus initialization wait failed",-r);}
            }
        }catch(...){sd_bus_unref(bus);bus=nullptr;throw;}
    }
    ~Bus(){sd_bus_unref(bus);}
    void budget(){check();need(sd_bus_is_ready(bus)>0,"dbus","system bus no longer ready");int r=sd_bus_set_method_call_timeout(bus,remaining());if(r<0)throw Failure("dbus","bus timeout setup failed",-r);}
};
struct Message{sd_bus_message* value=nullptr;~Message(){sd_bus_message_unref(value);}};
struct BusError{sd_bus_error value{};~BusError(){sd_bus_error_free(&value);}};
std::string property(Bus& bus,const std::string& object,const char* interface,const char* name){
    bus.budget();BusError error;char* value=nullptr;int r=sd_bus_get_property_string(bus.bus,"org.freedesktop.systemd1",object.c_str(),interface,name,&error.value,&value);std::unique_ptr<char,decltype(&free)> held(value,free);
    if(r<0)throw Failure("dbus-property",std::string("property read failed: ")+name,-r);need(value&&strlen(value)<=512,"dbus-property","property size limit");return value;
}
Unit unit(Bus& bus,uint32_t uid){
    bus.budget();auto name="user@"+std::to_string(uid)+".service";BusError error;Message message;
    int r=sd_bus_call_method(bus.bus,"org.freedesktop.systemd1","/org/freedesktop/systemd1","org.freedesktop.systemd1.Manager","GetUnit",&error.value,&message.value,"s",name.c_str());
    if(r<0)throw Failure("dbus-unit","fixed GetUnit failed",-r);const char* path=nullptr;r=sd_bus_message_read(message.value,"o",&path);need(r>0&&path&&strlen(path)<=512,"dbus-unit","invalid unit object");std::string object(path);
    Unit result;result.id=property(bus,object,"org.freedesktop.systemd1.Unit","Id");result.active=property(bus,object,"org.freedesktop.systemd1.Unit","ActiveState");result.group=property(bus,object,"org.freedesktop.systemd1.Service","ControlGroup");
    bus.budget();r=sd_bus_get_property_trivial(bus.bus,"org.freedesktop.systemd1",object.c_str(),"org.freedesktop.systemd1.Service","MainPID",&error.value,'u',&result.pid);if(r<0)throw Failure("dbus-unit","MainPID read failed",-r);
    bus.budget();Message invocation;r=sd_bus_get_property(bus.bus,"org.freedesktop.systemd1",object.c_str(),"org.freedesktop.systemd1.Unit","InvocationID",&error.value,&invocation.value,"ay");if(r<0)throw Failure("dbus-unit","InvocationID read failed",-r);
    const void* bytes=nullptr;size_t size=0;r=sd_bus_message_read_array(invocation.value,'y',&bytes,&size);need(r>0&&size==16,"dbus-unit","invalid InvocationID array");result.invocation=hexBytes(static_cast<const unsigned char*>(bytes),size);requireUnit(result,uid);return result;
}
void waitSocket(short event){pollfd p{STDIN_FILENO,event,0};int r;do{r=poll(&p,1,static_cast<int>((remaining()+999)/1000));}while(r<0&&errno==EINTR);if(r<0)throw Failure("socket","poll failed",errno);need(r>0&&(p.revents&(event|POLLHUP))&&!(p.revents&(POLLERR|POLLNVAL)),"socket","socket deadline/error");check();}
std::string readRequest(){
    std::string bytes;std::array<char,65> buffer{};
    for(;;){waitSocket(POLLIN);ssize_t n=recv(STDIN_FILENO,buffer.data(),buffer.size(),MSG_DONTWAIT);if(n<0){if(errno==EAGAIN||errno==EINTR)continue;throw Failure("request","request receive failed",errno);}if(!n)return request(bytes);bytes.append(buffer.data(),n);need(bytes.size()<=64,"request","request limit");}
}
void writeResponse(const std::string& text){
    size_t done=0;while(done<text.size()){waitSocket(POLLOUT);ssize_t n=send(STDIN_FILENO,text.data()+done,text.size()-done,MSG_DONTWAIT|MSG_NOSIGNAL);if(n<0){if(errno==EAGAIN||errno==EINTR)continue;throw Failure("response","response send failed",errno);}need(n>0,"response","short response send");done+=n;}
}
}
int main(int argc,char**){
    end=Clock::now()+std::chrono::milliseconds(1500);std::string nonce;uint32_t uid=UINT32_MAX;
    try{
        need(argc==1&&getuid()==0&&geteuid()==0,"startup","fixed root service only");need(clearenv()==0,"startup","environment clear failed");
        sockaddr_un address{};socklen_t length=sizeof(address);need(getsockname(STDIN_FILENO,reinterpret_cast<sockaddr*>(&address),&length)==0&&address.sun_family==AF_UNIX&&length>offsetof(sockaddr_un,sun_path)&&length<=sizeof(address),"socket","AF_UNIX service socket required");
        need(std::string(address.sun_path,strnlen(address.sun_path,sizeof(address.sun_path)))==socketPath,"socket","fixed socket path required");
        int type=0;socklen_t size=sizeof(type);need(getsockopt(STDIN_FILENO,SOL_SOCKET,SO_TYPE,&type,&size)==0&&type==SOCK_STREAM,"socket","stream socket required");
        ucred peer{};size=sizeof(peer);need(getsockopt(STDIN_FILENO,SOL_SOCKET,SO_PEERCRED,&peer,&size)==0&&size==sizeof(peer),"peer","kernel peer credentials unavailable");uid=peer.uid;need(uid>0&&uid!=UINT32_MAX,"peer","invalid peer uid");nonce=readRequest();
        Snapshot snapshot; snapshot.uid=uid;snapshot.boot=bootId();
        {
            Bus bus;snapshot.unit=unit(bus,uid);snapshot.files=components(true);snapshot.process=managerProcess(snapshot.unit.pid);
            Fd own(open("/proc/self/ns/user",O_RDONLY|O_CLOEXEC));auto ns=info(own.value);requireProcess(snapshot.process,snapshot.unit,uid,snapshot.files[2],ns.st_dev,ns.st_ino);
            auto proc="/proc/"+std::to_string(snapshot.unit.pid);Fd dir(open(proc.c_str(),O_PATH|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC));requireMaps(procRead(dir.value,"maps",2097152),snapshot.files[3],snapshot.files[4]);
            need(managerProcess(snapshot.unit.pid)==snapshot.process,"manager","manager process changed");need(unit(bus,uid)==snapshot.unit,"unit","unit changed during observation");
            auto after=components(false);for(size_t i=0;i<5;i++){after[i].hash=snapshot.files[i].hash;need(after[i]==snapshot.files[i],"component","fixed component changed");}
            need(bootId()==snapshot.boot,"boot","boot changed");
        }
        if(closeError)throw Failure("close","read handle close failed",closeError);check();writeResponse(response(nonce,snapshot));return 0;
    }catch(const Failure& error){try{writeResponse(errorResponse(nonce,uid,error));}catch(...){ }return 1;}
    catch(const std::exception& error){try{writeResponse(errorResponse(nonce,uid,Failure("internal",std::string(error.what()).substr(0,512))));}catch(...){ }return 1;}
}
