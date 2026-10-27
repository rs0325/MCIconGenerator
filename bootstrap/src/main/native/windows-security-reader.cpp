// directory chain と最終通常fileを保持handleから観測する。作成・ACL変更・子起動は行わない。
#define _WIN32_WINNT 0x0A00
#include <windows.h>
#include <winternl.h>
#include <aclapi.h>
#include <sddl.h>
#include <memory>
#include <set>
#include <iostream>
#include <sstream>
#include <utility>
#include "security-protocol.h"

#ifndef OBJ_DONT_REPARSE
#define OBJ_DONT_REPARSE 0x00001000L
#endif
static bool cleanupUnknown=false;
struct Handle {HANDLE value=nullptr;~Handle(){if(value&&value!=INVALID_HANDLE_VALUE)if(!CloseHandle(value))cleanupUnknown=true;}Handle(const Handle&)=delete;Handle():value(nullptr){}};
struct Local {void* value=nullptr;~Local(){if(value&&LocalFree(value))cleanupUnknown=true;}};
struct Failure {std::string stage;DWORD error;std::string raw="";};
static void check(bool ok,const char* stage){if(!ok)throw Failure{stage,GetLastError()};}
static std::wstring wide(const std::string& value){int n=MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,value.data(),(int)value.size(),nullptr,0);check(n>0,"utf8");std::wstring out(n,L'\0');check(MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,value.data(),(int)value.size(),out.data(),n)==n,"utf8");return out;}
static std::string utf8(const std::wstring& value){int n=WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,value.data(),(int)value.size(),nullptr,0,nullptr,nullptr);check(n>0,"utf8");std::string out(n,'\0');check(WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,value.data(),(int)value.size(),out.data(),n,nullptr,nullptr)==n,"utf8");return out;}
static std::string sid(PSID value){check(value&&IsValidSid(value),"sid");LPWSTR text=nullptr;check(ConvertSidToStringSidW(value,&text),"sid-text");Local memory;memory.value=text;return utf8(text);}
static void deadline(ULONGLONG end){if(GetTickCount64()>=end)throw Failure{"deadline",ERROR_TIMEOUT};}
struct Identity {FILE_ATTRIBUTE_TAG_INFO tag{};FILE_ID_INFO id{};};
static Identity identity(HANDLE handle){Identity value;check(GetFileInformationByHandleEx(handle,FileAttributeTagInfo,&value.tag,sizeof(value.tag)),"attributes");check(GetFileInformationByHandleEx(handle,FileIdInfo,&value.id,sizeof(value.id)),"identity");return value;}
static std::string fileId(const FILE_ID_INFO& id){const char* hex="0123456789abcdef";std::string out;for(BYTE b:id.FileId.Identifier){out+=hex[b>>4];out+=hex[b&15];}return out;}
static bool same(const Identity& a,const Identity& b){return a.id.VolumeSerialNumber==b.id.VolumeSerialNumber&&fileId(a.id)==fileId(b.id)&&a.tag.FileAttributes==b.tag.FileAttributes&&a.tag.ReparseTag==b.tag.ReparseTag;}
static void directory(const Identity& value){if(!(value.tag.FileAttributes&FILE_ATTRIBUTE_DIRECTORY)||value.tag.FileAttributes&FILE_ATTRIBUTE_REPARSE_POINT||value.tag.ReparseTag)throw Failure{"reparse-or-type",ERROR_REPARSE_TAG_INVALID,",\"fileAttributes\":"+std::to_string(value.tag.FileAttributes)+",\"reparseTag\":"+std::to_string(value.tag.ReparseTag)};}
static DWORD regularFile(HANDLE handle,const Identity& value){
    if(value.tag.FileAttributes&FILE_ATTRIBUTE_DIRECTORY||value.tag.FileAttributes&FILE_ATTRIBUTE_REPARSE_POINT||value.tag.ReparseTag)throw Failure{"reparse-or-type",ERROR_REPARSE_TAG_INVALID,",\"fileAttributes\":"+std::to_string(value.tag.FileAttributes)+",\"reparseTag\":"+std::to_string(value.tag.ReparseTag)};
    SetLastError(ERROR_SUCCESS);DWORD kind=GetFileType(handle),error=GetLastError();if(kind!=FILE_TYPE_DISK)throw Failure{"file-type",kind==FILE_TYPE_UNKNOWN?error:ERROR_NOT_SUPPORTED,",\"fileType\":"+std::to_string(kind)};
    FILE_STANDARD_INFO standard{};check(GetFileInformationByHandleEx(handle,FileStandardInfo,&standard,sizeof(standard)),"file-standard");
    if(standard.Directory)throw Failure{"file-type",ERROR_INVALID_DATA,",\"directory\":true"};if(standard.DeletePending)throw Failure{"delete-pending",ERROR_INVALID_DATA,",\"deletePending\":true"};
    if(standard.NumberOfLinks!=1)throw Failure{"hardlink-count",ERROR_NOT_SUPPORTED,",\"linkCount\":"+std::to_string(standard.NumberOfLinks)};return standard.NumberOfLinks;
}
static std::string security(HANDLE handle){Local memory;PSID owner=nullptr;PACL dacl=nullptr;PSECURITY_DESCRIPTOR descriptor=nullptr;DWORD error=GetSecurityInfo(handle,SE_FILE_OBJECT,OWNER_SECURITY_INFORMATION|DACL_SECURITY_INFORMATION,&owner,nullptr,&dacl,nullptr,&descriptor);memory.value=descriptor;if(error!=ERROR_SUCCESS)throw Failure{"security",error};check(descriptor&&IsValidSecurityDescriptor(descriptor),"security-descriptor");SECURITY_DESCRIPTOR_CONTROL control;DWORD revision;check(GetSecurityDescriptorControl(descriptor,&control,&revision),"security-control");BOOL present=FALSE,defaulted=FALSE;check(GetSecurityDescriptorDacl(descriptor,&present,&dacl,&defaulted),"security-dacl");std::ostringstream out;out<<"\"ownerSid\":"<<escaped(sid(owner))<<",\"controlFlags\":"<<control<<",\"daclPresent\":"<<(present?"true":"false")<<",\"daclNull\":"<<(dacl?"false":"true")<<",\"aces\":[";
    if(dacl){check(IsValidAcl(dacl),"acl");if(dacl->AceCount>256)throw Failure{"acl-size",ERROR_BUFFER_OVERFLOW};
        for(DWORD i=0;i<dacl->AceCount;i++){void* raw=nullptr;check(GetAce(dacl,i,&raw),"ace");auto* header=(ACE_HEADER*)raw;if(i)out<<',';out<<"{\"type\":"<<(unsigned)header->AceType<<",\"flags\":"<<(unsigned)header->AceFlags<<",\"bytes\":"<<header->AceSize;
            if(header->AceType==ACCESS_ALLOWED_ACE_TYPE||header->AceType==ACCESS_DENIED_ACE_TYPE){auto* ace=(ACCESS_ALLOWED_ACE*)raw;if(header->AceSize<offsetof(ACCESS_ALLOWED_ACE,SidStart)+8)throw Failure{"ace-size",ERROR_INVALID_ACL};PSID principal=&ace->SidStart;check(IsValidSid(principal),"ace-sid");if(offsetof(ACCESS_ALLOWED_ACE,SidStart)+GetLengthSid(principal)>header->AceSize)throw Failure{"ace-sid-size",ERROR_INVALID_ACL};out<<",\"rawMask\":"<<(uint32_t)ace->Mask<<",\"sid\":"<<escaped(sid(principal));}
            else out<<",\"unknown\":true";out<<'}';}
    }out<<']';return out.str();}
