add_library(core src/core.cpp)
add_executable(app src/main.cpp)
target_link_libraries(app core)
