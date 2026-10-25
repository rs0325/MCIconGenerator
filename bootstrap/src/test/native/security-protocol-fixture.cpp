#include "security-protocol.h"
#include <cassert>
int main(){
    std::string directory="{\"schemaVersion\":2,\"terminalType\":\"directory\",\"paths\":[\"C:\\\\\",\"C:\\\\a b\"]}";
    auto accepted=Request::parse(directory);assert(accepted.terminalType=="directory"&&accepted.paths.size()==2&&accepted.paths[0]=="C:\\"&&accepted.paths[1]=="C:\\a b");
    auto file=directory;file.replace(file.find("directory"),9,"regular-file");assert(Request::parse(file).terminalType=="regular-file");
    for(const std::string& value:std::vector<std::string>{"{}","{\"schemaVersion\":1,\"paths\":[\"C:\\\\\"]}","{\"schemaVersion\":2,\"terminalType\":\"directory\",\"paths\":[]}","{\"schemaVersion\":2,\"terminalType\":\"file\",\"paths\":[\"C:\\\\\"]}","{\"schemaVersion\":2,\"terminalType\":\"regular-file\",\"paths\":[\"C:\\\\\"]}",directory+"{}",std::string(65537,' ')}){bool failed=false;try{Request::parse(value);}catch(const std::exception&){failed=true;}assert(failed);}
    assert(escaped("a\nb\\\"")=="\"a\\u000ab\\\\\\\"\"");
}
