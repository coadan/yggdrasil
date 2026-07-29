# Markdown extractor

Build the optional reference plugin:

```sh
go build -o ../../bin/ygg-extract-markdown .
```

Configure it from a repository:

```json
{
  "schema": "ygg.config/v1",
  "plugins": [
    {
      "id": "markdown",
      "version": "0.1.0",
      "command": ["ygg-extract-markdown"],
      "includeGlobs": ["**/*.md", "**/*.mdx"],
      "timeoutMs": 10000
    }
  ]
}
```
