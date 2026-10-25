#include <string>
#include <vector>
#include <stdexcept>
#include <iostream>
#include <cstdint>
using HANDLE=uintptr_t;
constexpr bool TRUE=true;
constexpr unsigned EXTENDED_STARTUPINFO_PRESENT=0x80000,CREATE_UNICODE_ENVIRONMENT=0x400,CREATE_NO_WINDOW=0x8000000;
struct State {unsigned calls=0,closed=0;bool deleted=false;std::wstring application,command;};static State s;
static void require(bool value){if(!value)throw std::runtime_error("fixture-failure");}
struct PROCESS_INFORMATION {HANDLE hProcess=0,hThread=0;};
struct Handle {HANDLE value;explicit Handle(HANDLE v):value(v){}~Handle(){s.closed++;}};
struct Startup {int StartupInfo=0;};
static bool CreateProcessW(const wchar_t* application,wchar_t* command,void*,void*,bool inherit,unsigned flags,void* environment,void* directory,int*,PROCESS_INFORMATION* process){
    require(inherit&&flags==(EXTENDED_STARTUPINFO_PRESENT|CREATE_UNICODE_ENVIRONMENT|CREATE_NO_WINDOW)&&environment==nullptr&&directory==nullptr);
    s.calls++;s.application=application;s.command=command;process->hProcess=1;process->hThread=2;return true;
}
static void DeleteProcThreadAttributeList(void*){s.deleted=true;}
#include "native-application.inc"
static void invoke(const std::wstring& path){
    std::vector<std::wstring> values={L"guard",L"exec",L"1",L"1",L"record",L"--",path,L"--version"};
    std::vector<wchar_t*> argv;for(auto& value:values)argv.push_back(value.data());int argc=(int)argv.size();Startup info;void* attributes=(void*)1;
#include "native-create.inc"
    require(attributes==nullptr);
}
int main(){unsigned tests=0;
    std::vector<std::pair<std::wstring,std::wstring>> valid={
        {L"C:\\jdk\\bin\\java.exe",L"C:\\jdk\\bin\\java.exe"},
        {L"C:\\Program Files\\jdk\\bin\\java.exe",L"\"C:\\Program Files\\jdk\\bin\\java.exe\""},
        {L"C:\\日本語\\java.exe",L"C:\\日本語\\java.exe"},
        {L"\\\\server\\share\\java.exe",L"\\\\server\\share\\java.exe"},
        {L"\\\\?\\C:\\jdk\\java.exe",L"\\\\?\\C:\\jdk\\java.exe"},
        {L"\\\\?\\UNC\\server\\share\\java.exe",L"\\\\?\\UNC\\server\\share\\java.exe"},
        {L"C:\\"+std::wstring(300,L'a')+L"\\java.exe",L"C:\\"+std::wstring(300,L'a')+L"\\java.exe"}
    };
    for(const auto& row:valid){s=State{};invoke(row.first);require(s.calls==1&&s.application==row.first&&s.command==row.second+L" --version"&&s.deleted&&s.closed==2);tests++;}
    for(const auto& path:std::vector<std::wstring>{L"java.exe",L"sub\\java.exe",L"C:java.exe",L"\\java.exe",L"/java.exe",L"",L"1:\\java.exe"}){
        s=State{};bool failed=false;try{invoke(path);}catch(const std::runtime_error&){failed=true;}require(failed&&s.calls==0);tests++;
    }
    require(extended(L"C:\\record")==L"\\\\?\\C:\\record");tests++;
    require(extended(L"\\\\server\\share\\record")==L"\\\\?\\UNC\\server\\share\\record");tests++;
    require(extended(L"\\\\?\\C:\\record")==L"\\\\?\\C:\\record");tests++;
    std::cout<<tests<<" native起動path条件 PASS\n";
}
