# PC simulator for the CYD UI. Needs `pio pkg install` in cyd/ first (for
# the LVGL and ArduinoJson sources).
#
#   make            build ./build/sim
#   make run        run script.txt and convert screenshots to PNG

LIBDEPS := ../.pio/libdeps/cyd
LVGL    := $(LIBDEPS)/lvgl
AJSON   := $(LIBDEPS)/ArduinoJson/src
BUILD   := build
SD_DIR  ?= $(abspath sd)

CFLAGS   := -O1 -g -DLV_CONF_INCLUDE_SIMPLE -DSIM_SD_PATH='"$(SD_DIR)"' \
            -I../include -I$(LVGL) -I$(AJSON) -I../src/gen
CXXFLAGS := $(CFLAGS) -std=c++17 -Wall -Wno-unused-function

LVGL_SRC := $(shell find $(LVGL)/src -name '*.c')
APP_SRC  := $(wildcard ../src/app/*.cpp) $(wildcard ../src/gen/*.cpp)
FONT_SRC := $(wildcard ../src/gen/*.c)

LVGL_OBJ := $(patsubst $(LVGL)/%.c,$(BUILD)/lvgl/%.o,$(LVGL_SRC))
APP_OBJ  := $(patsubst ../src/%.cpp,$(BUILD)/app/%.o,$(APP_SRC))
FONT_OBJ := $(patsubst ../src/gen/%.c,$(BUILD)/fonts/%.o,$(FONT_SRC))

$(BUILD)/sim: $(LVGL_OBJ) $(APP_OBJ) $(FONT_OBJ) $(BUILD)/sim_main.o
	$(CXX) -o $@ $^ -lm

$(BUILD)/lvgl/%.o: $(LVGL)/%.c ../include/lv_conf.h
	@mkdir -p $(dir $@)
	@$(CC) $(CFLAGS) -c $< -o $@

$(BUILD)/app/%.o: ../src/%.cpp $(wildcard ../src/app/*.h) ../src/gen/game_data.h
	@mkdir -p $(dir $@)
	$(CXX) $(CXXFLAGS) -c $< -o $@

$(BUILD)/fonts/%.o: ../src/gen/%.c
	@mkdir -p $(dir $@)
	@$(CC) $(CFLAGS) -c $< -o $@

$(BUILD)/sim_main.o: sim_main.cpp
	@mkdir -p $(dir $@)
	$(CXX) $(CXXFLAGS) -c $< -o $@

clean:
	rm -rf $(BUILD)

.PHONY: clean
