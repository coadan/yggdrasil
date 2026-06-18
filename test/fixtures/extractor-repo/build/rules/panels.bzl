load("@rules_java//java:defs.bzl", "java_library")

def panel_library(name, srcs):
    native.java_library(name = name, srcs = srcs)

panel_rule = rule(
    implementation = panel_library,
)
