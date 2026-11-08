#!/usr/bin/env python3
"""Validate the eval's small Bash command grammar before the harness runs it.

The ygg arm accepts search chains and stdin-only filters. The shell arms also
accept supported read-only commands scoped to the checkout. The hook runs in
addition to the harness's process permissions.
"""
import json
import os
from pathlib import Path
import re
import sys
from typing import NamedTuple


class Word(NamedTuple):
    text: str
    glob: bool = False


# Keep shell operators distinct from quoted words. Unquoted globs must later
# be validated as scoped file operands or recognized Grep filter options.
_LITERAL = (
    r"'[^']*'"
    r'|"(?:[^"\\`$]|\\[^\r\n]|\$(?![\w@*#?$!{\[(\-]))*"'
    r'|\\[^\r\n]'
    r"""|[^ \t\r\n'"\\`$;|&<>(){}*?\[\]#~]+"""
)
_WORD_PART = re.compile(_LITERAL)
_TOKEN = re.compile(
    r"""(?P<space>[ \t]+)"""
    r"""|(?P<redirect>(?:[12]?>>?[ \t]*(?:/dev/null|'/dev/null'|"/dev/null")|[12]?>&[12])(?=$|[ \t;|&]))"""
    r"""|(?P<separator>\|\||&&|[;|])"""
    r"""|(?P<glob>--(?:include|exclude|exclude-dir)=[\w.*+?/\[\]-]+(?=$|[ \t;|&><]))"""
    r"""|(?P<pathglob>[\w./+-]*[?*][\w./+?*-]*(?=$|[ \t;|&><]))"""
    rf"""|(?P<word>(?:{_LITERAL})+)"""
)
_NUMBER = re.compile(r'[+-]?\d+')
_RANGES = re.compile(r'[\d,-]+')
_SED_PRINT = re.compile(r'(?:(?:\d+|\$)(?:,(?:\d+|\$))?)?p')
_FILTERS = {
    'head': ('', {'n': _NUMBER, 'c': _NUMBER}),
    'tail': ('', {'n': _NUMBER, 'c': _NUMBER}),
    'wc': ('clmwL', {}),
    'sort': ('bdfghimnru', {'k': None, 't': None}),
    'uniq': ('cdiu', {'f': _NUMBER, 's': _NUMBER, 'w': _NUMBER}),
    'cut': ('s', {'b': _RANGES, 'c': _RANGES, 'f': _RANGES, 'd': None}),
}
_GREP = {'grep', 'egrep', 'fgrep'}
_GLOB_OPTIONS = {'include', 'exclude', 'exclude-dir'}


def word_text(raw):
    parts = []
    for match in _WORD_PART.finditer(raw):
        text = match[0]
        if text.startswith("'"):
            parts.append(text[1:-1])
        elif text.startswith('"'):
            parts.append(re.sub(r'\\(["\\$`])', r'\1', text[1:-1]))
        elif text.startswith('\\'):
            parts.append(text[1:])
        else:
            parts.append(text)
    return ''.join(parts)


def commands(command):
    """Return command words and the separator preceding each command."""
    if any(character in command for character in '\n\r\0'):
        raise ValueError('use explicit command separators')
    result, words, separator, position = [], [], None, 0
    while position < len(command):
        token = _TOKEN.match(command, position)
        if not token:
            raise ValueError('unsupported shell syntax')
        position = token.end()
        if token.lastgroup == 'separator':
            result.append((separator, words))
            words, separator = [], token[0]
        elif token.lastgroup in ('glob', 'pathglob'):
            words.append(Word(token[0], glob=any(character in token[0] for character in '*?[')))
        elif token.lastgroup == 'word':
            words.append(Word(word_text(token[0])))
    result.append((separator, words))
    if any(not argv for _, argv in result):
        raise ValueError('missing command')
    return result


def literals(words):
    if any(word.glob for word in words):
        raise ValueError('expected literal arguments')
    return [word.text for word in words]


