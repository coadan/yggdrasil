#!/usr/bin/env python3
"""Reformat `ygg search` JSON for the agent eval's output experiments.

Reads the CLI envelope on stdin and prints one of:

  compact  one header line, then each record as `path:start-end` followed by
           its excerpt indented and any structural card (cards.py), then the
           additional candidate paths
  top2     compact, but only the first two records keep their excerpts; the
           rest are listed as `path:start-end`
  files    distinct paths in rank order with their matched line ranges, then
           the additional candidate paths, with no excerpts

Errors print `error: <message>` and exit 2. This lives in the eval so output
shapes can be measured before any change to the CLI contract.
"""
import json
import sys


def print_card(card):
    """A structural card added by cards.py, in the compact layout."""
    if not card:
        return
    print(f"  card: {card['symbol']}")
    for label, key in (('called by', 'calledBy'), ('calls', 'calls'), ('related', 'related')):
        if card.get(key):
            print(f'    {label}: ' + '; '.join(card[key]))


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
    if mode == 'top2':
        for index, record in enumerate(records):
            print(f"{record['path']}:{record['startLine']}-{record['endLine']}")
            if index < 2:
                for line in (record.get('excerpt') or '').splitlines():
                    print(f'    {line}')
    elif mode == 'files':
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
            print_card(record.get('card'))
    if more:
        print('more: ' + ', '.join(more))
    return 0


if __name__ == '__main__':
    sys.exit(main())
