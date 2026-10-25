#include "reader-source.inc"
int main(int argc,char** argv){
    std::string mode=argc>1?argv[1]:"normal";
    if(mode=="open-failure")failAt=2;if(mode=="reparse")reparse=true;if(mode=="close-failure")failClose=true;
    if(mode=="zero-eof")brokenPipe=false;if(mode=="read-error")readError=true;
    if(mode=="exact-limit")request.append(65536-request.size(),' ');if(mode=="over-limit")request.append(65537-request.size(),' ');
    int result=wmain();
    assert(live.empty()&&opened==closed&&localAllocations==localFrees);
    if(mode=="normal"||mode=="zero-eof"||mode=="exact-limit"){assert(result==0&&parents==std::vector<unsigned>({0,1})&&securityHandles==std::vector<unsigned>({1,2}));}
    else assert(result==1);
    return 0;
}
