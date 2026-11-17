// 製品fileIdentityのAPI失敗直後の保存と、契約拒否の区別を検証する。
#include <cstdint>
#include <iostream>
#include <sstream>
#include <stdexcept>
#include <string>

using DWORD=unsigned long;
using HANDLE=int;
constexpr HANDLE INVALID_HANDLE_VALUE=-1;
constexpr DWORD FILE_READ_ATTRIBUTES=1,FILE_SHARE_READ=2,FILE_SHARE_WRITE=4,FILE_SHARE_DELETE=8,OPEN_EXISTING=3,FILE_FLAG_OPEN_REPARSE_POINT=16,FILE_ATTRIBUTE_REPARSE_POINT=32,FILE_ATTRIBUTE_DIRECTORY=64;
enum Information {FileAttributeTagInfo,FileIdInfo,FileStandardInfo};
struct FILE_ATTRIBUTE_TAG_INFO {DWORD FileAttributes=0;};
struct FILE_ID_INFO {uint64_t VolumeSerialNumber=12;struct {unsigned char Identifier[16]{};} FileId;};
struct FILE_STANDARD_INFO {DWORD NumberOfLinks=1;};
static DWORD error=0,attributes=0,links=1;
static int failing=-1;
struct Handle {HANDLE value;explicit Handle(HANDLE value):value(value){}~Handle(){error=999;}};
static std::wstring applicationPath(const std::wstring& path){return path;}
static std::wstring extended(const std::wstring& path){return path;}
static DWORD GetLastError(){return error;}
static HANDLE CreateFileW(const wchar_t*,DWORD,DWORD,void*,DWORD,DWORD,void*){if(failing==0){error=32;return INVALID_HANDLE_VALUE;}return 1;}
static bool GetFileInformationByHandleEx(HANDLE,Information info,void* result,unsigned long){
    if(failing==(int)info+1){error=87+(DWORD)info;return false;}
    if(info==FileAttributeTagInfo)((FILE_ATTRIBUTE_TAG_INFO*)result)->FileAttributes=attributes;
    if(info==FileStandardInfo)((FILE_STANDARD_INFO*)result)->NumberOfLinks=links;
    error=1234;return true;
}
#include "native-identity.inc"
static void accept(bool ok){if(!ok)throw std::runtime_error("file identity API fixture失敗");}
int main(){
    std::ostringstream out,err;auto oldOut=std::cout.rdbuf(out.rdbuf()),oldErr=std::cerr.rdbuf(err.rdbuf());
    const char* operations[]={"CreateFileW","FileAttributeTagInfo","FileIdInfo","FileStandardInfo"};
    for(int step=0;step<4;step++){
        failing=step;out.str("");err.str("");accept(fileIdentity(L"C:\\fixture")==1);accept(out.str().empty());
        std::string expected="{\"schemaVersion\":1,\"phase\":\"file-identity\",\"kind\":\"WIN32\",\"operation\":\""+std::string(operations[step])+"\",\"win32Code\":"+std::to_string(step==0?32:86+step)+"}\n";
        accept(err.str()==expected);accept(error==999);
    }
    failing=-1;
    for(DWORD rejected:{FILE_ATTRIBUTE_REPARSE_POINT,FILE_ATTRIBUTE_DIRECTORY}){
        attributes=rejected;out.str("");err.str("");accept(fileIdentity(L"C:\\fixture")==1);accept(out.str().empty());accept(err.str().find("\"kind\":\"CONTRACT\",\"operation\":\"FileAttributeTagInfo\",\"win32Code\":null")!=std::string::npos);
    }
    attributes=0;links=2;out.str("");err.str("");accept(fileIdentity(L"C:\\fixture")==1);accept(err.str().find("\"operation\":\"FileStandardInfo\",\"win32Code\":null")!=std::string::npos);
    links=1;out.str("");err.str("");accept(fileIdentity(L"C:\\fixture")==0);accept(err.str().empty());accept(out.str()=="{\"schemaVersion\":1,\"volumeSerial\":\"12\",\"fileId\":\"00000000000000000000000000000000\"}\n");
    std::cout.rdbuf(oldOut);std::cerr.rdbuf(oldErr);std::cout<<"file identity API fixture: 8境界成功\n";
}
