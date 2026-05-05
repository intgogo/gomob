#
# Makefile.inc
#
# Copyright (C) 2020 reflynet.com
#
# Imported variables:
#
#   ARCH           Target architecture
#   CXX             Compiler
#   CROSS_COMPILE  Cross-compiler prefix (optional)
#   EXTRA_CFLAGS   Extra compiler flags appended to CXXFLAGS
#   EXTRA_LFLAGS   Extra linker flags appended to LFLAGS
#   OPT_SIZE       If non-empty, optimize for size
#   OPT_SPEED      If non-empty, optimize for speed
#   PROJECT        Project name
#   RELEASE        Release build
#   SYSROOT        System root of library and include files
#   SYSROOT_ALT    Alternative system root of library and include files
#   VERSION        Version number
#
# Exported variables:
#
#   APP_LFLAGS     Linker flags for applications using modules
#   BIN_SUFFIX     Suffix for binary executables
#   CXX             Compiler
#   CXXFLAGS         Compiler flags
#   DFLAGS         Dependency generator flags
#   LFLAGS         Common linker flags
#   LIBS           Libraries to link against
#   LIB_SUFFIX     Suffix for shared libraries
#   SH_LFLAGS      Linker flags for shared libraries
#

BUILD_ATLAS=0
BUILD_LYNXI=0
BUILD_ORT=1
BUILD_FAISS=0

ifneq ($(RELEASE),)
CXXFLAGS  += -DRELEASE
OPT_SPEED=1
endif


# Default system root
ifeq ($(SYSROOT),)
SYSROOT := /usr
endif


##############################################################################
#
# Compiler section
#
# find compiler name & version

ifeq ($(CXX),)
	CXX := g++
endif
ifeq ($(CXX),cc)
	CXX := g++
endif

LD := $(CXX)

# Compiler warning flags
CXXFLAGS	+= -Wall
CXXFLAGS	+= -Wmissing-declarations
CXXFLAGS	+= -Wsign-compare
CXXFLAGS	+= -Wshadow
CXXFLAGS	+= -Waggregate-return
CXXFLAGS	+= -Wcast-align

CXXFLAGS  += -g
# 调试符号里的源路径保持相对形式（./src/...），gdb 从 build 目录或 engine/ccv
# 目录开局就能找到源文件；避免把个人开发机的绝对路径烧进 .o，影响他人调试。
ifneq ($(OPT_SPEED),)
CXXFLAGS  += -O3  # Optimize for speed - takes longer to compile!
OPTIMIZE := 1
endif
ifneq ($(OPT_SIZE),)
CXXFLAGS  += -Os  # Optimize for size - takes longer to compile!
OPTIMIZE := 1
endif

ifneq ($(OPTIMIZE),)
CXXFLAGS	+= -Wuninitialized
endif


##############################################################################
#
# OS section
#

MACHINE   := $(shell $(CXX) -dumpmachine)

ifneq ($(strip $(filter i386-mingw32 i486-mingw32 i586-mingw32msvc \
	i686-w64-mingw32 mingw32 x86_64-w64-mingw32, \
	$(MACHINE))),)
	OS   := windows
else
	OS   := $(shell uname -s | sed -e s/SunOS/solaris/ | tr "[A-Z]" "[a-z]")
endif

# default
LIB_SUFFIX	:= .so
MOD_SUFFIX	:= .so
BIN_SUFFIX	:=

ifeq ($(OS),linux)
	CXXFLAGS		+= -fPIC -DLINUX
	LIBS		+= -ldl
	LFLAGS		+= -fPIC
	SH_LFLAGS	+= -shared
	MOD_LFLAGS	+=
	APP_LFLAGS	+= -rdynamic
	AR		:= ar
	AFLAGS		:= crD
endif
ifeq ($(OS),gnu)
	CXXFLAGS		+= -fPIC -DGNU
	LIBS		+= -ldl
	LFLAGS		+= -fPIC
	SH_LFLAGS	+= -shared
	MOD_LFLAGS	+=
	APP_LFLAGS	+= -rdynamic
	AR		:= ar
	AFLAGS		:= cru
endif
ifeq ($(OS),windows)
	LFLAGS		+=
	SH_LFLAGS	+= -shared
	MOD_LFLAGS	+=
	APP_LFLAGS	+= -Wl,--export-all-symbols
	AR		:= ar
	AFLAGS		:= cru
	CROSS_COMPILE	?=
	RANLIB		:= $(CROSS_COMPILE)ranlib
	LIB_SUFFIX	:= .dll
	MOD_SUFFIX	:= .dll
	BIN_SUFFIX	:= .exe
	SYSROOT		:= /usr/$(MACHINE)/
endif

CXXFLAGS	+= -DOS=\"$(OS)\"

ifneq ($(PEDANTIC),)
CXXFLAGS  += -pedantic
endif


ifeq ($(OS),)
$(warning Could not detect OS)
endif


##############################################################################
#
# Architecture section
#


ifeq ($(ARCH),)
ifeq ($(OS),windows)
ifneq ($(strip $(filter x86_64-w64-mingw32, $(MACHINE))),)
	ARCH   := x86_64
endif
ifneq ($(strip $(filter i686-w64-mingw32, $(MACHINE))),)
	ARCH   := i686
endif
ifneq ($(strip $(filter i386-mingw32, $(MACHINE))),)
	ARCH   := i386
endif
ifneq ($(strip $(filter i486-mingw32, $(MACHINE))),)
	ARCH   := i486