def options(words, switches, values, *, long_switches=(), long_values=(), counts=False, trailing=None):
    """Recognize options with known arity and keep file operands separate."""
    seen, operands = set(), []
    position = 0
    while position < len(words):
        word = words[position]
        arg = word.text
        position += 1
        if arg == '-' or not arg.startswith('-'):
            if trailing is not None:
                operands.append(word)
                continue
            operands = words[position - 1:]
            if any(operand.text.startswith('-') and operand.text != '-' for operand in operands):
                raise ValueError('options must precede operands')
            return seen, operands
        # Grep can accept options after operands. When an implementation stops
        # parsing options at the first operand, those tokens must also be safe
        # as local filenames.
        if operands and not trailing(word):
            raise ValueError('trailing option could read outside the repository')
        if arg == '--':
            return seen, operands + words[position:]
        if arg.startswith('--'):
            name, equals, _ = arg[2:].partition('=')
            if not equals and name in long_switches and not word.glob:
                seen.add(name)
                continue
            if not equals or name not in long_values:
                raise ValueError('unsupported long option')
            if word.glob and (name not in _GLOB_OPTIONS or (not operands and not trailing(word))):
                raise ValueError('Grep option glob could expand outside the repository')
            seen.add(name)
            continue
        if word.glob:
            raise ValueError('unexpected glob')
        if counts and arg[1:].isdigit():
            continue
        for index, flag in enumerate(arg[1:], 2):
            if flag in switches:
                seen.add(flag)
                continue
            if flag not in values:
                raise ValueError('unsupported short option')
            value = arg[index:]
            if not value:
                if position >= len(words):
                    raise ValueError('missing option value')
                if operands and not trailing(words[position]):
                    raise ValueError('trailing option value could read outside the repository')
                value = literals(words[position:position + 1])[0]
                position += 1
            validator = values[flag]
            if validator is not None and not validator.fullmatch(value):
                raise ValueError('invalid option value')
            seen.add(flag)
            break
    return seen, operands


def inside(path, cwd):
    if path in ('-', '/dev/null'):
        return True
    if not path or '..' in Path(path).parts:
        return False
    try:
        return (cwd / path).resolve().is_relative_to(cwd)
    except (OSError, RuntimeError):
        return False


def file_operands(words, cwd, *, max_files=None, option_words=False):
    total = 0
    for word in words:
        if not word.glob:
            if not inside(word.text, cwd):
                return False
            total += 1
            continue
        path = Path(word.text)
        if '..' in path.parts or '**' in word.text:
            return False
        first_glob = next(index for index, part in enumerate(path.parts) if any(c in part for c in '*?['))
        prefix = cwd / Path(*path.parts[:first_glob])
        if not inside(str(prefix), cwd):
            return False
        matches = [prefix]
        for part in path.parts[first_glob:]:
            matches = [match for parent in matches for match in parent.glob(part)]
            if any(not inside(str(match), cwd) for match in matches):
                return False
        for match in matches:
            # A bare filename produced by expansion could be interpreted as a
            # new option. Attached Grep filter flags have a fixed safe prefix.
            if not path.is_absolute() and not word.text.startswith('./'):
                expanded = match.relative_to(cwd).as_posix()
                if expanded.startswith('-') and (not option_words or not expanded.startswith(
                    ('--include=', '--exclude=', '--exclude-dir=')
                )):
                    return False
        # With no match Bash normally passes the literal operand.
        if not inside(word.text, cwd):
            return False
        total += max(1, len(matches))
    return max_files is None or total <= max_files


def grep(words, cwd, stdin_only):
    switches = 'abcEFGhHiIlLnoPqsvwxyz'
    long_switches = {'ignore-case', 'invert-match', 'line-number', 'fixed-strings',
                     'extended-regexp', 'only-matching', 'line-buffered', 'files-with-matches',
                     'count', 'quiet', 'no-filename', 'with-filename', 'word-regexp'}
    long_values = {'regexp', 'color'}
    if not stdin_only:
        switches += 'r'
        long_switches.add('recursive')
        long_values |= _GLOB_OPTIONS
    def trailing(word):
        return file_operands([word], cwd, option_words=True)

    seen, operands = options(words, switches, {'e': None, 'A': _NUMBER, 'B': _NUMBER,
                                             'C': _NUMBER, 'm': _NUMBER},
                             long_switches=long_switches, long_values=long_values,
                             trailing=None if stdin_only else trailing)
    if 'e' in seen or 'regexp' in seen:
        paths = operands
    elif operands and not operands[0].glob:
        paths = operands[1:]
    else:
        return False
    return paths in ([], [Word('-')]) if stdin_only else file_operands(paths, cwd)


