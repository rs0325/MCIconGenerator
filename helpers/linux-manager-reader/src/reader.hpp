#pragma once
#include <array>
#include <cstdint>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

namespace mcicon {
inline constexpr std::array<const char*,5> roles={"run","control","manager","core","shared"};
inline constexpr std::array<const char*,5> paths={"/usr/bin/systemd-run","/usr/bin/systemctl","/usr/lib/systemd/systemd","/usr/lib/x86_64-linux-gnu/systemd/libsystemd-core-255.so","/usr/lib/x86_64-linux-gnu/systemd/libsystemd-shared-255.so"};
inline constexpr const char* socketPath="/run/mcicon-manager-reader.sock";
struct Failure:std::runtime_error {
    std::string stage;int native;
    Failure(std::string stage,std::string message,int native=0):std::runtime_error(message),stage(std::move(stage)),native(native){}
};
struct File {
    uint64_t dev,ino,size;uint32_t uid,mode;int64_t mtime,ctime;std::string hash;
    bool operator==(const File&) const=default;
};
struct Unit {
    std::string id,active,group,invocation;uint32_t pid;
    bool operator==(const Unit&) const=default;
};
struct Process {
    uint32_t pid;uint64_t start;std::array<uint32_t,4> uids;std::string exe,group;uint64_t exeDev,exeIno,namespaceDev,namespaceIno;
    bool operator==(const Process&) const=default;
};
struct Snapshot {
    uint32_t uid;std::string boot;Unit unit;Process process;std::array<File,5> files;
};
std::string request(std::string_view bytes);
uint64_t startTicks(std::string_view bytes,uint32_t pid);
std::array<uint32_t,4> uidValues(std::string_view bytes);
std::string controlGroup(std::string_view bytes);
void requireUnit(const Unit&,uint32_t uid);
void requireProcess(const Process&,const Unit&,uint32_t uid,const File& manager,uint64_t nsDev,uint64_t nsIno);
void requireMaps(std::string_view bytes,const File& core,const File& shared);
std::string response(const std::string& nonce,const Snapshot&);
std::string errorResponse(const std::string& nonce,uint32_t uid,const Failure&);
}