endif
ifneq ($(strip $(filter i586-mingw32msvc, $(MACHINE))),)
	ARCH   := i586
endif
else
PREDEF	:= $(shell $(CXX) -dM -E -x c $(EXTRA_CFLAGS) $(CXXFLAGS) /dev/null)

ifneq ($(strip $(filter i386 __i386__ __i386 _M_IX86 __X86__ _X86_, \
	$(PREDEF))),)
ARCH	:= i386
endif

ifneq ($(strip $(filter __i486__,$(PREDEF))),)
ARCH	:= i486
endif

ifneq ($(strip $(filter __i586__,$(PREDEF))),)
ARCH	:= i586
endif

ifneq ($(strip $(filter __i686__ ,$(PREDEF))),)
ARCH	:= i686
endif

ifneq ($(strip $(filter __amd64__ __amd64 __x86_64__ __x86_64, \
	$(PREDEF))),)
ARCH	:= x86_64
endif

ifneq ($(strip $(filter __arm__ __thumb__,$(PREDEF))),)

ifneq ($(strip $(filter __ARM_ARCH_6__,$(PREDEF))),)
ARCH	:= arm6
else
ARCH	:= arm
endif

endif

ifneq ($(strip $(filter __arm64__ ,$(PREDEF))),)
ARCH   := arm64
endif

endif
endif


ifeq ($(ARCH),)
$(warning Could not detect ARCH)
endif


CXXFLAGS	+= -DARCH=\"$(ARCH)\"


##############################################################################
#
# External libraries section
#

ifneq ($(OS),windows)
CXXFLAGS  += -I/usr/local/include
CXXFLAGS  += -I/usr/local/include/opencv4/opencv -I/usr/local/include/opencv4
CXXFLAGS  += -I/usr/local/cuda/include -L/usr/local/cuda/lib64
CXXFLAGS  += -DHAVE_CUDA
LFLAGS    += -L/usr/local/lib -L/usr/local/lib64 -lopencv_world

ifeq ($(BUILD_ATLAS),1)
CXXFLAGS  += -DUSE_ATLAS 
ifeq ($(ARCH),x86_64)
CXXFLAGS  += -I/usr/local/Ascend/ascend-toolkit/latest/acllib/include
LFLAGS    += -L/usr/local/Ascend/driver/lib64 -L/usr/local/Ascend/ascend-toolkit/latest/acllib/lib64 -L/usr/local/Ascend/ascend-toolkit/latest/acllib/lib64/stub -L/usr/local/Ascend/ascend-toolkit/latest/atc/lib64
else
CXXFLAGS  += -I/usr/local/Ascend/ascend-toolkit/latest/acllib/include
LFLAGS    += -L/usr/local/Ascend/ascend-toolkit/latest/acllib/lib64 -L/usr/local/Ascend/ascend-toolkit/latest/aarch64-linux/devlib -L/usr/local/Ascend/driver/lib64
endif
LFLAGS    += -lacl_cblas -lacl_dvpp -lascendcl -lruntime -lmmpa -lascend_hal
endif

ifeq ($(BUILD_LYNXI),1)
CXXFLAGS  += -DUSE_LYNXI
CXXFLAGS  += -I/usr/local/lynxi/sdk/include/lyn_api/
LFLAGS    += -L/usr/local/lynxi/sdk/lib
LFLAGS    += -lLYNCHIPSDKCLIENT -lLYNCHIPSDKCLIENTCOMM
endif

ifeq ($(BUILD_ORT),1)
CXXFLAGS  += --std=c++17
CXXFLAGS  += -DUSE_ORT
CXXFLAGS  += -I/usr/local/onnxruntime/include
LFLAGS    += -L/usr/local/onnxruntime/lib -lonnxruntime
else
CXXFLAGS  += --std=c++11
endif

ifeq ($(BUILD_FAISS),1)
CXXFLAGS  += -DUSE_SSC_FAISS
LFLAGS    += -lfaiss
endif

else
CXXFLAGS  += -I../include
LFLAGS    += -L../lib
LFLAGS    += -lopencv_world430
endif

BUILD   := build-$(ARCH)


default:	all

.PHONY: distclean
distclean:
	@rm -rf build* *core*
	@rm -f *stamp $(BIN)
	@rm -f `find . -name "*.[oda]"` `find . -name "*.so"`
	@rm -f `find . -name "*~"` `find . -name "\.\#*"`
	@rm -f `find . -name "*.orig"` `find . -name "*.rej"`
	@rm -f `find . -name "*.previous"` `find . -name "*.gcov"`
	@rm -f `find . -name "*.exe"` `find . -name "*.dll"`
	@rm -f `find . -name "*.dylib"`

.PHONY: info
info:
	@echo "info - $(PROJECT) version $(VERSION)"
	@echo "  MODULES:       $(MODULES)"
#	@echo "  SRCS:          $(SRCS)"
	@echo "  MACHINE:       $(MACHINE)"
	@echo "  ARCH:          $(ARCH)"
	@echo "  OS:            $(OS)"
	@echo "  BUILD:         $(BUILD)"
	@echo "  CXX:           $(CC_NAME) $(CC_SHORTVER)"
	@echo "  CXXFLAGS:      $(CXXFLAGS)"
	@echo "  DFLAGS:        $(DFLAGS)"
	@echo "  LFLAGS:        $(LFLAGS)"
	@echo "  LIBS:          $(LIBS)"
	@echo "  CROSS_COMPILE: $(CROSS_COMPILE)"
	@echo "  SYSROOT:       $(SYSROOT)"

