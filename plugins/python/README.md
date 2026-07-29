# Python extractor

`ygg-extract-python` uses Python's standard-library `ast` parser to emit
top-level imports, classes, functions, and direct class methods. The process is
started once per index run and accepts file content over `ygg.extractor/v1`.

It requires Python 3 but has no third-party package dependency:

```json
{
  "id": "python",
  "version": "1",
  "command": ["./plugins/python/ygg-extract-python"],
  "includeGlobs": ["**/*.py"]
}
```
