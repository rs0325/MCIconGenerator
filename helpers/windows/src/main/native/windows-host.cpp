// native Jobへの原子的な子登録と、Windows所有情報の読み取りだけを行う。
#define _WIN32_WINNT 0x0A00
#include <windows.h>
#include <winternl.h>
#include <sddl.h>
#include <objbase.h>
#include <iostream>
#include <string>
#include <vector>
#include <stdexcept>
#include <algorithm>
#include <cwctype>
#include <cstdio>

struct Handle {
    HANDLE value=nullptr;
    explicit Handle(HANDLE value=nullptr):value(value){}
    ~Handle(){if(value&&value!=INVALID_HANDLE_VALUE)CloseHandle(value);}
    Handle(const Handle&)=delete;
};
static void require(bool ok){if(!ok)throw std::runtime_error("Windows native APIに失敗しました");}
static std::string utf8(const std::wstring& value){if(value.empty())return {};int n=WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,value.data(),(int)value.size(),nullptr,0,nullptr,nullptr);require(n>0);std::string out(n,'\0');require(WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,value.data(),(int)value.size(),out.data(),n,nullptr,nullptr)==n);return out;}
static std::string json(const std::wstring& value){std::string out="\"";for(unsigned char c:utf8(value)){if(c=='\\'||c=='"'){out+='\\';out+=(char)c;}else if(c<32){char buffer[7];sprintf_s(buffer,"\\u%04x",c);out+=buffer;}else out+=(char)c;}return out+'"';}
static std::wstring guid(GUID value){wchar_t text[39];require(StringFromGUID2(value,text,39)==39);std::wstring result(text+1,36);std::transform(result.begin(),result.end(),result.begin(),towlower);return result;}
static ULONGLONG started(HANDLE process){FILETIME creation,exit,kernel,user;require(GetProcessTimes(process,&creation,&exit,&kernel,&user));ULARGE_INTEGER time;time.LowPart=creation.dwLowDateTime;time.HighPart=creation.dwHighDateTime;require(time.QuadPart>=116444736000000000ULL);return (time.QuadPart-116444736000000000ULL)/10000ULL;}
static std::wstring quoted(const std::wstring& value){if(!value.empty()&&value.find_first_of(L" \t\"")==std::wstring::npos)return value;std::wstring out=L"\"";unsigned slash=0;for(wchar_t c:value){if(c==L'\\'){slash++;continue;}if(c==L'"'){out.append(slash*2+1,L'\\');out+=c;}else{out.append(slash,L'\\');out+=c;}slash=0;}out.append(slash*2,L'\\');return out+L'"';}
static std::wstring regString(HKEY key,const wchar_t* name){DWORD type=0,size=0;require(RegQueryValueExW(key,name,nullptr,&type,nullptr,&size)==ERROR_SUCCESS&&type==REG_SZ&&size>=2&&size<=32768&&size%2==0);std::vector<wchar_t> value(size/2);require(RegQueryValueExW(key,name,nullptr,&type,(BYTE*)value.data(),&size)==ERROR_SUCCESS&&type==REG_SZ&&value.back()==0);std::wstring result(value.data(),value.size()-1);require(result.find(L'\0')==std::wstring::npos);return result;}
static DWORD regDword(HKEY key,const wchar_t* name){DWORD value=0,size=4,type=0;require(RegQueryValueExW(key,name,nullptr,&type,(BYTE*)&value,&size)==ERROR_SUCCESS&&type==REG_DWORD&&size==4);return value;}
struct Key {HKEY value=nullptr;~Key(){if(value)RegCloseKey(value);}};
static std::wstring bootId(){auto library=GetModuleHandleW(L"ntdll.dll");require(library!=nullptr);auto query=(LONG(WINAPI*)(ULONG,PVOID,ULONG,PULONG))GetProcAddress(library,"NtQuerySystemInformation");require(query!=nullptr);struct Boot {GUID id;ULONG firmware;ULONGLONG flags;} boot{};ULONG returned=0;require(query(90,&boot,sizeof(boot),&returned)>=0&&returned>=sizeof(GUID));return guid(boot.id);}
static std::wstring extended(const std::wstring& path){if(path.rfind(L"\\\\?\\",0)==0)return path;if(path.rfind(L"\\\\",0)==0)return L"\\\\?\\UNC\\"+path.substr(2);require(path.size()>2&&path[1]==L':');return L"\\\\?\\"+path;}
static std::wstring applicationPath(const std::wstring& path){bool drive=path.size()>2&&((path[0]>=L'A'&&path[0]<=L'Z')||(path[0]>=L'a'&&path[0]<=L'z'))&&path[1]==L':'&&(path[2]==L'\\'||path[2]==L'/');require(drive||path.rfind(L"\\\\",0)==0);return path;}
static void record(const std::wstring& path,const std::string& value){std::wstring full=extended(path);Handle file(CreateFileW(full.c_str(),GENERIC_WRITE,0,nullptr,CREATE_NEW,FILE_ATTRIBUTE_NORMAL,nullptr));require(file.value!=INVALID_HANDLE_VALUE);DWORD written=0;require(WriteFile(file.value,value.data(),(DWORD)value.size(),&written,nullptr)&&written==value.size()&&FlushFileBuffers(file.value));}
static void released(Handle& handle){require(CloseHandle(handle.value));handle.value=nullptr;}
static void accountingDiagnostic(const char* phase,DWORD childExit,const std::vector<DWORD>& tail,unsigned queries){
    std::cerr<<"nativeJob phase="<<phase<<" childExit="<<childExit<<" active=";for(size_t i=0;i<tail.size();i++){if(i)std::cerr<<',';std::cerr<<tail[i];}std::cerr<<" queries="<<queries<<'\n';
}
static DWORD activeProcesses(HANDLE job){JOBOBJECT_BASIC_ACCOUNTING_INFORMATION info{};require(QueryInformationJobObject(job,JobObjectBasicAccountingInformation,&info,sizeof(info),nullptr));return info.ActiveProcesses;}
static void observeJob(HANDLE job,HANDLE parent,ULONGLONG recoveryEnd) noexcept{try{
    constexpr unsigned limit=8;constexpr size_t logLimit=4096;
    std::string log="nativeJob observe nonAtomic=1\n";bool complete=true,allClosed=true,listComplete=false;unsigned listed=0,rows=0;ULONGLONG end=std::min<ULONGLONG>(GetTickCount64()+50,recoveryEnd);
    auto append=[&](const std::string& line){if(log.size()+line.size()+256>logLimit){complete=false;return false;}log+=line;if(line.rfind("pid=",0)==0||line.rfind("row=",0)==0)rows++;return true;};
    try{
        struct List {DWORD assigned,count;ULONG_PTR ids[limit];} list{};
        static_assert(offsetof(List,ids)==offsetof(JOBOBJECT_BASIC_PROCESS_ID_LIST,ProcessIdList));
        if(GetTickCount64()>=end||WaitForSingleObject(parent,0)!=WAIT_TIMEOUT)return;
        else if(!QueryInformationJobObject(job,JobObjectBasicProcessIdList,&list,sizeof(list),nullptr)){append("list=unknown error="+std::to_string(GetLastError())+"\n");complete=false;}
        else if(list.count>limit||list.assigned!=list.count){append("list=insufficient\n");complete=false;}
        else {listComplete=true;listed=list.count;for(DWORD i=0;i<list.count;i++){
            if(GetTickCount64()>=end||WaitForSingleObject(parent,0)!=WAIT_TIMEOUT)return;
            ULONG_PTR pid=list.ids[i];bool duplicate=false;for(DWORD j=0;j<i;j++)if(list.ids[j]==pid)duplicate=true;
            if(!pid||pid>MAXDWORD||duplicate){append("row=unknown-id\n");complete=false;continue;}
            std::string row="pid="+std::to_string(pid);Handle process(OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION|SYNCHRONIZE,FALSE,(DWORD)pid));
            if(!process.value){append(row+" open=unknown error="+std::to_string(GetLastError())+"\n");complete=false;continue;}
            BOOL member=FALSE;
            if(!IsProcessInJob(process.value,job,&member)||!member){row+=" member=unknown-or-false";complete=false;}
            else{
                row+=" member=1";FILETIME creation,exit,kernel,user;
                if(GetProcessTimes(process.value,&creation,&exit,&kernel,&user)){ULARGE_INTEGER start;start.LowPart=creation.dwLowDateTime;start.HighPart=creation.dwHighDateTime;row+=" start100ns="+std::to_string(start.QuadPart);}else{row+=" start=unknown";complete=false;}
                wchar_t image[128];DWORD length=128;
                if(QueryFullProcessImageNameW(process.value,0,image,&length))row+=" image="+json(std::wstring(image,length));else{DWORD imageError=GetLastError();row+=" image=unknown imageError="+std::to_string(imageError)+" imageCapacity=128 imageLength="+std::to_string(length);complete=false;}
                DWORD state=WaitForSingleObject(process.value,0);
                if(state==WAIT_TIMEOUT)row+=" wait=live";else if(state==WAIT_OBJECT_0)row+=" wait=exited";else{row+=" wait=unknown";complete=false;}
            }
            BOOL closed=CloseHandle(process.value);process.value={};if(!closed){row+=" close=unknown";complete=false;allClosed=false;}
            if(!append(row+"\n"))break;
        }
    }}catch(...){complete=false;allClosed=false;append("observation=exception\n");}
    if(GetTickCount64()>=end||WaitForSingleObject(parent,0)!=WAIT_TIMEOUT)return;
    try{if(allClosed)append("afterCloseActive="+std::to_string(activeProcesses(job))+"\n");else append("afterCloseActive=unknown-close\n");}catch(...){complete=false;append("afterCloseActive=unknown\n");}
    if(GetTickCount64()>=end||WaitForSingleObject(parent,0)!=WAIT_TIMEOUT)return;
    char footer[256];unsigned lines=(unsigned)std::count(log.begin(),log.end(),'\n')+1;
    int length=std::snprintf(footer,sizeof(footer),"nativeJob observeEnd rows=%010u listed=%010u lines=%010u bytes=%010u listComplete=%u observationComplete=%u outcome=unchanged-fail\n",rows,listed,lines,0U,listComplete?1:0,complete?1:0);
    if(length<=0||(size_t)length>=sizeof(footer))return;
    unsigned bytes=(unsigned)(log.size()+(size_t)length);
    length=std::snprintf(footer,sizeof(footer),"nativeJob observeEnd rows=%010u listed=%010u lines=%010u bytes=%010u listComplete=%u observationComplete=%u outcome=unchanged-fail\n",rows,listed,lines,bytes,listComplete?1:0,complete?1:0);
    if(length<=0||(size_t)length>=sizeof(footer))return;log.append(footer,(size_t)length);
    if(log.size()<=logLimit){DWORD written=0;WriteFile(GetStdHandle(STD_ERROR_HANDLE),log.data(),(DWORD)log.size(),&written,nullptr);}
}catch(...){}}

