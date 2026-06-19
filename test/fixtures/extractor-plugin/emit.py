import json
import sys


request = json.load(sys.stdin)
path = request["file"]["path"]
file_id = request["file"]["file-id"]

node_id = f"node:plugin:{path}"

json.dump(
    {
        "schema": "agraph.extractor-plugin.result/v1",
        "nodes": [
            {
                "xt/id": node_id,
                "kind": "plugin-example",
                "label": f"plugin {path}",
            }
        ],
        "edges": [
            {
                "sourceId": node_id,
                "targetId": file_id,
                "relation": "plugin-links",
            }
        ],
        "fileFacts": [
            {
                "kind": "plugin-fact",
                "label": f"plugin fact {path}",
                "normalizedValue": path,
                "sourceLine": 1,
                "confidence": 0.75,
            }
        ],
        "chunks": [
            {
                "kind": "plugin-summary",
                "label": f"plugin summary {path}",
                "text": f"searchable plugin summary for {path}",
                "sourceLine": 1,
            }
        ],
    },
    sys.stdout,
)
