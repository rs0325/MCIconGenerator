#include <string>
#include <vector>
#include <set>
#include <algorithm>
#include <stdexcept>
#include <iostream>
#include <cstdint>
using DWORD=uint32_t;using ULONGLONG=unsigned long long;using HANDLE=uintptr_t;
constexpr DWORD WAIT_TIMEOUT=258,WAIT_OBJECT_0=0,WAIT_FAILED=0xffffffff;constexpr int FALSE=0;constexpr DWORD INFINITE=0xffffffff;constexpr DWORD STD_ERROR_HANDLE=0xfffffff4;
struct State {ULONGLONG now=100,parentEnds=~0ULL,queryCost=0,witnessEnds=~0ULL,terminationCost=0;bool witness=true,parent=true,parentWaitError=false,childExitError=false,queryError=false,killError=false,termNeverZero=false;DWORD childDelay=0,childExit=0;ULONGLONG outerCut=~0ULL;std::vector<DWORD> active{1,0};unsigned query=0,termQuery=0,kill=0,observed=0;bool terminated=false;std::set<HANDLE> closed;bool done=false;unsigned recoveredError=0;ULONGLONG observationCost=0,observationEnd=0;std::vector<std::string> phases;std::string completion;int saveFailure=0;ULONGLONG saveCost=0;};static State s;
static void require(bool value){if(!value)throw std::runtime_error("api-failure");}
static ULONGLONG GetTickCount64(){return s.now;}
static DWORD WaitForSingleObject(HANDLE parent,DWORD wait){require(parent==9||parent==10);if(parent==10){if(s.now>=s.witnessEnds)s.witness=false;return s.witness?WAIT_TIMEOUT:WAIT_OBJECT_0;}if(s.parentWaitError)return WAIT_FAILED;s.now+=wait;if(s.now>=s.outerCut)throw std::runtime_error("outer-cut");if(s.now>=s.parentEnds)s.parent=false;return s.parent?WAIT_TIMEOUT:WAIT_OBJECT_0;}
static DWORD WaitForMultipleObjects(DWORD n,HANDLE* handles,int,DWORD wait){require((n==2||n==3)&&handles[0]==9&&handles[n-1]==11);if(!s.parent)return WAIT_OBJECT_0;if(n==3&&!s.witness)return WAIT_OBJECT_0+1;if(s.childDelay>wait){s.now+=wait;return WAIT_TIMEOUT;}s.now+=s.childDelay;if(s.now>=s.parentEnds){s.parent=false;return WAIT_OBJECT_0;}return WAIT_OBJECT_0+n-1;}
static bool GetExitCodeProcess(HANDLE h,DWORD* exit){require(h==11);*exit=s.childExit;return !s.childExitError;}
struct Handle{HANDLE value;explicit Handle(HANDLE v):value(v){}};
static void released(Handle& h){require(h.value!=0);s.closed.insert(h.value);h.value=0;}
static DWORD activeProcesses(HANDLE job){require(job==8);if(s.terminated){s.now+=s.terminationCost;s.termQuery++;return s.termNeverZero?1:0;}require(s.closed.count(11)&&s.closed.count(12));if(s.queryError)throw std::runtime_error("query-original");s.now+=s.queryCost;unsigned i=std::min<unsigned>(s.query,(unsigned)s.active.size()-1);s.query++;return s.active[i];}
static bool TerminateJobObject(HANDLE job,DWORD exit){require(job==8&&exit==125);s.kill++;s.terminated=true;return !s.killError;}
static void Sleep(DWORD ms){require(ms<=10);s.now+=ms;}
static void accountingDiagnostic(const char* phase,DWORD,const std::vector<DWORD>&,unsigned){s.phases.emplace_back(phase);}
static HANDLE GetStdHandle(DWORD){return 7;}
static bool WriteFile(HANDLE,const void*,DWORD bytes,DWORD* written,void*){s.recoveredError++;*written=bytes;return true;}
static void observeJob(HANDLE job,HANDLE parent,ULONGLONG end){require(job==8&&parent==9);s.observed++;s.observationEnd=end;s.now+=s.observationCost;}
#include "native-completion.inc"
static int invoke(HANDLE witness=0,bool bridge=false){Handle child(11),thread(12);ULONGLONG end=0;try{int exit=completeOwnedJob(8,9,child,thread,end,witness,bridge);s.done=true;return exit;}catch(...){auto original=end;recoverAfterError(8,end);require(!original||end==original);throw;}}
static ULONGLONG started(HANDLE){return 23;}
static void record(const std::wstring& path,const std::string& value){
 if(path==L"record.completion.json"){s.now+=s.saveCost;if(s.saveFailure&&s.saveFailure!=4)throw std::runtime_error(s.saveFailure==1?"create-new":s.saveFailure==2?"partial-write":"flush");s.completion=value;}
 if(path==L"record.done.json"){if(s.saveFailure==4)throw std::runtime_error("done-flush");s.done=true;}
}
static int invokeRecorded(){std::wstring base=L"record";std::string receipt="{}";struct{DWORD dwProcessId=55;}process;Handle child(11),thread(12);bool keeper=true,bridge=false;HANDLE witness=10;DWORD witnessPid=42;ULONGLONG witnessStart=23;struct{HANDLE value;}job{8},parent{9};
#include "native-client.inc"
}
static void failed(const char* expected){bool threw=false;try{invoke();}catch(const std::runtime_error& e){threw=true;require(std::string(e.what())==expected);}require(threw);}
int main(){unsigned tests=0;
 s=State{};require(invoke()==0&&s.query==2&&s.kill==0&&s.now==110&&s.closed.size()==2);tests++;
 s=State{};s.childExit=7;require(invoke()==7&&s.kill==0);tests++;
 s=State{};s.active={0};require(invoke()==0&&s.now==100&&s.kill==0&&s.observed==0);tests++;
 s=State{};s.active={1};require(invoke()==125&&s.kill==1&&s.now==600&&s.termQuery==1&&s.observed==1&&s.observationEnd==2100);tests++;
 s=State{};s.parent=false;require(invoke()==125&&s.query==0&&s.kill==1&&s.now==100&&s.observed==0);tests++;
 s=State{};s.parentEnds=105;require(invoke()==125&&s.query==1&&s.kill==1&&s.now==110);tests++;
 s=State{};s.queryError=true;failed("query-original");require(s.kill==1&&s.now==100&&s.observed==0);tests++;
 s=State{};s.queryError=true;s.killError=true;failed("query-original");require(s.recoveredError==1);tests++;
 s=State{};s.childExitError=true;failed("api-failure");require(s.kill==1);tests++;
 s=State{};s.parentWaitError=true;failed("api-failure");require(s.kill==1);tests++;
 s=State{};s.active={1};s.termNeverZero=true;failed("api-failure");require(s.now==2100&&s.recoveredError==1&&s.kill==2&&!s.done);tests++;
 s=State{};s.active={0};s.queryCost=501;require(invoke()==125&&s.kill==1&&s.now==601);tests++;
 s=State{};s.active={1};s.outerCut=105;failed("outer-cut");require(s.kill==1&&!s.done);tests++;
 s=State{};s.childDelay=2000;s.active={0};require(invoke()==0&&s.now==2100);tests++;
 s=State{};s.active={0};s.queryCost=6;s.parentEnds=105;require(invoke()==125&&s.kill==1);tests++;
 s=State{};s.active={1};s.observationCost=50;require(invoke()==125&&s.observed==1&&s.observationEnd==2100&&s.kill==1&&s.now==650);tests++;
 s=State{};s.active={1};s.observationCost=50;s.termNeverZero=true;failed("api-failure");require(s.now==2100&&s.observed==1&&!s.done);tests++;
 s=State{};s.active={1};require(invoke(10,true)==0&&s.kill==1&&s.observed==0&&s.done);tests++;
 s=State{};s.active={1};s.childExit=7;require(invoke(10,true)==125&&s.kill==1&&s.observed==1);tests++;
 s=State{};s.witness=false;require(invoke(10,true)==125&&s.query==0&&s.kill==1);tests++;
 s=State{};s.active={1};s.witnessEnds=105;require(invoke(10,true)==125&&s.now==110&&s.kill==1);tests++;
 s=State{};s.active={1};s.witnessEnds=601;s.terminationCost=2;require(invoke(10,true)==125&&s.kill==1);tests++;
 s=State{};s.active={1};s.parentEnds=105;require(invoke(10,true)==125&&s.kill==1);tests++;
 s=State{};s.active={0};require(invoke(10,true)==0&&s.kill==0);tests++;
 s=State{};s.witness=false;require(invoke(10,false)==125&&s.kill==1&&s.query==0);tests++;
 s=State{};s.active={1};require(invokeRecorded()==125&&s.done&&s.termQuery==1);tests++;
 s=State{};s.queryError=true;try{invokeRecorded();require(false);}catch(const std::runtime_error& error){require(std::string(error.what())=="query-original");}require(!s.done&&s.termQuery==1);tests++;
 s=State{};s.active={1};s.termNeverZero=true;try{invokeRecorded();require(false);}catch(const std::runtime_error&){}require(!s.done&&s.now==2100);tests++;
 for(DWORD value:{0U,1U,125U,0xffffffffU}){s=State{};s.active={0};s.childExit=value;require(invokeRecorded()==(int)value&&s.done);require(s.completion.find("\"childExit\":"+std::to_string(value))!=std::string::npos&&s.completion.find("\"phase\":\"drain-empty\"")!=std::string::npos);tests++;}
 s=State{};s.parent=false;require(invokeRecorded()==125&&s.done&&s.completion.find("\"childKnown\":false,\"childExit\":null")!=std::string::npos&&s.completion.find("owner-death-cleanup")!=std::string::npos);tests++;
 s=State{};s.parentEnds=105;require(invokeRecorded()==125&&s.done&&s.completion.find("\"childKnown\":true")!=std::string::npos&&s.completion.find("owner-death-cleanup")!=std::string::npos);tests++;
 s=State{};s.active={1};require(invokeRecorded()==125&&s.done&&s.completion.find("residual-terminated")!=std::string::npos);tests++;
 for(int error:{1,2,3}){s=State{};s.active={0};s.saveFailure=error;try{invokeRecorded();require(false);}catch(const std::runtime_error& e){require(std::string(e.what())==(error==1?"create-new":error==2?"partial-write":"flush"));}require(!s.done&&s.completion.empty()&&s.kill==1);tests++;}
 s=State{};s.active={0};s.saveCost=2000;try{invokeRecorded();require(false);}catch(const std::runtime_error&){}require(!s.done&&!s.completion.empty()&&s.now==2100);tests++;
 s=State{};s.active={1};s.observationCost=1500;try{invokeRecorded();require(false);}catch(const std::runtime_error&){}require(!s.done&&s.completion.empty()&&s.now==2100);tests++;
 s=State{};s.active={0};s.saveFailure=4;try{invokeRecorded();require(false);}catch(const std::runtime_error& e){require(std::string(e.what())=="done-flush");}require(!s.done&&!s.completion.empty());tests++;
 s=State{};s.childExitError=true;try{invokeRecorded();require(false);}catch(const std::runtime_error&){}require(!s.done&&s.completion.empty());tests++;
 s=State{};s.active={1};s.killError=true;try{invokeRecorded();require(false);}catch(const std::runtime_error&){}require(!s.done&&s.completion.empty());tests++;
 std::cout<<tests<<" native回収条件 PASS\n";
}
