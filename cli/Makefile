# OwnSona CLI Makefile
#
# Portable across Linux, macOS, and Windows (MSYS2 UCRT64).
# Single binary at runtime: libcurl + OpenSSL + zlib are statically linked
# where possible; system libc/Win32 are dynamic by necessity.
#
# Build dependencies (one-time, per platform):
#
#   Linux (Debian/Ubuntu):
#       sudo apt install build-essential libcurl4-openssl-dev libssl-dev zlib1g-dev
#       # If you also want the static .a variants (recommended):
#       sudo apt install libcurl-openssl-dev:amd64 || true
#       # (Static curl .a is sometimes in libcurl-dev or has to be built.)
#
#   macOS (Homebrew):
#       brew install curl openssl@3 pkg-config
#
#   Windows (MSYS2 UCRT64 shell):
#       pacman -S mingw-w64-ucrt-x86_64-toolchain \
#                 mingw-w64-ucrt-x86_64-curl     \
#                 mingw-w64-ucrt-x86_64-openssl
#
# Then just run `make`.  The resulting binary is `./ownsona` (or
# `ownsona.exe` on Windows).

# -----------------------------------------------------------------------
# Platform detection
# -----------------------------------------------------------------------
UNAME_S := $(shell uname -s)

ifeq ($(UNAME_S),Linux)
    PLATFORM := linux
endif
ifeq ($(UNAME_S),Darwin)
    PLATFORM := macos
endif
ifeq ($(findstring MINGW,$(UNAME_S)),MINGW)
    PLATFORM := windows
endif
ifeq ($(findstring MSYS,$(UNAME_S)),MSYS)
    PLATFORM := windows
endif
ifeq ($(findstring UCRT,$(UNAME_S)),UCRT)
    PLATFORM := windows
endif

ifndef PLATFORM
    $(error Unrecognized platform "$(UNAME_S)" --- supported: Linux, Darwin, MSYS2 UCRT64)
endif

# -----------------------------------------------------------------------
# Compiler and base flags
# -----------------------------------------------------------------------
CC      := gcc
CSTD    := -std=c11
WARN    := -Wall -Wextra -Wno-unused-parameter -Wno-unused-function
OPT     := -O2
DEFINES := -D_GNU_SOURCE -DOWNSONA_VERSION=\"$(shell git -C .. rev-parse --short HEAD 2>/dev/null || echo dev)\"

INCLUDES := -Iinclude -Isrc

CFLAGS  := $(CSTD) $(WARN) $(OPT) $(DEFINES) $(INCLUDES)

# -----------------------------------------------------------------------
# libcurl linker flags --- prefer --static-libs, fall back to dynamic
# -----------------------------------------------------------------------
HAVE_CURL_CONFIG := $(shell command -v curl-config 2>/dev/null)

ifneq ($(HAVE_CURL_CONFIG),)
    CFLAGS += $(shell curl-config --cflags)
    CURL_LIBS := $(shell curl-config --static-libs 2>/dev/null)
    ifeq ($(CURL_LIBS),)
        # curl-config --static-libs unsupported on this build; fall back
        CURL_LIBS := $(shell curl-config --libs)
        $(warning curl-config did not report --static-libs; linking dynamically against libcurl)
    endif
else
    $(warning curl-config not found; assuming -lcurl works.  See "Build dependencies" at the top of this Makefile.)
    CURL_LIBS := -lcurl
endif

# -----------------------------------------------------------------------
# Platform-specific extras
# -----------------------------------------------------------------------
ifeq ($(PLATFORM),linux)
    # Static curl on Linux still leaves glibc dynamic --- correct outcome.
    # OpenSSL is added explicitly (oauth.c uses RAND_bytes + SHA256);
    # curl-config --static-libs usually pulls it in too, but the explicit
    # link guarantees the symbols resolve even with shared libcurl.
    LDFLAGS := -Wl,--as-needed
    LDLIBS  := $(CURL_LIBS) -lssl -lcrypto -ldl -lpthread
    BIN     := ownsona
endif
ifeq ($(PLATFORM),macos)
    # macOS doesn't permit full -static (no static libSystem).  Frameworks
    # come from the SDK at runtime; curl/openssl/zlib are static via
    # curl-config --static-libs when Homebrew was built with static libs.
    LDFLAGS :=
    LDLIBS  := $(CURL_LIBS) -lssl -lcrypto -framework SystemConfiguration -framework CoreFoundation
    BIN     := ownsona
endif
ifeq ($(PLATFORM),windows)
    # MinGW supports full -static.  Statically link the C runtime and all
    # transitive deps so the .exe runs on any Windows 10+ host without
    # needing libcurl.dll or libssl.dll alongside it.
    LDFLAGS := -static
    LDLIBS  := $(CURL_LIBS) -lws2_32 -lwldap32 -lcrypt32 -lbcrypt
    BIN     := ownsona.exe
endif

# -----------------------------------------------------------------------
# Sources
# -----------------------------------------------------------------------
SRCS := $(wildcard src/*.c)
OBJS := $(SRCS:.c=.o)

# -----------------------------------------------------------------------
# Targets
# -----------------------------------------------------------------------
.PHONY: all clean test install help

all: $(BIN)

$(BIN): $(OBJS)
	$(CC) $(LDFLAGS) -o $@ $^ $(LDLIBS)
	@echo "==> $(BIN) built for $(PLATFORM)"
	@ls -lh $@

%.o: %.c
	$(CC) $(CFLAGS) -c $< -o $@

clean:
	rm -f $(OBJS) ownsona ownsona.exe

# Quick "does it run" sanity check that doesn't need the server.
test: $(BIN)
	./$(BIN) --version
	./$(BIN) --help >/dev/null

install: $(BIN)
	@if [ -z "$$PREFIX" ]; then \
	    echo "Set PREFIX (e.g. PREFIX=$$HOME/.local make install)"; \
	    exit 1; \
	fi
	install -d $(PREFIX)/bin
	install -m 755 $(BIN) $(PREFIX)/bin/

help:
	@echo "OwnSona CLI build targets:"
	@echo "  make             # build $(BIN) for $(PLATFORM)"
	@echo "  make clean       # remove build artifacts"
	@echo "  make test        # sanity-check the built binary"
	@echo "  PREFIX=... install   # install $(BIN) to PREFIX/bin"
