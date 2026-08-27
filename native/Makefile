# joan-ims native build: host unit tests + aarch64 device binary.
CC_host ?= cc
CFLAGS ?= -O2
AARCH64_GCC := aarch64-linux-gnu-gcc

SRC_COMMON := src/util.c src/md5.c src/secagree.c src/sip.c \
              src/config.c src/xfrm.c src/ctl.c src/ua.c
SRC_MAIN := src/main.c
TEST_SRC := tests/test_sip_units.c

.PHONY: all clean device test

all: device test

build:
	mkdir -p build

build/joan-ims-host-test: $(SRC_COMMON) $(TEST_SRC) | build
	$(CC_host) $(CFLAGS) -Isrc -o $@ \
		$(filter-out src/xfrm.c src/ctl.c,$(SRC_COMMON)) \
		tests/stub_xfrm_ctl.c $(TEST_SRC)

test: build/joan-ims-host-test
	./build/joan-ims-host-test

device: build/joan-ims-aarch64

build/joan-ims-aarch64: $(SRC_COMMON) $(SRC_MAIN)
	$(AARCH64_GCC) -static -Os -Wall -Isrc -o $@ $(SRC_COMMON) $(SRC_MAIN)
	aarch64-strip $@ || true

clean:
	rm -rf build
