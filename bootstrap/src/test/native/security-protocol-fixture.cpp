#include "security-protocol.h"
#include <cassert>
int main(){
    auto accepted=Request::parse("{\"schemaVersion\":1,\"paths\":[\"C:\\\\\",\"C:\\\\a b\"]}");assert(accepted.paths.size()==2&&accepted.paths[0]=="C:\\"&&accepted.paths[1]=="C:\\a b");
    for(const std::string& value:std::vector<std::string>{"{}","{\"schemaVersion\":1,\"paths\":[]}","{\"schemaVersion\":1,\"paths\":[\"a\"],\"paths\":[\"b\"]}","{\"schemaVersion\":1,\"paths\":[\"a\"]}{}",std::string(65537,' ')}){bool failed=false;try{Request::parse(value);}catch(const std::exception&){failed=true;}assert(failed);}
    assert(escaped("a\nb\\\"")=="\"a\\u000ab\\\\\\\"\"");
}
