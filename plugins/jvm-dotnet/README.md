# JVM and .NET extractor

`ygg-extract-jvm-dotnet` extracts syntax-owned facts for Java and C#. It emits
packages/namespaces, imports/usings,
declarations, line ranges, and parser diagnostics.

The pinned parser bundle is optional and isolated from the Go CLI:

```sh
python3 -m venv .dev/jvm-dotnet-venv
.dev/jvm-dotnet-venv/bin/pip install -r plugins/jvm-dotnet/requirements.txt
```

```json
{
  "id": "jvm-dotnet",
  "version": "1",
  "command": [
    ".dev/jvm-dotnet-venv/bin/python",
    "plugins/jvm-dotnet/ygg-extract-jvm-dotnet"
  ],
  "includeGlobs": ["**/*.java", "**/*.cs"]
}
```

The plugin does not infer architecture or project meaning from packages,
namespaces, paths, or type names.
