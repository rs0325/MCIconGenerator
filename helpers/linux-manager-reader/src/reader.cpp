#include "reader.hpp"
#include <algorithm>
#include <charconv>
#include <iomanip>
#include <sstream>
#include <limits>

namespace mcicon {
static void require(bool value,const char* stage,const char* message) {
    if(!value)throw Failure(stage,message);
}
static bool hex(std::string_view value,size_t size) {
    return value.size()==size&&std::all_of(value.begin(),value.end(),[](char c){return (c>='0'&&c<='9')||(c>='a'&&c<='f');});
}
static uint64_t number(std::string_view value,int base=10) {
    uint64_t result=0;auto [end,error]=std::from_chars(value.data(),value.data()+value.size(),result,base);
    require(!value.empty()&&error==std::errc{}&&end==value.data()+value.size(),"parse","invalid number");return result;
}
static std::vector<std::string> words(std::string_view value) {
    std::istringstream input{std::string(value)};std::vector<std::string> result;std::string word;
    while(input>>word)result.push_back(word);return result;
}
std::string request(std::string_view bytes) {
    require(bytes.size()==40&&bytes.substr(0,7)=="MCIMR1 "&&bytes.back()=='\n'&&hex(bytes.substr(7,32),32),"request","invalid request");return std::string(bytes.substr(7,32));
}
uint64_t startTicks(std::string_view bytes,uint32_t pid) {
    auto left=bytes.find('('),right=bytes.rfind(')');require(left!=bytes.npos&&right>left&&right+2<bytes.size(),"stat","invalid process stat");
    auto prefix=words(bytes.substr(0,left));require(prefix.size()==1&&number(prefix[0])==pid,"stat","process pid mismatch");
    auto values=words(bytes.substr(right+2));require(values.size()>=20,"stat","short process stat");return number(values[19]);
}
std::array<uint32_t,4> uidValues(std::string_view bytes) {
    std::istringstream input{std::string(bytes)};std::string line;std::array<uint32_t,4> result{};bool found=false;
    while(std::getline(input,line))if(line.starts_with("Uid:")) {
        require(!found,"status","duplicate Uid");auto values=words(std::string_view(line).substr(4));require(values.size()==4,"status","invalid Uid");
        for(size_t i=0;i<4;i++){auto n=number(values[i]);require(n<=UINT32_MAX,"status","Uid overflow");result[i]=static_cast<uint32_t>(n);}found=true;
    }
    require(found,"status","missing Uid");return result;
}
std::string controlGroup(std::string_view bytes) {
    require(bytes.starts_with("0::")&&bytes.size()>4&&bytes.back()=='\n'&&bytes.find('\n')==bytes.size()-1,"cgroup","expected unified cgroup");
    return std::string(bytes.substr(3,bytes.size()-4));
}
void requireUnit(const Unit& unit,uint32_t uid) {
    require(uid>0&&uid!=UINT32_MAX,"peer","invalid peer uid");auto user="user@"+std::to_string(uid)+".service";
    auto group="/user.slice/user-"+std::to_string(uid)+".slice/"+user;
    require(unit.id==user&&unit.active=="active"&&unit.group==group&&unit.pid>1,"unit","fixed user unit mismatch");
    require(hex(unit.invocation,32)&&unit.invocation!=std::string(32,'0'),"unit","invalid invocation id");
}
void requireProcess(const Process& process,const Unit& unit,uint32_t uid,const File& manager,uint64_t nsDev,uint64_t nsIno) {
    require(process.pid==unit.pid&&std::all_of(process.uids.begin(),process.uids.end(),[uid](auto x){return x==uid;}),"manager","manager uid/pid mismatch");
    require(process.exe==paths[2]&&process.exeDev==manager.dev&&process.exeIno==manager.ino,"manager","manager executable mismatch");
    require(process.namespaceDev==nsDev&&process.namespaceIno==nsIno,"manager","manager user namespace mismatch");
    require(process.group==unit.group||process.group.starts_with(unit.group+"/"),"manager","manager cgroup mismatch");
}
void requireMaps(std::string_view bytes,const File& core,const File& shared) {
    std::array<bool,2> found{};std::array<const File*,2> files={&core,&shared};std::istringstream input{std::string(bytes)};std::string line;
    while(std::getline(input,line)) {
        std::istringstream fields(line);std::string address,permissions,offset,device,inode,path;
        if(!(fields>>address>>permissions>>offset>>device>>inode))continue;
        std::getline(fields,path);auto begin=path.find_first_not_of(" \t");if(begin==path.npos)continue;path.erase(0,begin);
        for(size_t i=0;i<2;i++)if(path==paths[i+3]||path==std::string(paths[i+3])+" (deleted)") {
            auto colon=device.find(':');require(path==paths[i+3]&&colon!=device.npos,"maps","deleted/malformed library mapping");auto dev=files[i]->dev;
            auto major=(dev>>8&0xfff)|(dev>>32&0xfffff000ULL),minor=(dev&0xff)|(dev>>12&0xffffff00ULL);
            require(number(device.substr(0,colon),16)==major&&number(device.substr(colon+1),16)==minor&&number(inode)==files[i]->ino,"maps","library mapping mismatch");found[i]=true;
        }
    }
    require(found[0]&&found[1],"maps","missing fixed library mapping");
}
static std::string jsonString(std::string_view text) {
    std::ostringstream out;out<<'"';
    for(unsigned char c:text) {
        if(c=='"'||c=='\\')out<<'\\'<<c;
        else if(c<32||c>=127)out<<"\\u00"<<std::hex<<std::setw(2)<<std::setfill('0')<<unsigned(c)<<std::dec;
        else out<<c;
    }
    out<<'"';return out.str();
}
std::string errorResponse(const std::string& nonce,uint32_t uid,const Failure& error) {
    return "{\"schemaVersion\":1,\"nonce\":"+jsonString(nonce)+",\"uid\":"+std::to_string(uid)+",\"complete\":false,\"error\":{\"stage\":"+jsonString(error.stage)+",\"message\":"+jsonString(error.what())+",\"errno\":"+std::to_string(error.native)+"}}\n";
}
std::string response(const std::string& nonce,const Snapshot& snapshot) {
    auto& unit=snapshot.unit;auto& process=snapshot.process;
    require(hex(nonce,32),"response","invalid nonce");requireUnit(unit,snapshot.uid);
    require(snapshot.boot.size()==36,"boot","invalid boot UUID");
    for(size_t i=0;i<36;i++)require(i==8||i==13||i==18||i==23?snapshot.boot[i]=='-':hex(std::string_view(snapshot.boot).substr(i,1),1),"boot","invalid boot UUID");
    std::ostringstream out;out<<"{\"schemaVersion\":1,\"nonce\":"<<jsonString(nonce)<<",\"uid\":"<<snapshot.uid<<",\"complete\":true,\"bootId\":"<<jsonString(snapshot.boot)<<",\"unit\":{\"id\":"<<jsonString(unit.id)<<",\"activeState\":"<<jsonString(unit.active)<<",\"controlGroup\":"<<jsonString(unit.group)<<",\"invocationId\":"<<jsonString(unit.invocation)<<"},\"manager\":{\"pid\":"<<process.pid<<",\"startTicks\":"<<process.start<<",\"uid\":"<<snapshot.uid<<",\"executable\":"<<jsonString(process.exe)<<",\"controlGroup\":"<<jsonString(process.group)<<",\"namespaceDev\":"<<process.namespaceDev<<",\"namespaceIno\":"<<process.namespaceIno<<"},\"files\":{";
    for(size_t i=0;i<5;i++) {
        const auto& f=snapshot.files[i];require(hex(f.hash,64),"response","invalid component hash");
        if(i)out<<',';out<<jsonString(roles[i])<<":{\"path\":"<<jsonString(paths[i])<<",\"dev\":"<<f.dev<<",\"ino\":"<<f.ino<<",\"size\":"<<f.size<<",\"uid\":"<<f.uid<<",\"mode\":"<<f.mode<<",\"mtimeNs\":"<<f.mtime<<",\"ctimeNs\":"<<f.ctime<<",\"sha256\":"<<jsonString(f.hash)<<'}';
    }
    out<<"}}\n";auto result=out.str();require(result.size()<=65536,"response","frame limit");return result;
}
}
