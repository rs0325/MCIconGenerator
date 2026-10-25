// 実readerをfake WinAPIへ接続する局所fixture。kernel受入ではない。
#include <cstdlib>
#include <cstring>
#include <cwchar>
#include <map>
#include <vector>
#include <string>
#include <cassert>
#include <cstdint>
using BYTE=uint8_t;using DWORD=uint32_t;using USHORT=uint16_t;using ULONG=uint32_t;using ULONGLONG=uint64_t;using BOOL=int;using NTSTATUS=int32_t;using HANDLE=void*;using PSID=void*;using LPWSTR=wchar_t*;using PSECURITY_DESCRIPTOR=void*;using SECURITY_DESCRIPTOR_CONTROL=uint16_t;
#define INVALID_HANDLE_VALUE ((HANDLE)(intptr_t)-1)
#define ERROR_SUCCESS 0
#define ERROR_BROKEN_PIPE 109
#define ERROR_TIMEOUT 1460
#define ERROR_INVALID_HANDLE 6
#define ERROR_INVALID_DATA 13
#define ERROR_INVALID_ACL 1336
#define ERROR_INVALID_NAME 123
#define ERROR_BUFFER_OVERFLOW 111
#define ERROR_REPARSE_TAG_INVALID 4393
#define ERROR_NOT_SAME_DEVICE 17
#define ERROR_NOT_SUPPORTED 50
#define CP_UTF8 65001
#define WC_ERR_INVALID_CHARS 128
#define MB_ERR_INVALID_CHARS 8
#define FILE_ATTRIBUTE_DIRECTORY 16
#define FILE_ATTRIBUTE_REPARSE_POINT 1024
#define SE_FILE_OBJECT 1
#define OWNER_SECURITY_INFORMATION 1
#define DACL_SECURITY_INFORMATION 4
#define ACCESS_ALLOWED_ACE_TYPE 0
#define ACCESS_DENIED_ACE_TYPE 1
#define TOKEN_QUERY 8
#define TokenUser 1
#define CSTR_EQUAL 2
#define TRUE 1
#define FALSE 0
#define READ_CONTROL 0x20000
#define FILE_READ_ATTRIBUTES 128
#define FILE_TRAVERSE 32
#define SYNCHRONIZE 0x100000
#define OBJ_CASE_INSENSITIVE 64
#define FILE_SHARE_READ 1
#define FILE_SHARE_WRITE 2
#define FILE_OPEN 1
#define FILE_DIRECTORY_FILE 1
#define FILE_NON_DIRECTORY_FILE 64
#define FILE_TYPE_UNKNOWN 0
#define FILE_TYPE_DISK 1
#define FILE_TYPE_PIPE 3
#define FILE_OPEN_REPARSE_POINT 0x200000
#define FILE_SYNCHRONOUS_IO_NONALERT 32
#define STD_INPUT_HANDLE -10
struct FILE_ATTRIBUTE_TAG_INFO {DWORD FileAttributes,ReparseTag;};
struct FILE_ID_INFO {ULONGLONG VolumeSerialNumber;struct {BYTE Identifier[16];} FileId;};
struct ACE_HEADER {BYTE AceType,AceFlags;USHORT AceSize;};
struct ACCESS_ALLOWED_ACE {ACE_HEADER Header;DWORD Mask,SidStart;DWORD tail[3];};
struct ACL {USHORT AclSize,AceCount;};using PACL=ACL*;
struct UNICODE_STRING {USHORT Length,MaximumLength;wchar_t* Buffer;};
struct OBJECT_ATTRIBUTES {ULONG Length;HANDLE RootDirectory;UNICODE_STRING* ObjectName;ULONG Attributes;};
struct IO_STATUS_BLOCK {};
struct TOKEN_USER {struct {PSID Sid;} User;};
struct FILE_STANDARD_INFO {int64_t AllocationSize,EndOfFile;DWORD NumberOfLinks;BOOL DeletePending,Directory;};
enum {FileAttributeTagInfo,FileIdInfo,FileStandardInfo};
struct MockNode {unsigned id;bool file=false;};
static std::map<HANDLE,MockNode> live;
static std::vector<unsigned> parents,securityHandles;
static std::string request="{\"schemaVersion\":2,\"terminalType\":\"directory\",\"paths\":[\"C:\\\\\",\"C:\\\\child\"]}";
static size_t consumed=0;
static unsigned opened=0,closed=0,localAllocations=0,localFrees=0,failAt=0;
static bool reparse=false,failClose=false;
static bool brokenPipe=true,readError=false,fileMode=false,terminalDirectory=false,middleFile=false,standardFailure=false,standardDirectory=false,deletePending=false,fileIdChanged=false,securityFailure=false;
static DWORD fileKind=FILE_TYPE_DISK,links=1;static unsigned standardCalls=0,changeLinksAt=0;
static std::map<HANDLE,unsigned> idCalls;
static DWORD lastError=5;
static DWORD GetLastError(){return lastError;}
static void SetLastError(DWORD value){lastError=value;}
static DWORD GetFileType(HANDLE h){assert(live.at(h).file);if(fileKind==FILE_TYPE_UNKNOWN)lastError=standardFailure?5:0;return fileKind;}
static ULONGLONG GetTickCount64(){return 0;}
static HANDLE GetCurrentProcess(){return (HANDLE)(intptr_t)-2;}
static HANDLE GetStdHandle(int){return nullptr;}
static BOOL CloseHandle(HANDLE h){assert(live.erase(h)==1);closed++;return !failClose;}
static void* LocalFree(void* p){localFrees++;std::free(p);return nullptr;}
static int MultiByteToWideChar(unsigned,unsigned,const char* p,int n,wchar_t* out,int){if(out)for(int i=0;i<n;i++)out[i]=(unsigned char)p[i];return n;}
static int WideCharToMultiByte(unsigned,unsigned,const wchar_t* p,int n,char* out,int,void*,void*){if(out)for(int i=0;i<n;i++)out[i]=(char)p[i];return n;}
static BOOL ConvertSidToStringSidW(PSID,LPWSTR* out){const wchar_t* s=L"S-1-1-0";*out=(LPWSTR)std::malloc((std::wcslen(s)+1)*sizeof(wchar_t));std::wcscpy(*out,s);localAllocations++;return TRUE;}
static BOOL IsValidSid(PSID p){return p!=nullptr;}
static DWORD GetLengthSid(PSID){return 12;}
static BOOL GetFileInformationByHandleEx(HANDLE h,int kind,void* out,DWORD){auto n=live.at(h);
    if(kind==FileAttributeTagInfo){auto* p=(FILE_ATTRIBUTE_TAG_INFO*)out;p->FileAttributes=(n.file?0:FILE_ATTRIBUTE_DIRECTORY)|(reparse&&n.id==2?FILE_ATTRIBUTE_REPARSE_POINT:0);p->ReparseTag=reparse&&n.id==2?1:0;}
    else if(kind==FileIdInfo){auto* p=(FILE_ID_INFO*)out;std::memset(p,0,sizeof(*p));p->VolumeSerialNumber=10;p->FileId.Identifier[0]=(BYTE)(n.id+(n.file&&fileIdChanged&&++idCalls[h]>1?1:0));}
    else {assert(kind==FileStandardInfo&&n.file);if(standardFailure){lastError=5;return FALSE;}auto* p=(FILE_STANDARD_INFO*)out;p->NumberOfLinks=changeLinksAt&&++standardCalls>=changeLinksAt?2:links;p->DeletePending=deletePending;p->Directory=standardDirectory;}
    return TRUE;
}
static DWORD GetSecurityInfo(HANDLE h,int,DWORD,PSID* owner,void*,PACL* acl,void*,PSECURITY_DESCRIPTOR* sd){if(securityFailure&&live.at(h).file)return 5;securityHandles.push_back(live.at(h).id);static DWORD principal=1;static ACL a{64,2};*owner=&principal;*acl=&a;*sd=std::malloc(1);localAllocations++;return ERROR_SUCCESS;}
static BOOL IsValidSecurityDescriptor(void*){return TRUE;}
static BOOL GetSecurityDescriptorControl(void*,SECURITY_DESCRIPTOR_CONTROL* c,DWORD* revision){*c=4;*revision=1;return TRUE;}
static BOOL GetSecurityDescriptorDacl(void*,BOOL* present,PACL* a,BOOL* defaulted){static ACL acl{64,2};*present=TRUE;*defaulted=FALSE;*a=&acl;return TRUE;}
static BOOL IsValidAcl(PACL){return TRUE;}
static BOOL GetAce(PACL,DWORD i,void** p){static ACCESS_ALLOWED_ACE allow{{0,0,sizeof(ACCESS_ALLOWED_ACE)},0xc0020000U,1,{0,0,0}};static ACE_HEADER unknown{42,0,4};*p=i?(void*)&unknown:(void*)&allow;return TRUE;}
static BOOL OpenProcessToken(HANDLE,DWORD,HANDLE* h){*h=(HANDLE)(uintptr_t)100;live[*h]={100};opened++;return TRUE;}
static BOOL GetTokenInformation(HANDLE,int,void* p,DWORD,DWORD* n){*n=sizeof(TOKEN_USER);if(p)((TOKEN_USER*)p)->User.Sid=p;return p!=nullptr;}
static int CompareStringOrdinal(const wchar_t* a,int n,const wchar_t* b,int,bool){return std::wcsncmp(a,b,n)==0?CSTR_EQUAL:0;}
static BOOL ReadFile(HANDLE h,char* out,DWORD maximum,DWORD* n,void*){assert(h==GetStdHandle(STD_INPUT_HANDLE));if(readError){lastError=5;return FALSE;}size_t count=std::min<size_t>(maximum,request.size()-consumed);if(!count&&brokenPipe){lastError=ERROR_BROKEN_PIPE;return FALSE;}std::memcpy(out,request.data()+consumed,count);consumed+=count;*n=(DWORD)count;return TRUE;}
static NTSTATUS NtCreateFile(HANDLE* h,DWORD access,OBJECT_ATTRIBUTES* object,IO_STATUS_BLOCK*,void*,DWORD,DWORD share,DWORD disposition,DWORD options,void*,DWORD){
    bool file=(options&FILE_NON_DIRECTORY_FILE)!=0;assert(file==(fileMode&&opened==1&&!middleFile));assert(access==(READ_CONTROL|FILE_READ_ATTRIBUTES|SYNCHRONIZE|(file?0:FILE_TRAVERSE)));assert(share==(FILE_SHARE_READ|FILE_SHARE_WRITE));assert(disposition==FILE_OPEN);assert(options==((file?FILE_NON_DIRECTORY_FILE:FILE_DIRECTORY_FILE)|FILE_OPEN_REPARSE_POINT|FILE_SYNCHRONOUS_IO_NONALERT));
    unsigned id=opened+1;if(failAt==id)return -1;parents.push_back(object->RootDirectory?live.at(object->RootDirectory).id:0);
    std::wstring name(object->ObjectName->Buffer,object->ObjectName->Length/sizeof(wchar_t));if(object->RootDirectory){assert(name==L"child");assert(object->Attributes&0x1000);}else assert(name==L"\\??\\C:\\");
    *h=(HANDLE)(uintptr_t)id;live[*h]={id,(file&&!terminalDirectory)||(middleFile&&id==2)};opened++;return 0;
}
static BOOL GetVolumeInformationByHandleW(HANDLE,wchar_t*,DWORD,void*,void*,void*,wchar_t* fs,DWORD){std::wcscpy(fs,L"NTFS");return TRUE;}
