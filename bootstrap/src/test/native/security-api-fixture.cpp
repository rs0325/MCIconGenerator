#include "reader-source.inc"
int main(int argc,char** argv){
    std::string mode=argc>1?argv[1]:"normal";
    if(mode=="open-failure")failAt=2;if(mode=="reparse")reparse=true;if(mode=="close-failure")failClose=true;
    if(mode=="zero-eof")brokenPipe=false;if(mode=="read-error")readError=true;
    if(mode=="exact-limit")request.append(65536-request.size(),' ');if(mode=="over-limit")request.append(65537-request.size(),' ');
    fileMode=mode.rfind("file",0)==0||mode=="middle-file";
    if(fileMode){auto at=request.find("directory");request.replace(at,9,"regular-file");}
    if(mode=="file-hardlink-zero")links=0;if(mode=="file-hardlink-two")links=2;if(mode=="file-hardlink-max")links=UINT32_MAX;
    if(mode=="file-links-changed")changeLinksAt=2;if(mode=="file-links-late")changeLinksAt=3;
    if(mode=="file-delete-pending")deletePending=true;if(mode=="file-standard-failure")standardFailure=true;
    if(mode=="file-type-pipe")fileKind=FILE_TYPE_PIPE;if(mode=="file-type-unknown")fileKind=FILE_TYPE_UNKNOWN;
    if(mode=="file-type-error"){fileKind=FILE_TYPE_UNKNOWN;standardFailure=true;}
    if(mode=="file-directory")terminalDirectory=true;if(mode=="file-standard-directory")standardDirectory=true;
    if(mode=="file-reparse")reparse=true;if(mode=="file-close-failure")failClose=true;if(mode=="file-open-failure")failAt=2;
    if(mode=="file-id-changed")fileIdChanged=true;if(mode=="file-security-failure")securityFailure=true;
    if(mode=="middle-file"){middleFile=true;request.replace(request.size()-2,2,",\"C:\\\\child\\\\last\"]}");}
    if(mode=="token-owner-error")ownerQueryFailure=true;if(mode=="token-owner-invalid")ownerSidInvalid=true;if(mode=="token-owner-size")ownerSizeInvalid=true;
    int result=wmain();
    assert(live.empty()&&opened==closed&&localAllocations==localFrees);
    if(mode=="normal"||mode=="zero-eof"||mode=="exact-limit"||mode=="file"){assert(tokenQueries==std::vector<int>({TokenUser,TokenUser,TokenOwner,TokenOwner}));assert(result==0&&parents==std::vector<unsigned>({0,1})&&securityHandles==std::vector<unsigned>({1,2}));}
    else assert(result==1);
    return 0;
}