def find(words, cwd):
    argv = literals(words)
    index = 0
    while index < len(argv) and not argv[index].startswith('-') and argv[index] not in ('!', '(', ')'):
        if not inside(argv[index], cwd):
            return False
        index += 1
    while index < len(argv):
        predicate = argv[index]
        index += 1
        if predicate in ('!', '(', ')', '-not', '-and', '-or', '-a', '-o', '-print', '-print0', '-prune'):
            continue
        if index >= len(argv):
            return False
        value = argv[index]
        index += 1
        if predicate in ('-name', '-iname', '-path', '-ipath'):
            continue
        if predicate in ('-maxdepth', '-mindepth') and value.isdigit():
            continue
        if predicate == '-type' and value in ('f', 'd', 'l'):
            continue
        return False
    return True


def read_command(program, words, cwd, *, stdin_only=False):
    if program in _GREP:
        return grep(words, cwd, stdin_only)
    if program in _FILTERS:
        switches, values = _FILTERS[program]
        _, paths = options(words, switches, values, counts=program in ('head', 'tail'))
        if stdin_only:
            return paths in ([], [Word('-')])
        return file_operands(paths, cwd, max_files=1 if program == 'uniq' else None)
    elif stdin_only:
        return False
    elif program == 'find':
        return find(words, cwd)
    elif program == 'sed':
        argv = literals(words[:2])
        if len(argv) < 2 or argv[0] != '-n' or not _SED_PRINT.fullmatch(argv[1]):
            return False
        paths = words[2:]
        if any(path.text.startswith('-') and path.text != '-' for path in paths):
            return False
    elif program == 'tr':
        _, operands = options(words, 'cdsC', {})
        return 1 <= len(literals(operands)) <= 2
    elif program == 'nl':
        _, paths = options(words, '', {'b': None, 'n': None, 's': None, 'v': _NUMBER, 'w': _NUMBER, 'i': _NUMBER})
    elif program in ('cat', 'ls'):
        _, paths = options(words, 'AbenstuvTE' if program == 'cat' else 'AadFhlpRrt1', {})
    elif program == 'echo':
        literals(words)
        return True
    else:
        return False
    return file_operands(paths, cwd)


def allowed(command, mode='ygg', cwd='.'):
    if mode not in ('ygg', 'shell', 'shell-ygg'):
        return False
    try:
        root = Path(cwd).resolve()
        for separator, argv in commands(command):
            program = literals(argv[:1])[0]
            if mode == 'ygg' and separator == '|':
                if not read_command(program, argv[1:], root, stdin_only=True):
                    return False
            elif program == 'ygg' and mode in ('ygg', 'shell-ygg'):
                args = literals(argv[1:])
                if len(args) < 2 or args[0] != 'search':
                    return False
            elif mode == 'ygg':
                if program != 'echo':
                    return False
                literals(argv[1:])
            elif not read_command(program, argv[1:], root):
                return False
    except (ValueError, OSError, RuntimeError):
        return False
    return True


MESSAGES = {
    'ygg': 'Use ygg search chains and stdin-only filters. Quote literal shell characters.',
    'shell': 'Use the supported read-only commands and paths inside the repository.',
    'shell-ygg': 'Use ygg search or the supported read-only commands and paths inside the repository.',
}


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else 'ygg'
    event = json.load(sys.stdin)
    if event.get('tool_name') != 'Bash':
        return 0
    command = str((event.get('tool_input') or {}).get('command', ''))
    if allowed(command, mode, event.get('cwd') or os.getcwd()):
        return 0
    print(MESSAGES.get(mode, 'Unknown Bash guard mode.'), file=sys.stderr)
    return 2


if __name__ == '__main__':
    sys.exit(main())