static DWORD remainingUntil(ULONGLONG end){ULONGLONG now=GetTickCount64();return now>=end?0:(DWORD)std::min<ULONGLONG>(end-now,2000);}
struct Completion {bool childKnown=false;DWORD childExit=0;const char* phase=nullptr;};
static bool liveParent(HANDLE parent){DWORD state=WaitForSingleObject(parent,0);require(state==WAIT_TIMEOUT||state==WAIT_OBJECT_0);return state==WAIT_TIMEOUT;}
static void terminateUntil(HANDLE job,ULONGLONG end){
    require(TerminateJobObject(job,125));std::vector<DWORD> tail;unsigned queries=0;
    for(unsigned i=0;i<200;i++){
        require(remainingUntil(end)>0);DWORD active=activeProcesses(job);queries++;if(tail.size()==16)tail.erase(tail.begin());tail.push_back(active);
        if(!active){require(remainingUntil(end)>0);accountingDiagnostic("terminated",125,tail,queries);return;}
        DWORD left=remainingUntil(end);if(!left)break;Sleep(std::min<DWORD>(10,left));
    }
    accountingDiagnostic("termination-unknown",125,tail,queries);throw std::runtime_error("Job終了未確認");
}
static int completeOwnedJob(HANDLE job,HANDLE parent,Handle& child,Handle& thread,ULONGLONG& end,HANDLE witness=0,bool bridge=false,Completion* completion=nullptr){
    HANDLE waits[]={parent,witness,child.value};DWORD count=witness?3:2;if(!witness)waits[1]=child.value;DWORD result=WaitForMultipleObjects(count,waits,FALSE,INFINITE);
    auto ownersLive=[&](){return liveParent(parent)&&(!witness||liveParent(witness));};
    auto completed=[&](const char* phase,int exit){if(completion)completion->phase=phase;return exit;};
    end=GetTickCount64()+2000;
    if(result<WAIT_OBJECT_0+count-1){released(thread);released(child);terminateUntil(job,end);return completed("owner-death-cleanup",125);}
    require(result==WAIT_OBJECT_0+count-1);DWORD exit=1;require(GetExitCodeProcess(child.value,&exit));if(completion){completion->childKnown=true;completion->childExit=exit;}released(thread);released(child);
    ULONGLONG drainEnd=end-1500;std::vector<DWORD> tail;unsigned queries=0;
    for(unsigned i=0;i<51;i++){
        if(!ownersLive()){terminateUntil(job,end);return completed("owner-death-cleanup",125);}
        if(!remainingUntil(drainEnd))break;
        DWORD active=activeProcesses(job);queries++;if(tail.size()==16)tail.erase(tail.begin());tail.push_back(active);
        if(queries==1)accountingDiagnostic("child-signalled",exit,{active},1);
        if(!ownersLive()){terminateUntil(job,end);return completed("owner-death-cleanup",125);}
        if(!remainingUntil(drainEnd))break;
        if(!active){accountingDiagnostic("drain-empty",exit,tail,queries);return completed("drain-empty",(int)exit);}
        DWORD left=remainingUntil(drainEnd);if(!left)break;
        DWORD state=WaitForSingleObject(parent,std::min<DWORD>(10,left));require(state==WAIT_TIMEOUT||state==WAIT_OBJECT_0);
        if(state==WAIT_OBJECT_0){terminateUntil(job,end);return completed("owner-death-cleanup",125);}
    }
    accountingDiagnostic("drain-expired",exit,tail,queries);
    if(bridge&&exit==0){if(!ownersLive()){terminateUntil(job,end);return completed("owner-death-cleanup",125);}terminateUntil(job,end);if(!ownersLive()||!remainingUntil(end))return 125;accountingDiagnostic("command-empty",exit,{0},1);return 0;}
    observeJob(job,parent,end);terminateUntil(job,end);return completed("residual-terminated",125);
}
static void recoverAfterError(HANDLE job,ULONGLONG& end) noexcept{
    if(!end)end=GetTickCount64()+2000;
    try{terminateUntil(job,end);}catch(...){const char text[]="nativeJob cleanup=unknown original-error-preserved\n";DWORD written=0;WriteFile(GetStdHandle(STD_ERROR_HANDLE),text,sizeof(text)-1,&written,nullptr);}
}
static int facts(){
    Handle token;require(OpenProcessToken(GetCurrentProcess(),TOKEN_QUERY,&token.value));DWORD size=0;GetTokenInformation(token.value,TokenUser,nullptr,0,&size);require(GetLastError()==ERROR_INSUFFICIENT_BUFFER&&size<65536);std::vector<BYTE> user(size);require(GetTokenInformation(token.value,TokenUser,user.data(),size,&size));LPWSTR sid=nullptr;require(ConvertSidToStringSidW(((TOKEN_USER*)user.data())->User.Sid,&sid));std::wstring sidText(sid);LocalFree(sid);
    auto library=GetModuleHandleW(L"ntdll.dll");require(library!=nullptr);auto query=(LONG(WINAPI*)(ULONG,PVOID,ULONG,PULONG))GetProcAddress(library,"NtQuerySystemInformation");auto version=(LONG(WINAPI*)(PRTL_OSVERSIONINFOW))GetProcAddress(library,"RtlGetVersion");require(query&&version);
    struct Boot {GUID id;ULONG firmware;ULONGLONG flags;} boot{};ULONG returned=0;require(query(90,&boot,sizeof(boot),&returned)>=0&&returned>=sizeof(GUID));RTL_OSVERSIONINFOW os{};os.dwOSVersionInfoSize=sizeof(os);require(version(&os)>=0);
    MEMORYSTATUSEX memory{};memory.dwLength=sizeof(memory);require(GlobalMemoryStatusEx(&memory));SYSTEM_INFO system{};GetNativeSystemInfo(&system);require(system.wProcessorArchitecture==PROCESSOR_ARCHITECTURE_AMD64);
    std::string rows="[";std::wstring defaultGuid;Key lxss;LSTATUS status=RegOpenKeyExW(HKEY_CURRENT_USER,L"Software\\Microsoft\\Windows\\CurrentVersion\\Lxss",0,KEY_READ,&lxss.value);require(status==ERROR_SUCCESS||status==ERROR_FILE_NOT_FOUND);
    if(status==ERROR_SUCCESS){
        DWORD type=0,bytes=0;status=RegQueryValueExW(lxss.value,L"DefaultDistribution",nullptr,&type,nullptr,&bytes);require(status==ERROR_SUCCESS||status==ERROR_FILE_NOT_FOUND);if(status==ERROR_SUCCESS){std::wstring raw=regString(lxss.value,L"DefaultDistribution");GUID id{};require(CLSIDFromString(raw.c_str(),&id)==S_OK);defaultGuid=guid(id);}
        for(DWORD index=0;;index++){wchar_t name[256];DWORD length=256;status=RegEnumKeyExW(lxss.value,index,name,&length,nullptr,nullptr,nullptr,nullptr);if(status==ERROR_NO_MORE_ITEMS)break;require(status==ERROR_SUCCESS);GUID id{};require(CLSIDFromString(name,&id)==S_OK);Key child;require(RegOpenKeyExW(lxss.value,name,0,KEY_READ,&child.value)==ERROR_SUCCESS);if(rows.size()>1)rows+=',';rows+="{\"guid\":"+json(guid(id))+",\"name\":"+json(regString(child.value,L"DistributionName"))+",\"basePath\":"+json(regString(child.value,L"BasePath"))+",\"version\":"+std::to_string(regDword(child.value,L"Version"))+"}";}
    }
    rows+=']';std::cout<<"{\"sid\":"<<json(sidText)<<",\"boot\":"<<json(guid(boot.id))<<",\"arch\":\"x86_64\",\"version\":\""<<os.dwMajorVersion<<'.'<<os.dwMinorVersion<<'.'<<os.dwBuildNumber<<"\",\"memoryBytes\":"<<memory.ullTotalPhys<<",\"cpus\":"<<system.dwNumberOfProcessors<<",\"defaultGuid\":"<<json(defaultGuid)<<",\"distributions\":"<<rows<<"}\n";return 0;
}
struct Startup {
    Handle parent;std::wstring base;std::string receipt;
    Startup(int argc,wchar_t** argv){
        require(argc>=7&&std::wstring(argv[5])==L"--");DWORD pid=std::stoul(argv[2]);ULONGLONG start=std::stoull(argv[3]);parent.value=OpenProcess(SYNCHRONIZE|PROCESS_QUERY_LIMITED_INFORMATION,FALSE,pid);require(parent.value&&started(parent.value)==start&&WaitForSingleObject(parent.value,0)==WAIT_TIMEOUT);
        base=argv[4];receipt="{\"parent\":"+std::to_string(pid)+",\"parentStarted\":"+std::to_string(start)+",\"pid\":"+std::to_string(GetCurrentProcessId())+",\"started\":"+std::to_string(started(GetCurrentProcess()))+",\"boot\":"+json(bootId())+"}";record(base+L".state.json",receipt);
    }
};
static int executeStarted(int argc,wchar_t** argv,Startup& startup,HANDLE witness=0,bool bridge=false,bool keeper=false,DWORD witnessPid=0,ULONGLONG witnessStart=0){
    auto& parent=startup.parent;auto& base=startup.base;auto& receipt=startup.receipt;
    Handle job(CreateJobObjectW(nullptr,nullptr));require(job.value!=nullptr);JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits{};limits.BasicLimitInformation.LimitFlags=JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;require(SetInformationJobObject(job.value,JobObjectExtendedLimitInformation,&limits,sizeof(limits)));
    HANDLE handles[3]{};Handle input,output,error;Handle* owners[]={&input,&output,&error};DWORD ids[]={STD_INPUT_HANDLE,STD_OUTPUT_HANDLE,STD_ERROR_HANDLE};for(int i=0;i<3;i++){HANDLE source=GetStdHandle(ids[i]);require(source&&source!=INVALID_HANDLE_VALUE);require(DuplicateHandle(GetCurrentProcess(),source,GetCurrentProcess(),&owners[i]->value,0,TRUE,DUPLICATE_SAME_ACCESS));handles[i]=owners[i]->value;}
    SIZE_T size=0;InitializeProcThreadAttributeList(nullptr,2,0,&size);std::vector<BYTE> bytes(size);auto attributes=(LPPROC_THREAD_ATTRIBUTE_LIST)bytes.data();require(InitializeProcThreadAttributeList(attributes,2,0,&size));
    try {
        require(UpdateProcThreadAttribute(attributes,0,PROC_THREAD_ATTRIBUTE_JOB_LIST,&job.value,sizeof(HANDLE),nullptr,nullptr));require(UpdateProcThreadAttribute(attributes,0,PROC_THREAD_ATTRIBUTE_HANDLE_LIST,handles,sizeof(handles),nullptr,nullptr));STARTUPINFOEXW info{};info.StartupInfo.cb=sizeof(info);info.StartupInfo.dwFlags=STARTF_USESTDHANDLES;info.StartupInfo.hStdInput=input.value;info.StartupInfo.hStdOutput=output.value;info.StartupInfo.hStdError=error.value;info.lpAttributeList=attributes;
        std::wstring command;for(int i=6;i<argc;i++){if(!command.empty())command+=L' ';command+=quoted(argv[i]);}require(command.size()<32767);PROCESS_INFORMATION process{};std::wstring application=applicationPath(argv[6]);require(CreateProcessW(application.c_str(),command.data(),nullptr,nullptr,TRUE,EXTENDED_STARTUPINFO_PRESENT|CREATE_UNICODE_ENVIRONMENT|CREATE_NO_WINDOW,nullptr,nullptr,&info.StartupInfo,&process));Handle child(process.hProcess),thread(process.hThread);DeleteProcThreadAttributeList(attributes);attributes=nullptr;
        ULONGLONG end=0;int exit;Completion completion;std::string client;try{if(keeper){client=receipt.substr(0,receipt.size()-1)+",\"clientPid\":"+std::to_string(process.dwProcessId)+",\"clientStarted\":"+std::to_string(started(child.value))+",\"corePid\":"+std::to_string(witnessPid)+",\"coreStarted\":"+std::to_string(witnessStart)+"}";record(base+L".client.json",client);}exit=completeOwnedJob(job.value,parent.value,child,thread,end,witness,bridge,keeper?&completion:nullptr);if(keeper){require(remainingUntil(end)>0&&completion.phase);std::string value="{\"schema\":1,\"client\":"+client+",\"phase\":\""+completion.phase+"\",\"childKnown\":"+(completion.childKnown?"true":"false")+",\"childExit\":"+(completion.childKnown?std::to_string(completion.childExit):"null")+",\"plannedGuardExit\":"+std::to_string(exit)+",\"jobEmpty\":true}";require(value.size()<=4096);record(base+L".completion.json",value);require(remainingUntil(end)>0);}}catch(...){recoverAfterError(job.value,end);throw;}record(base+L".done.json",receipt);return exit;
    }catch(...){if(attributes)DeleteProcThreadAttributeList(attributes);throw;}
}
static int execute(int argc,wchar_t** argv){Startup startup(argc,argv);return executeStarted(argc,argv,startup);}
static int wslExecute(int argc,wchar_t** argv,bool bridge){
    require(argc==17&&std::wstring(argv[5])==L"--"&&std::wstring(argv[8])==L"--");applicationPath(argv[4]);Startup startup(argc,argv);Handle witness;DWORD pid=0;ULONGLONG start=0;const char* reason="witness";
    try {
        pid=std::stoul(argv[6]);start=std::stoull(argv[7]);witness.value=OpenProcess(SYNCHRONIZE|PROCESS_QUERY_LIMITED_INFORMATION,FALSE,pid);require(witness.value&&started(witness.value)==start&&liveParent(witness.value));
        reason="child-argv";wchar_t system[32768];UINT size=GetSystemDirectoryW(system,32768);require(size>0&&size<32768);std::wstring expected=std::wstring(system,size)+L"\\wsl.exe";require(CompareStringOrdinal(expected.c_str(),-1,argv[9],-1,TRUE)==CSTR_EQUAL);
        require(std::wstring(argv[10])==L"--distribution"&&std::wstring(argv[12])==L"--user"&&std::wstring(argv[13])==L"root"&&std::wstring(argv[14])==L"--exec"&&std::wstring(argv[15])==L"/usr/bin/python3"&&std::wstring(argv[16])==L"/usr/lib/mcicon/guestbridge.py");
        std::wstring name(argv[11]);require(name.size()==31&&name.rfind(L"MCIcon-",0)==0&&name.find_first_not_of(L"0123456789abcdef",7)==std::wstring::npos);
    }catch(const std::exception&){
        ULONGLONG end=GetTickCount64()+2000;std::string args="[";for(int i=9;i<argc;i++){if(i>9)args+=',';args+=json(argv[i]);}args+=']';
        std::string value="{\"schema\":1,\"kind\":\"reject-no-job\",\"phase\":\"before-job-create\",\"mode\":\""+std::string(bridge?"exec-bridge":"exec-keeper")+"\",\"state\":"+startup.receipt+",\"reason\":\""+reason+"\",\"argv\":"+args+",\"jobCreated\":false,\"childCreated\":false,\"plannedGuardExit\":1}";
        require(value.size()<=4096&&remainingUntil(end)>0);record(startup.base+L".rejected.json",value);require(remainingUntil(end)>0);return 1;
    }
    std::vector<wchar_t*> normalized(argv,argv+6);normalized.insert(normalized.end(),argv+9,argv+argc);return executeStarted((int)normalized.size(),normalized.data(),startup,witness.value,bridge,!bridge,pid,start);
}
int wmain(int argc,wchar_t** argv){try{if(argc==2&&std::wstring(argv[1])==L"facts")return facts();if(argc>=2&&std::wstring(argv[1])==L"exec")return execute(argc,argv);if(argc>=2&&std::wstring(argv[1])==L"exec-bridge")return wslExecute(argc,argv,true);if(argc>=2&&std::wstring(argv[1])==L"exec-keeper")return wslExecute(argc,argv,false);throw std::runtime_error("固定native操作が必要です");}catch(const std::exception&){std::cerr<<"Windows native所有操作に失敗しました\n";return 1;}}
