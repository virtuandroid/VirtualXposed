cmake_minimum_required(VERSION 3.18.1)
project("lsplantbridge")

set(CMAKE_CXX_STANDARD 23)

add_subdirectory(${PROJECT_ROOT}/external external)

add_library(lsplantbridge SHARED bridge_init.cpp elf/elf_image.cpp elf/symbol_cache.cpp common/config.h)
set_target_properties(lsplantbridge PROPERTIES CXX_SCAN_FOR_MODULES ON)

set(IGNORED_WARNINGS
    -Wno-c99-extensions
    -Wno-extra-semi
    -Wno-gnu-flexible-array-initializer
    -Wno-gnu-string-literal-operator-template
    -Wno-gnu-zero-variadic-macro-arguments
    -Wno-variadic-macros
    -Wno-zero-length-array
)

target_include_directories(${PROJECT_NAME} PRIVATE src ${PROJECT_ROOT}/external/xz-embedded/linux/include)
target_compile_options(${PROJECT_NAME} PRIVATE -Wpedantic ${IGNORED_WARNINGS})

#find_package(lsplant REQUIRED CONFIG)
find_package(dobby REQUIRED CONFIG)

target_link_libraries(${PROJECT_NAME} PUBLIC dobby::dobby lsplant_static xz_static log)

target_link_libraries(${PROJECT_NAME} PRIVATE
    dex_builder_static
)