static std::pair<std::string,std::string> token(){Handle handle;check(OpenProcessToken(GetCurrentProcess(),TOKEN_QUERY,&handle.value),"token");
    auto query=[&](TOKEN_INFORMATION_CLASS kind,const char* stage){DWORD bytes=0;if(GetTokenInformation(handle.value,kind,nullptr,0,&bytes))throw Failure{stage,ERROR_INVALID_DATA};DWORD error=GetLastError();if(error!=ERROR_INSUFFICIENT_BUFFER)throw Failure{stage,error};if(!bytes||bytes>65536)throw Failure{"token-size",ERROR_INVALID_DATA};std::vector<BYTE> data(bytes);check(GetTokenInformation(handle.value,kind,data.data(),bytes,&bytes),stage);return sid(kind==TokenUser?((TOKEN_USER*)data.data())->User.Sid:((TOKEN_OWNER*)data.data())->Owner);};
    auto user=query(TokenUser,"token-user"),owner=query(TokenOwner,"token-owner");return {user,owner};}
static std::wstring component(const std::wstring& path,const std::wstring& parent){std::wstring prefix=parent;if(prefix.back()!=L'\\')prefix+=L'\\';if(path.size()<=prefix.size()||CompareStringOrdinal(path.data(),(int)prefix.size(),prefix.data(),(int)prefix.size(),TRUE)!=CSTR_EQUAL)throw Failure{"path-chain",ERROR_INVALID_NAME};std::wstring name=path.substr(prefix.size());if(name==L"."||name==L".."||name.find_first_of(L"\\/:\"<>|?*\r\n")!=std::wstring::npos||name.back()==L'.'||name.back()==L' ')throw Failure{"path-component",ERROR_INVALID_NAME};return name;}
static std::string read(){ULONGLONG end=GetTickCount64()+5000;
    std::string input;char buffer[4096];DWORD n=0;while(true){deadline(end);if(!ReadFile(GetStdHandle(STD_INPUT_HANDLE),buffer,sizeof(buffer),&n,nullptr)){DWORD error=GetLastError();if(error==ERROR_BROKEN_PIPE)break;throw Failure{"stdin",error};}if(!n)break;if(input.size()+n>65536)throw Failure{"stdin-size",ERROR_BUFFER_OVERFLOW};input.append(buffer,n);}Request request=Request::parse(input);
    std::vector<std::unique_ptr<Handle>> handles;std::vector<Identity> identities;std::vector<std::wstring> paths;std::vector<std::string> rows;std::set<std::string> known;
    for(size_t i=0;i<request.paths.size();i++){deadline(end);auto path=wide(request.paths[i]);std::wstring name;
        if(i==0){if(path.size()!=3||!((path[0]>=L'A'&&path[0]<=L'Z')||(path[0]>=L'a'&&path[0]<=L'z'))||path[1]!=L':'||path[2]!=L'\\')throw Failure{"drive-root",ERROR_INVALID_NAME};name=L"\\??\\"+path;}
        else name=component(path,paths.back());
        for(size_t j=0;j<handles.size();j++)if(!same(identities[j],identity(handles[j]->value)))throw Failure{"ancestor-changed",ERROR_INVALID_DATA};
        UNICODE_STRING unicode{};unicode.Buffer=name.data();unicode.Length=(USHORT)(name.size()*sizeof(wchar_t));unicode.MaximumLength=unicode.Length;OBJECT_ATTRIBUTES attributes{};attributes.Length=sizeof(attributes);attributes.RootDirectory=i?handles.back()->value:nullptr;attributes.ObjectName=&unicode;attributes.Attributes=OBJ_CASE_INSENSITIVE|(i?OBJ_DONT_REPARSE:0);IO_STATUS_BLOCK status{};auto handle=std::make_unique<Handle>();bool file=request.terminalType=="regular-file"&&i+1==request.paths.size();
        NTSTATUS result=NtCreateFile(&handle->value,READ_CONTROL|FILE_READ_ATTRIBUTES|SYNCHRONIZE|(file?0:FILE_TRAVERSE),&attributes,&status,nullptr,0,FILE_SHARE_READ|FILE_SHARE_WRITE,FILE_OPEN,(file?FILE_NON_DIRECTORY_FILE:FILE_DIRECTORY_FILE)|FILE_OPEN_REPARSE_POINT|FILE_SYNCHRONOUS_IO_NONALERT,nullptr,0);if(result<0)throw Failure{"relative-open",(DWORD)result};
        auto observed=identity(handle->value);DWORD links=0;if(file)links=regularFile(handle->value,observed);else directory(observed);if(i&&observed.id.VolumeSerialNumber!=identities.front().id.VolumeSerialNumber)throw Failure{"volume-changed",ERROR_NOT_SAME_DEVICE};std::string key=std::to_string(observed.id.VolumeSerialNumber)+":"+fileId(observed.id);if(!known.insert(key).second)throw Failure{"duplicate-identity",ERROR_INVALID_DATA};
        if(i==0){wchar_t fs[32];check(GetVolumeInformationByHandleW(handle->value,nullptr,0,nullptr,nullptr,nullptr,fs,32),"filesystem");if(std::wstring(fs)!=L"NTFS")throw Failure{"filesystem",ERROR_NOT_SUPPORTED};}
        std::ostringstream row;row<<"{\"index\":"<<i<<",\"path\":"<<escaped(request.paths[i])<<",\"parentIndex\":"<<(i?std::to_string(i-1):"null")<<",\"relativeName\":"<<(i?escaped(utf8(name)):"null")<<",\"parentFileId\":"<<(i?escaped(fileId(identities.back().id)):"null")<<",\"parentVolumeSerial\":"<<(i?escaped(std::to_string(identities.back().id.VolumeSerialNumber)):"null")<<",\"volumeSerial\":"<<escaped(std::to_string(observed.id.VolumeSerialNumber))<<",\"fileId\":"<<escaped(fileId(observed.id))<<",\"nodeType\":"<<escaped(file?"regular-file":"directory")<<",\"linkCount\":"<<(file?std::to_string(links):"null")<<",\"fileAttributes\":"<<observed.tag.FileAttributes<<",\"reparseTag\":"<<observed.tag.ReparseTag<<','<<security(handle->value)<<'}';
        if(!same(observed,identity(handle->value)))throw Failure{"node-changed",ERROR_INVALID_DATA};if(file)regularFile(handle->value,observed);paths.push_back(path);identities.push_back(observed);handles.push_back(std::move(handle));rows.push_back(row.str());
    }
    for(size_t i=0;i<handles.size();i++){deadline(end);if(!same(identities[i],identity(handles[i]->value)))throw Failure{"ancestor-changed",ERROR_INVALID_DATA};if(request.terminalType=="regular-file"&&i+1==handles.size())regularFile(handles[i]->value,identities[i]);}
    auto identity=token();std::string out="{\"schemaVersion\":3,\"status\":\"observed\",\"tokenUserSid\":"+escaped(identity.first)+",\"tokenDefaultOwnerSid\":"+escaped(identity.second)+",\"rows\":[";for(size_t i=0;i<rows.size();i++){if(i)out+=',';out+=rows[i];}out+="]}";if(out.size()>1048576)throw Failure{"stdout-size",ERROR_BUFFER_OVERFLOW};deadline(end);return out;
}
int wmain(){try{std::string out=read();if(cleanupUnknown)throw Failure{"cleanup",ERROR_INVALID_HANDLE};std::cout<<out<<std::endl;return std::cout?0:1;}catch(const Failure& error){std::cout<<"{\"schemaVersion\":3,\"status\":\"unknown\",\"errorStage\":"<<escaped(error.stage)<<",\"win32OrNtStatus\":"<<error.error<<error.raw<<",\"cleanupUnknown\":"<<(cleanupUnknown?"true":"false")<<'}'<<std::endl;return 1;}catch(const std::exception& error){std::cout<<"{\"schemaVersion\":3,\"status\":\"unknown\",\"errorStage\":"<<escaped(error.what())<<'}'<<std::endl;return 1;}}
