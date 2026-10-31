#include "reader.hpp"
#include <functional>
#include <iostream>
#include <cassert>
using namespace mcicon;
static int cases=0;
static void reject(const std::function<void()>& call){try{call();}catch(const Failure&){cases++;return;}throw std::runtime_error("拒否条件が受理された");}
int main(){
    const std::string nonce(32,'a');assert(request("MCIMR1 "+nonce+"\n")==nonce);cases++;
    for(const auto& value:std::vector<std::string>{"", "MCIMR2 "+nonce+"\n", "MCIMR1 "+nonce+"\nuid=1000", "MCIMR1 "+std::string(32,'G')+"\n"})reject([&]{request(value);});
    auto stat="42 (name with ) inside) S";std::string bytes=stat;for(int i=0;i<18;i++)bytes+=" 0";bytes+=" 123\n";assert(startTicks(bytes,42)==123);cases++;
    reject([&]{startTicks(bytes,43);});reject([]{startTicks("42 (x) S 0",42);});
    auto uid=uidValues("Name: unrelated\nUid:\t1000 1000 1000 1000\nCapEff:\t0000000800000000\n");assert((uid==std::array<uint32_t,4>{1000,1000,1000,1000}));cases++;
    reject([]{uidValues("Uid: 1 2 3\n");});reject([]{uidValues("Uid: 1 1 1 1\nUid: 1 1 1 1\n");});
    assert(controlGroup("0::/user.slice/user-1000.slice/user@1000.service/init.scope\n")=="/user.slice/user-1000.slice/user@1000.service/init.scope");cases++;
    reject([]{controlGroup("0::/x\n1::/y\n");});
    Unit unit{"user@1000.service","active","/user.slice/user-1000.slice/user@1000.service",std::string(32,'b'),42};requireUnit(unit,1000);cases++;
    reject([&]{requireUnit(unit,1001);});reject([&]{requireUnit(unit,0);});
    File manager{2050,10,100,0,0100755,1,2,std::string(64,'c')};Process process{42,123,uid,paths[2],unit.group+"/init.scope",2050,10,4,50};requireProcess(process,unit,1000,manager,4,50);cases++;
    auto changed=process;changed.uids[2]=0;reject([&]{requireProcess(changed,unit,1000,manager,4,50);});
    changed=process;changed.exeIno++;reject([&]{requireProcess(changed,unit,1000,manager,4,50);});
    changed=process;changed.namespaceIno++;reject([&]{requireProcess(changed,unit,1000,manager,4,50);});
    changed=process;changed.group=unit.group+"-other";reject([&]{requireProcess(changed,unit,1000,manager,4,50);});
    File core=manager,shared=manager;core.ino=11;shared.ino=12;
    auto maps=std::string("0-1 r-xp 0 08:02 11 ")+paths[3]+"\n0-1 r--p 0 08:02 12 "+paths[4]+"\n";requireMaps(maps,core,shared);cases++;
    reject([&]{requireMaps(std::string("0-1 r-xp 0 08:02 11 ")+paths[3]+" (deleted)\n",core,shared);});
    auto replaced=shared;replaced.ino++;reject([&]{requireMaps(maps,core,replaced);});
    Snapshot snapshot{1000,"12345678-1234-1234-1234-123456789abc",unit,process,{manager,manager,manager,core,shared}};
    auto responseBytes=response(nonce,snapshot);assert(responseBytes.size()<65536&&responseBytes.find("\"complete\":true")!=std::string::npos&&responseBytes.find("peerPid")==std::string::npos);cases++;
    snapshot.boot[8]='a';reject([&]{response(nonce,snapshot);});
    auto failure=errorResponse(nonce,1000,Failure("maps","quoted\"\n",13));assert(failure.find("\"complete\":false")!=std::string::npos&&failure.find("\\u000a")!=std::string::npos);cases++;
    std::cout<<"reader core "<<cases<<"条件PASS\n";
}
