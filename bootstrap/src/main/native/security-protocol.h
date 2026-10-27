// reader の単一要求だけを受け付ける。汎用 JSON parser ではない。
#pragma once
#include <string>
#include <vector>
#include <stdexcept>
#include <cstdint>

struct Request {
    std::string terminalType;
    std::vector<std::string> paths;
    static Request parse(const std::string& input) {
        if(input.size()>65536)throw std::runtime_error("request-size");
        size_t at=0;
        auto space=[&](){while(at<input.size()&&(input[at]==' '||input[at]=='\n'||input[at]=='\r'||input[at]=='\t'))at++;};
        auto token=[&](char c){space();if(at==input.size()||input[at++]!=c)throw std::runtime_error("request-json");};
        auto text=[&](){space();if(at==input.size()||input[at++]!='"')throw std::runtime_error("request-string");std::string out;
            while(at<input.size()){unsigned char c=input[at++];if(c=='"')return out;if(c<32)throw std::runtime_error("request-control");
                if(c=='\\'){if(at==input.size())throw std::runtime_error("request-escape");c=input[at++];if(c!='\\'&&c!='"'&&c!='/')throw std::runtime_error("request-escape");}out+=(char)c;
            }throw std::runtime_error("request-string");};
        Request result;token('{');if(text()!="schemaVersion")throw std::runtime_error("request-schema");token(':');token('3');token(',');if(text()!="terminalType")throw std::runtime_error("request-schema");token(':');result.terminalType=text();if(result.terminalType!="directory"&&result.terminalType!="regular-file")throw std::runtime_error("request-terminal");token(',');if(text()!="paths")throw std::runtime_error("request-schema");token(':');token('[');
        for(;;){if(result.paths.size()==64)throw std::runtime_error("request-count");auto path=text();if(path.empty()||path.size()>32768||path.find('\0')!=std::string::npos)throw std::runtime_error("request-path");result.paths.push_back(path);space();if(at<input.size()&&input[at]==']'){at++;break;}token(',');}
        token('}');if(result.terminalType=="regular-file"&&result.paths.size()<2)throw std::runtime_error("request-file-chain");space();if(at!=input.size())throw std::runtime_error("request-tail");return result;
    }
};
static std::string escaped(const std::string& value){std::string out="\"";const char* hex="0123456789abcdef";for(unsigned char c:value){if(c=='"'||c=='\\'){out+='\\';out+=(char)c;}else if(c<32){out+="\\u00";out+=hex[c>>4];out+=hex[c&15];}else out+=(char)c;}return out+'"';}
