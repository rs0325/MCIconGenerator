CXX ?= c++
OUT ?= build
CPPFLAGS += -Isrc
CXXFLAGS += -std=c++20 -O2 -g0 -ffile-prefix-map="$(abspath ../..)=/usr/src/mcicon" -Wall -Wextra -Werror -Wpedantic
LDLIBS += -lsystemd -lcrypto

.PHONY: all test clean
all: $(OUT)/mcicon-manager-reader
$(OUT):
	mkdir -p "$@"
$(OUT)/reader-test: src/reader.cpp src/reader.hpp tests/reader-test.cpp | $(OUT)
	$(CXX) $(CPPFLAGS) $(CXXFLAGS) src/reader.cpp tests/reader-test.cpp -o "$@"
$(OUT)/mcicon-manager-reader: src/reader.cpp src/reader.hpp src/main.cpp | $(OUT)
	$(CXX) $(CPPFLAGS) $(CXXFLAGS) -D_FORTIFY_SOURCE=3 -fstack-protector-strong -fPIE -pie src/reader.cpp src/main.cpp -Wl,-z,relro,-z,now,-z,noexecstack -Wl,--strip-debug $(LDFLAGS) $(LDLIBS) -o "$@"
test: $(OUT)/reader-test
	"$(OUT)/reader-test"
	CXX="$(CXX)" python3 -m unittest discover -s tests -p 'test_main_bus.py'
clean:
	rm -f "$(OUT)/reader-test" "$(OUT)/mcicon-manager-reader"
