import json
from pathlib import Path
import sqlite3
import tempfile
import unittest

import cards

FILES = {
    'auth/token.py': 'def validate_token(token):\n    claims = verify_signature(token)\n    check_expiry(claims)\n'
                     '    raise TokenError()\n\n\ndef helper_two(x):\n    return x\n',
    'auth/middleware.py': 'def auth_middleware(request):\n    return validate_token(request.token)\n',
    'crypto/sig.py': 'def verify_signature(token):\n    return token\n',
    'auth/expiry.py': 'def check_expiry(claims):\n    return claims\n',
    'auth/errors.py': 'class TokenError(Exception):\n    pass\n',
    'a/dup.py': 'def check_dupe():\n    pass\n',
    'b/dup.py': 'def check_dupe():\n    pass\n',
}
RECORDS = [
    ('python-function', 'validate_token', 'auth/token.py', 1),
    ('python-function', 'helper_two', 'auth/token.py', 7),
    ('python-function', 'auth_middleware', 'auth/middleware.py', 1),
    ('python-function', 'verify_signature', 'crypto/sig.py', 1),
    ('python-function', 'check_expiry', 'auth/expiry.py', 1),
    ('python-class', 'TokenError', 'auth/errors.py', 1),
    ('python-function', 'check_dupe', 'a/dup.py', 1),
    ('python-function', 'check_dupe', 'b/dup.py', 1),
    ('python-import', 'verify_signature', 'auth/token.py', 1),
]


class CardsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        base = Path(self.tmp.name)
        self.repo = base / 'repo'
        for path, text in FILES.items():
            (self.repo / path).parent.mkdir(parents=True, exist_ok=True)
            (self.repo / path).write_text(text)
        self.storage = base / 'store'
        db = self.storage / 'indexes' / 'abc' / 'search.sqlite3'
        db.parent.mkdir(parents=True)
        con = sqlite3.connect(db)
        con.execute('create table repository (id integer primary key, root_id text, root text)')
        con.execute('insert into repository values (1, ?, ?)', ('abc', str(self.repo)))
        con.execute('create table records (kind text, title text, path text, start_line integer, source text)')
        con.executemany("insert into records values (?,?,?,?,'plugin:python')", RECORDS)
        con.commit()
        con.close()
        self.index = cards.Index.load(self.storage, self.repo)

    def tearDown(self):
        self.tmp.cleanup()

    def test_card_lists_unique_callers_callees_and_types(self):
        card = self.index.card('auth/token.py', 2)
        self.assertEqual(card['symbol'], 'validate_token auth/token.py:1')
        self.assertEqual(card['calledBy'], ['auth_middleware auth/middleware.py:2'])
        self.assertEqual(card['calls'], ['verify_signature crypto/sig.py:1', 'check_expiry auth/expiry.py:1'])
        self.assertEqual(card['related'], ['TokenError auth/errors.py:1'])

    def test_body_ends_at_next_declaration(self):
        card = self.index.card('auth/token.py', 8)
        self.assertEqual(card['symbol'], 'helper_two auth/token.py:7')
        self.assertEqual(card['calls'], [])

    def test_ambiguous_names_are_not_resolved(self):
        self.assertIsNone(self.index.unique('check_dupe'))
        self.assertIn('declared 2 times', cards.define('check_dupe', self.index))
        card = self.index.card('a/dup.py', 1)
        self.assertEqual(card['calledBy'], [])

    def test_decorate_adds_cards_only_inside_declarations(self):
        envelope = {'ok': True, 'data': {'records': [
            {'path': 'auth/token.py', 'startLine': 2, 'endLine': 3},
            {'path': 'auth/token.py', 'startLine': 7, 'endLine': 8},
            {'path': 'README.md', 'startLine': 1, 'endLine': 1}]}}
        out = cards.decorate(json.loads(json.dumps(envelope)), self.index)['data']['records']
        self.assertIn('card', out[0])
        self.assertNotIn('card', out[1])
        self.assertNotIn('card', out[2])

    def test_def_and_refs(self):
        text = cards.define('verify_signature', self.index)
        self.assertTrue(text.startswith('crypto/sig.py:1\n'))
        self.assertIn('def verify_signature(token):', text)
        refs = cards.references('validate_token', self.index).splitlines()
        self.assertEqual([r.split()[0] for r in refs], ['auth/middleware.py:2', 'auth/token.py:1'])
        self.assertEqual(cards.define('missing_name', self.index), 'no declaration named missing_name')

    def test_unknown_repository_has_no_declarations(self):
        index = cards.Index.load(self.storage, self.repo.parent)
        self.assertIsNone(index.card('auth/token.py', 1))


if __name__ == '__main__':
    unittest.main()
