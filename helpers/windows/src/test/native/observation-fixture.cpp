#include <string>
#include <algorithm>
#include <cstdio>
#include <vector>
#include <set>
#include <stdexcept>
#include <cstddef>
#include <cstdint>
#include <iostream>
using DWORD=uint32_t;using ULONGLONG=unsigned long long;using ULONG_PTR=uintptr_t;using HANDLE=uintptr_t;using BOOL=int;
constexpr DWORD WAIT_TIMEOUT=258,WAIT_OBJECT_0=0,WAIT_FAILED=0xffffffff,MAXDWORD=0xffffffff;
constexpr DWORD PROCESS_QUERY_LIMITED_INFORMATION=0x1000,SYNCHRONIZE=0x100000,STD_ERROR_HANDLE=0xfffffff4;
constexpr BOOL FALSE=0;constexpr int JobObjectBasicProcessIdList=3;
struct JOBOBJECT_BASIC_PROCESS_ID_LIST{DWORD a,b;ULONG_PTR ProcessIdList[1];};
struct FILETIME{DWORD dwLowDateTime,dwHighDateTime;};union ULARGE_INTEGER{struct{uint32_t LowPart,HighPart;};uint64_t QuadPart;};
struct State{bool listOk=true,member=true,parentLive=true,parentUnknown=false,openOk=true,closeOk=true;unsigned assigned=1,count=1;DWORD wait=WAIT_TIMEOUT;unsigned listCalls=0,accounting=0,writes=0;bool endOnClose=false,endOnAccounting=false,imageOk=true,endOnImage=false,deadlineOnImage=false,longImage=false,shortWrite=false,writeFail=false,timesOk=true,memberOk=true,accountingError=false,throwTick=false,throwAfterClose=false;DWORD imageError=122,imageLength=128,lastError=0;unsigned imageCalls=0;bool accountingWhileOpen=false;ULONGLONG ticks=0,tickStep=1;std::set<HANDLE> opened;std::string output;};static State s;
static DWORD GetLastError(){return s.lastError;}static ULONGLONG GetTickCount64(){if(s.throwTick)throw std::runtime_error("tick");auto value=s.ticks;s.ticks+=s.tickStep;return value;}
static DWORD WaitForSingleObject(HANDLE h,DWORD ms){if(ms)throw std::runtime_error("nonzero wait");s.lastError=999;return h==999?(s.parentUnknown?WAIT_FAILED:s.parentLive?WAIT_TIMEOUT:WAIT_OBJECT_0):s.wait;}
static BOOL QueryInformationJobObject(HANDLE job,int kind,void* ptr,DWORD,void*){if(job!=888||kind!=JobObjectBasicProcessIdList)throw std::runtime_error("wrong job");s.listCalls++;auto p=(JOBOBJECT_BASIC_PROCESS_ID_LIST*)ptr;p->a=s.assigned;p->b=s.count;for(unsigned i=0;i<s.count&&i<8;i++)p->ProcessIdList[i]=i+1;return s.listOk;}
static HANDLE OpenProcess(DWORD access,BOOL inherit,DWORD pid){if(access!=(PROCESS_QUERY_LIMITED_INFORMATION|SYNCHRONIZE)||inherit)throw std::runtime_error("rights");if(!s.openOk)return 0;s.opened.insert(pid+100);return pid+100;}
static BOOL IsProcessInJob(HANDLE,HANDLE job,BOOL* m){if(job!=888)throw std::runtime_error("wrong job");*m=s.member;return s.memberOk;}
static BOOL GetProcessTimes(HANDLE h,FILETIME* a,FILETIME*,FILETIME*,FILETIME*){a->dwLowDateTime=h;a->dwHighDateTime=0;return s.timesOk;}
static BOOL QueryFullProcessImageNameW(HANDLE,DWORD,wchar_t* out,DWORD* length){s.imageCalls++;if(*length!=128)throw std::runtime_error("image capacity changed");if(s.endOnImage)s.parentLive=false;if(s.deadlineOnImage)s.ticks=100;if(!s.imageOk){s.lastError=s.imageError;*length=s.imageLength;return 0;}std::wstring v=s.longImage?std::wstring(127,L'\1'):L"C:\\fixed.exe";v.copy(out,v.size());*length=(DWORD)v.size();return 1;}
static BOOL CloseHandle(HANDLE h){s.opened.erase(h);if(s.throwAfterClose)s.throwTick=true;if(s.endOnClose)s.parentLive=false;return s.closeOk;}
struct Handle{HANDLE value;explicit Handle(HANDLE h):value(h){}~Handle(){if(value)CloseHandle(value);}};
static DWORD activeProcesses(HANDLE){s.accounting++;s.accountingWhileOpen=!s.opened.empty();if(s.endOnAccounting)s.parentLive=false;if(s.accountingError)throw std::runtime_error("accounting");return 0;}
static std::string json(const std::wstring& v){std::string out="\"";for(wchar_t c:v){if(c==92||c==34){out+=(char)92;out+=(char)c;}else if(c<32){char b[7];std::snprintf(b,sizeof(b),"\\u%04x",(unsigned)c);out+=b;}else out+=(char)c;}return out+'"';}
static HANDLE GetStdHandle(DWORD){return 7;}static BOOL WriteFile(HANDLE,const void* data,DWORD bytes,DWORD* written,void*){s.writes++;DWORD size=s.writeFail?0:s.shortWrite?bytes/2:bytes;s.output.append((const char*)data,size);*written=size;return !s.writeFail;}
#include "native-observation.inc"
static void require(bool ok){if(!ok)throw std::runtime_error("fixture assertion");}
int main(){
 s=State{};observeJob(888,999,1000);require(s.output.find("wait=live")!=std::string::npos&&s.listCalls==1&&s.accounting==1&&!s.accountingWhileOpen&&s.opened.empty());std::cout<<s.output;
 s=State{};s.wait=WAIT_OBJECT_0;observeJob(888,999,1000);require(s.output.find("wait=exited")!=std::string::npos);
 s=State{};s.wait=WAIT_FAILED;observeJob(888,999,1000);require(s.output.find("wait=unknown")!=std::string::npos&&s.output.find("observationComplete=0")!=std::string::npos);
 s=State{};s.member=false;observeJob(888,999,1000);require(s.output.find("member=unknown-or-false")!=std::string::npos&&s.output.find("wait=live")==std::string::npos);
 s=State{};s.assigned=9;s.count=8;observeJob(888,999,1000);require(s.listCalls==1&&s.output.find("list=insufficient")!=std::string::npos&&s.opened.empty());
 s=State{};s.listOk=false;observeJob(888,999,1000);require(s.output.find("list=unknown")!=std::string::npos);
 s=State{};s.openOk=false;observeJob(888,999,1000);require(s.output.find("open=unknown")!=std::string::npos);
 s=State{};s.closeOk=false;observeJob(888,999,1000);require(s.accounting==0&&s.output.find("unknown-close")!=std::string::npos);
 s=State{};s.parentLive=false;observeJob(888,999,1000);require(s.listCalls==0&&s.accounting==0&&s.writes==0&&s.opened.empty());
 s=State{};s.assigned=8;s.count=8;observeJob(888,999,1000);require(s.output.size()<=4096&&s.listCalls==1&&s.opened.empty());
 s=State{};s.tickStep=100;observeJob(888,999,1000);require(s.listCalls==0&&s.accounting==0&&s.writes==0&&s.opened.empty());
 s=State{};s.endOnClose=true;observeJob(888,999,1000);require(s.listCalls==1&&s.accounting==0&&s.writes==0&&s.opened.empty());
 s=State{};s.parentUnknown=true;observeJob(888,999,1000);require(s.listCalls==0&&s.accounting==0&&s.writes==0&&s.opened.empty());
 s=State{};s.endOnAccounting=true;observeJob(888,999,1000);require(s.accounting==1&&s.writes==0&&s.opened.empty());
 for(DWORD code:{122U,5U,87U,0U})for(DWORD wait:{WAIT_TIMEOUT,WAIT_OBJECT_0}){
  s=State{};s.imageOk=false;s.imageError=code;s.imageLength=128;s.wait=wait;observeJob(888,999,1000);
  require(s.imageCalls==1&&s.opened.empty()&&s.accounting==1&&s.output.find("image=unknown imageError="+std::to_string(code)+" imageCapacity=128 imageLength=128")!=std::string::npos&&s.output.find("observationComplete=0")!=std::string::npos);std::cout<<s.output;
 }
 s=State{};s.imageOk=false;s.imageLength=MAXDWORD;observeJob(888,999,1000);require(s.output.find("imageLength=4294967295")!=std::string::npos&&s.imageCalls==1&&s.output.size()<=4096);
 s=State{};s.imageOk=false;s.assigned=8;s.count=8;observeJob(888,999,1000);require(s.imageCalls==8&&s.output.size()<=4096&&s.opened.empty()&&s.output.find("observationComplete=0")!=std::string::npos);
 s=State{};s.imageOk=false;s.endOnImage=true;observeJob(888,999,1000);require(s.imageCalls==1&&s.opened.empty()&&s.accounting==0&&s.writes==0);
 s=State{};s.imageOk=false;s.deadlineOnImage=true;observeJob(888,999,1000);require(s.imageCalls==1&&s.opened.empty()&&s.accounting==0&&s.writes==0);
 s=State{};s.ticks=100;observeJob(888,999,100);require(s.listCalls==0&&s.accounting==0&&s.writes==0&&s.opened.empty());
 s=State{};s.tickStep=5;observeJob(888,999,8);require(s.listCalls<=1&&s.accounting==0&&s.writes==0&&s.opened.empty());
 s=State{};s.memberOk=false;observeJob(888,999,1000);require(s.imageCalls==0&&s.opened.empty()&&s.output.find("member=unknown-or-false")!=std::string::npos);
 s=State{};s.timesOk=false;observeJob(888,999,1000);require(s.output.find("start=unknown")!=std::string::npos&&s.output.find("observationComplete=0")!=std::string::npos);
 s=State{};s.accountingError=true;observeJob(888,999,1000);require(s.output.find("afterCloseActive=unknown")!=std::string::npos&&s.opened.empty());
 s=State{};s.longImage=true;s.assigned=8;s.count=8;observeJob(888,999,1000);require(s.output.size()<=4096&&s.output.find("observationComplete=0")!=std::string::npos&&s.opened.empty());std::cout<<"FRAME_MAX_BEGIN\n"<<s.output<<"FRAME_MAX_END\n";
 s=State{};s.shortWrite=true;observeJob(888,999,1000);require(s.output.find("nativeJob observeEnd")==std::string::npos&&s.opened.empty());
 s=State{};s.writeFail=true;observeJob(888,999,1000);require(s.output.empty()&&s.opened.empty());
 s=State{};s.throwTick=true;s.parentLive=false;observeJob(888,999,1000);require(s.listCalls==0&&s.accounting==0&&s.writes==0&&s.opened.empty());
 s=State{};s.throwAfterClose=true;observeJob(888,999,1000);require(s.listCalls==1&&s.imageCalls==1&&s.accounting==0&&s.writes==0&&s.opened.empty());
 std::cout<<"36 direct observer cases PASS\n";

}
