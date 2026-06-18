python_sources(
    name = "lib",
    sources = ["*.py"],
    dependencies = [
        ":resources",
        "3rdparty/python#requests",
    ],
)

resources(
    name = "resources",
    sources = ["templates/*.html"],
)

python_tests(
    name = "tests",
    dependencies = [":lib"],
)
