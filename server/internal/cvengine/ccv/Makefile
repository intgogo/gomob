#
# Makefile
#
# Copyright (C) 2020 reflynet.com
#

# Master version number
VER_MAJOR := 1
VER_MINOR := 0
VER_PATCH := 1

PROJECT   := ccv
VERSION   := 1.0.1

include Makefile.inc
ifeq ($(BUILD_ATLAS), 1)
PROJECT = ccv_atlas
else ifeq ($(BUILD_LYNXI), 1)
PROJECT = ccv_lynxi
endif

# List of modules
MODULES += ocv
MODULES += ssc

INSTALL := install
ifeq ($(DESTDIR),)
PREFIX  ?= /usr/local
else
PREFIX  ?= /usr
endif
ifeq ($(LIBDIR),)
LIBDIR  := $(PREFIX)/lib
endif
CXXFLAGS	+= -Iinclude

MODMKS	:= $(patsubst %,src/%/mod.mk,$(MODULES))
SHARED  := lib$(PROJECT)$(LIB_SUFFIX)
STATIC	:= lib$(PROJECT).a

include $(MODMKS)

OBJS	?= $(patsubst %.cpp,$(BUILD)/%.o,$(SRCS))

all: $(SHARED) $(STATIC) libengine_crypt.so
static: $(STATIC)
shared: $(SHARED)

-include $(OBJS:.o=.d)

$(SHARED): $(OBJS)
	@echo "$(LD) $(LFLAGS) $(SH_LFLAGS) $^ $(LIBS) -o $@"
	@$(LD) $(LFLAGS) $(SH_LFLAGS) $^ $(LIBS) -o $@

$(STATIC): $(OBJS)
	@echo "$(AR) $(AFLAGS) $@ $^"
	@$(AR) $(AFLAGS) $@ $^
ifneq ($(RANLIB),)
	@$(RANLIB) $@
endif

$(BUILD)/%.o: src/%.cpp $(BUILD) Makefile $(MODMKS)
	@echo "$(CXX) $(CXXFLAGS) -c $< -o $@ $(DFLAGS)"
	@$(CXX) $(CXXFLAGS) -c $< -o $@ $(DFLAGS)


$(BUILD): Makefile $(MODMKS)
	@mkdir -p $(patsubst %,$(BUILD)/%,$(sort $(dir $(SRCS))))
	@touch $@


.PHONY: clean
clean:
	@rm -rf $(SHARED) $(STATIC) libengine_crypt.so test.d test.o test $(BUILD)


libengine_crypt.so: src/ocv/ocv_trt_crypt.cpp src/ocv/ocv_aes.cpp
	$(CXX) $(CXXFLAGS) $(SH_LFLAGS) -o $@ $^


install: $(SHARED) $(STATIC) libengine_crypt.so
	@mkdir -p $(DESTDIR)$(LIBDIR)
	$(INSTALL) -m 0755 $(SHARED) $(DESTDIR)$(LIBDIR)
	$(INSTALL) -m 0755 $(STATIC) $(DESTDIR)$(LIBDIR)
	$(INSTALL) -m 0755 libengine_crypt.so $(DESTDIR)$(LIBDIR)

uninstall:
	@rm -f $(DESTDIR)$(LIBDIR)/$(SHARED)
	@rm -f $(DESTDIR)$(LIBDIR)/$(STATIC)


