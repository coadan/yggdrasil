#!/usr/bin/env python3
"""Reformat `ygg search` JSON for the agent eval's output experiments.

Reads the CLI envelope on stdin and prints one of:

  compact  one header line, then each record as `path:start-end` followed by
           its excerpt indented, then the additional candidate paths
  files    distinct paths in rank order with their matched line ranges, then
           the additional candidate paths, with no excerpts

Errors print `error: <message>` and exit 2. This lives in the eval so output
shapes can be measured before any change to the CLI contract.
"""
import json
import sys


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else 'compact'
    raw = sys.stdin.read()
    try:
        envelope = json.loads(raw)
    except json.JSONDecodeError:
        sys.stdout.write(raw)
        return 1
    if not envelope.get('ok'):
        error = envelope.get('error')
        message = error.get('message') if isinstance(error, dict) else error
        print(f'error: {message}')
        return 2
    data = envelope.get('data') or {}
    records = data.get('records') or []
    more = data.get('morePaths') or []
    print(f"{len(records)} results ({data.get('activeMode')})")
    if mode == 'files':
        ranges = {}
        for record in records:
            ranges.setdefault(record['path'], []).append(f"{record['startLine']}-{record['endLine']}")
        for path, spans in ranges.items():
            print(f"{path}:{','.join(spans)}")
    else:
        for record in records:
            print(f"{record['path']}:{record['startLine']}-{record['endLine']}")
            for line in (record.get('excerpt') or '').splitlines():
                print(f'    {line}')
    if more:
        print('more: ' + ', '.join(more))
    return 0


if __name__ == '__main__':
    sys.exit(main())
