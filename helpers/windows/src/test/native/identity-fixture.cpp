// 認証済みreaderのprotocol負例。VMや外部サービスは起動しない。
#include <windows.h>
#include <iostream>
#include <string>

int wmain(int argc,wchar_t** argv){
    if(argc==2&&std::wstring(argv[1])==L"--hold"){Sleep(10000);return 0;}
    if(argc==3&&std::wstring(argv[1])==L"--monitor"){
        ULONGLONG end=GetTickCount64()+600000;
        while(GetFileAttributesW(argv[2])==INVALID_FILE_ATTRIBUTES&&GetTickCount64()<end)Sleep(10);
        return GetFileAttributesW(argv[2])==INVALID_FILE_ATTRIBUTES?7:0;
    }
    if(argc!=3||std::wstring(argv[1])!=L"file-identity")return 2;
    HANDLE file=CreateFileW(argv[2],GENERIC_READ,FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE,nullptr,OPEN_EXISTING,0,nullptr);
    if(file==INVALID_HANDLE_VALUE)return 3;
    char bytes[64]{};DWORD size=0;BOOL ok=ReadFile(file,bytes,63,&size,nullptr);CloseHandle(file);if(!ok)return 4;std::string mode(bytes,size);
    if(mode=="timeout"){Sleep(10000);return 0;}
    if(mode=="huge"){std::cout<<std::string(65536,'x');return 0;}
    if(mode=="nonzero"){std::cout<<"abc";std::cerr<<"{\"schemaVersion\":1,\"phase\":\"file-identity\",\"kind\":\"WIN32\",\"operation\":\"CreateFileW\",\"win32Code\":32}";return 23;}
    if(mode=="contract"){std::cerr<<"{\"schemaVersion\":1,\"phase\":\"file-identity\",\"kind\":\"CONTRACT\",\"operation\":\"FileStandardInfo\",\"win32Code\":null}";return 1;}
    if(mode=="stderr-secret"){std::cerr<<std::string(65536,'x')<<"SECRET C:/private";return 1;}
    if(mode=="stderr-extra"){std::cerr<<"{\"schemaVersion\":1,\"phase\":\"file-identity\",\"kind\":\"WIN32\",\"operation\":\"CreateFileW\",\"win32Code\":32,\"path\":\"SECRET\"}";return 1;}
    if(mode=="invalid"){std::cout<<"{";return 0;}
    if(mode=="invalid-token"){std::cout<<"NATIVE_SENTINEL_207";return 0;}
    if(mode=="invalid-duplicate"){std::cout<<"{\"NATIVE_SENTINEL_207\":1,\"NATIVE_SENTINEL_207\":2}";return 0;}
    if(mode=="invalid-trailing"){std::cout<<"{\"schemaVersion\":1,\"volumeSerial\":\"12\",\"fileId\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"} NATIVE_SENTINEL_207";return 0;}
    if(mode=="invalid-utf8"){std::cout.put(static_cast<char>(0xff));return 0;}
    if(mode=="extra"){std::cout<<"{\"schemaVersion\":1,\"volumeSerial\":\"12\",\"fileId\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"extra\":true}";return 0;}
    if(mode=="descendant"){
        wchar_t image[32768];DWORD length=GetModuleFileNameW(nullptr,image,32768);if(!length||length==32768)return 5;
        std::wstring command=L"\""+std::wstring(image)+L"\" --hold";STARTUPINFOW startup{};startup.cb=sizeof(startup);startup.dwFlags=STARTF_USESTDHANDLES;startup.hStdInput=GetStdHandle(STD_INPUT_HANDLE);startup.hStdOutput=GetStdHandle(STD_OUTPUT_HANDLE);startup.hStdError=GetStdHandle(STD_ERROR_HANDLE);PROCESS_INFORMATION child{};
        if(!CreateProcessW(image,command.data(),nullptr,nullptr,TRUE,CREATE_NO_WINDOW,nullptr,nullptr,&startup,&child))return 6;
        std::wstring pidFile=std::wstring(argv[2])+L".child-pid";HANDLE recorded=CreateFileW(pidFile.c_str(),GENERIC_WRITE,FILE_SHARE_READ,nullptr,CREATE_ALWAYS,0,nullptr);
        if(recorded==INVALID_HANDLE_VALUE){TerminateProcess(child.hProcess,1);CloseHandle(child.hThread);CloseHandle(child.hProcess);return 8;}
        std::string pid=std::to_string(child.dwProcessId);DWORD written=0;BOOL saved=WriteFile(recorded,pid.data(),static_cast<DWORD>(pid.size()),&written,nullptr);CloseHandle(recorded);
        if(!saved||written!=pid.size()){TerminateProcess(child.hProcess,1);CloseHandle(child.hThread);CloseHandle(child.hProcess);return 9;}
        CloseHandle(child.hThread);CloseHandle(child.hProcess);Sleep(200);return 0;
    }
    std::string result="{\"schemaVersion\":1,\"volumeSerial\":\"12\",\"fileId\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}";
    if(mode=="boundary-ok"||mode=="boundary-limit")result.resize(mode=="boundary-ok"?4095:4096,' ');
    std::cout<<result;return 0;
}
