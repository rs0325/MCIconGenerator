#include <string>
#include <vector>
#include <stdexcept>
#include <cwctype>
#include <iostream>
using DWORD=unsigned long;using ULONGLONG=unsigned long long;using UINT=unsigned;using HANDLE=int;
constexpr int SYNCHRONIZE=1,PROCESS_QUERY_LIMITED_INFORMATION=2,FALSE=0,TRUE=1,CSTR_EQUAL=2,WAIT_TIMEOUT=258;
static bool alive=true,recordFail=false,parentAlive=true,postJobFail=false;static ULONGLONG clockValue=0;static int rejectWrites=0,stateWrites=0;static ULONGLONG actualStart=23;static int calls=0,recovered=0,completed=0;static bool rejectFail=false,rejectEnd=false;static std::wstring saved;static std::string body;
struct Handle{HANDLE value;explicit Handle(HANDLE value=0):value(value){}};
static void require(bool value){if(!value)throw std::runtime_error("拒否");}
static HANDLE OpenProcess(int,int,DWORD pid){return pid==42?9:pid==1?11:0;}
static ULONGLONG started(HANDLE value){return value==9?actualStart:value==11?2:101;}
static DWORD GetCurrentProcessId(){return 100;}
static HANDLE GetCurrentProcess(){return 77;}
static int WaitForSingleObject(HANDLE,int){return parentAlive?WAIT_TIMEOUT:0;}
static ULONGLONG GetTickCount64(){return clockValue;}
static std::wstring bootId(){return L"11111111-1111-1111-1111-111111111111";}
static std::string json(const std::wstring& text){std::string out="\"";for(wchar_t c:text){if(c==L'\\'||c==L'\"')out+='\\';out+=(char)c;}return out+'\"';}
static std::wstring applicationPath(const std::wstring& path){require(path.size()>2&&path[1]==L':'&&path[2]==L'\\');return path;}
static void record(const std::wstring& path,const std::string& value){saved=path;body=value;if(recordFail||(rejectFail&&path.find(L".rejected.json")!=std::wstring::npos))throw std::runtime_error("保存失敗");if(rejectEnd&&path.find(L".rejected.json")!=std::wstring::npos)clockValue+=2000;if(path.find(L".state.json")!=std::wstring::npos)stateWrites++;if(path.find(L".rejected.json")!=std::wstring::npos)rejectWrites++;}
#include "native-startup.inc"
static bool liveParent(HANDLE){return alive;}
static UINT GetSystemDirectoryW(wchar_t* output,UINT){std::wstring value=L"C:\\Windows\\System32";value.copy(output,value.size());return value.size();}
static int CompareStringOrdinal(const wchar_t* a,int,const wchar_t* b,int,int){std::wstring x(a),y(b);for(auto& c:x)c=std::towlower(c);for(auto& c:y)c=std::towlower(c);return x==y?CSTR_EQUAL:0;}
static int executeStarted(int argc,wchar_t** argv,Startup& startup,HANDLE witness=0,bool bridge=false,bool keeper=false,DWORD pid=0,ULONGLONG start=0){require(startup.parent.value==11);if(postJobFail)throw std::runtime_error("Job以後の失敗");require(argc==14&&std::wstring(argv[6])==L"C:\\Windows\\System32\\wsl.exe"&&witness==9&&bridge!=keeper&&pid==42&&start==23);calls++;return 7;}
static int execute(int,wchar_t**){return 0;}
static int facts(){return 0;}
static int remainingUntil(ULONGLONG end){return clockValue<end?1:0;}
#include "native-wsl.inc"
#include "native-completion-type.inc"
static int completeOwnedJob(HANDLE,HANDLE,Handle&,Handle&,ULONGLONG& end,HANDLE,bool,Completion* value){end=2000;completed++;value->phase="drain-empty";value->childKnown=true;value->childExit=0;return 0;}
static void recoverAfterError(HANDLE,ULONGLONG&){recovered++;}
static int client(){std::wstring base=L"record";std::string receipt="{\"pid\":1}";struct{DWORD dwProcessId=55;}process;Handle child(4),thread(5);bool keeper=true,bridge=false;HANDLE witness=9;DWORD witnessPid=42;ULONGLONG witnessStart=23;struct{HANDLE value=3;}job,parent;
#include "native-client.inc"
}
static std::vector<std::wstring> valid(){return {L"host",L"exec-bridge",L"1",L"2",L"C:\\record",L"--",L"42",L"23",L"--",L"C:\\Windows\\System32\\wsl.exe",L"--distribution",L"MCIcon-0123456789abcdef01234567",L"--user",L"root",L"--exec",L"/usr/bin/python3",L"/usr/lib/mcicon/guestbridge.py"};}
static int invoke(std::vector<std::wstring> args,bool bridge=true){std::vector<wchar_t*> argv;for(auto& arg:args)argv.push_back(arg.data());return wslExecute(argv.size(),argv.data(),bridge);}
int main(){int count=0;auto accept=[&](bool value){require(value);count++;};auto reject=[&](std::vector<std::wstring> args){int before=calls;try{accept(invoke(args)==1);}catch(const std::runtime_error&){}accept(calls==before);};
accept(invoke(valid())==7);accept(invoke(valid(),false)==7);
for(auto [index,value]:std::vector<std::pair<int,std::wstring>>{{5,L"x"},{8,L"x"},{9,L"C:\\other\\wsl.exe"},{10,L"--exec"},{11,L"MCIcon-0123456789ABCDEF01234567"},{12,L"--other"},{13,L"user"},{14,L"--shell"},{15,L"/bin/sh"},{16,L"/tmp/bridge"},{6,L"43"},{7,L"24"}}){auto args=valid();args[index]=value;reject(args);}auto shortArgs=valid();shortArgs.pop_back();reject(shortArgs);alive=false;reject(valid());alive=true;
auto badMode=valid();badMode[1]=L"exec-other";std::vector<wchar_t*> mode;for(auto& arg:badMode)mode.push_back(arg.data());accept(wmain(mode.size(),mode.data())==1);

alive=false;int rejected=rejectWrites;accept(invoke(valid())==1&&rejectWrites==rejected+1&&saved==L"C:\\record.rejected.json");accept(body.find("\"kind\":\"reject-no-job\"")!=std::string::npos&&body.find("\"reason\":\"witness\"")!=std::string::npos&&body.find("\"jobCreated\":false")!=std::string::npos&&body.find("\"childCreated\":false")!=std::string::npos);alive=true;
postJobFail=true;rejected=rejectWrites;try{invoke(valid());throw std::logic_error("Job後例外無視");}catch(const std::runtime_error&){}accept(rejectWrites==rejected);postJobFail=false;
parentAlive=false;rejected=rejectWrites;try{invoke(valid());throw std::logic_error("親認可無視");}catch(const std::runtime_error&){}accept(rejectWrites==rejected);parentAlive=true;
recordFail=true;rejected=rejectWrites;try{invoke(valid());throw std::logic_error("state保存失敗無視");}catch(const std::runtime_error&){}accept(rejectWrites==rejected);recordFail=false;
alive=false;rejectFail=true;try{invoke(valid());throw std::logic_error("拒否保存失敗無視");}catch(const std::runtime_error&){}accept(calls==2);rejectFail=false;
rejectEnd=true;try{invoke(valid());throw std::logic_error("保存後期限無視");}catch(const std::runtime_error&){}accept(calls==2);rejectEnd=false;clockValue=0;
auto huge=valid();huge[16]=std::wstring(5000,L'x');alive=true;rejected=rejectWrites;try{invoke(huge);throw std::logic_error("拒否上限無視");}catch(const std::runtime_error&){}accept(rejectWrites==rejected);
accept(client()==0&&completed==1&&recovered==0&&saved==L"record.done.json");accept(body=="{\"pid\":1}");recordFail=true;try{client();throw std::logic_error("保存失敗無視");}catch(const std::runtime_error&){}accept(recovered==1&&completed==1);
std::cout<<"WSL固定入口 "<<count<<"条件 PASS\n";}
