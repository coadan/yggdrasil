#!/usr/bin/env python3
"""PreToolUse hook that holds an arm's Bash to its allowed commands.

Claude Code allows read-only shell commands such as grep and sed without a
permission rule, so an allowlist alone does not hold an arm to its tools. Modes:

  ygg        one plain `ygg search` command (the default)
  shell      read-only search and file commands, piped together if wanted,
             inside the working directory
  shell-ygg  the shell commands plus `ygg search`

Substitutions, background jobs and redirections other than to /dev/null or
between standard streams are refused in every mode.
"""
import json
import os
import shlex
import sys

PUNCTUATION = ';|&<>()'
SEPARATORS = {'|', '||', '&&', ';'}
SHELL_PROGRAMS = {'grep', 'egrep', 'fgrep', 'find', 'ls', 'cat', 'head', 'tail', 'sed',
                  'wc', 'sort', 'uniq', 'cut', 'tr', 'nl', 'echo'}
FIND_ACTIONS = {'-exec', '-execdir', '-ok', '-okdir', '-delete', '-fprint', '-fprintf', '-fls'}


def tokens(command):
    if '`' in command or '$(' in command:
        return None
    lexer = shlex.shlex(command, posix=True, punctuation_chars=PUNCTUATION)
    lexer.whitespace_split = True
    try:
        return list(lexer)
    except ValueError:
        return None


def segments(parts):
    """Split tokens into commands at separators; None if an operator is refused."""
    commands, current, index = [], [], 0
    while index < len(parts):
        token = parts[index]
        if token in SEPARATORS:
            commands.append(current)
            current = []
        elif token and set(token) <= set(PUNCTUATION):
            target = parts[index + 1] if index + 1 < len(parts) else ''
            if token in ('>', '>>') and target == '/dev/null':
                index += 1
            elif token == '>&' and target in ('1', '2'):
                index += 1
            else:
                return None
        else:
            current.append(token)
        index += 1
    commands.append(current)
    return commands


def inside(token, cwd):
    if token.startswith('~') or '..' in token.split('/'):
        return False
    if token.startswith('/'):
        return token == '/dev/null' or os.path.realpath(token).startswith(os.path.realpath(cwd) + os.sep) \
            or os.path.realpath(token) == os.path.realpath(cwd)
    return True


def shell_command(command, cwd):
    program, args = command[0], command[1:]
    if program not in SHELL_PROGRAMS:
        return False
    if program == 'find' and FIND_ACTIONS & set(args):
        return False
    if program == 'sed' and (any(arg.startswith('-i') or arg.startswith('--in-place') for arg in args)
                             or '-n' not in args):
        return False
    return all(inside(arg, cwd) for arg in args if not arg.startswith('-'))


def ygg_command(command):
    return command[:2] == ['ygg', 'search'] and len(command) > 2


def allowed(command, mode='ygg', cwd='.'):
    parts = tokens(command)
    if not parts:
        return False
    commands = segments(parts)
    if not commands or any(not part for part in commands):
        return False
    if mode == 'ygg':
        return len(commands) == 1 and parts == commands[0] and ygg_command(commands[0])
    for part in commands:
        if mode == 'shell-ygg' and part[0] == 'ygg':
            if not ygg_command(part):
                return False
        elif not shell_command(part, cwd):
            return False
    return True


MESSAGES = {
    'ygg': 'Only a single `ygg search ...` command is available in Bash.',
    'shell': 'Only read-only grep, find, ls, cat, head, tail, sed -n, wc, sort, uniq, cut, tr, nl and echo '
             'inside the repository are available in Bash.',
    'shell-ygg': 'Only `ygg search` and read-only grep, find, ls, cat, head, tail, sed -n, wc, sort, '
                 'uniq, cut, tr, nl and echo inside the repository are available in Bash.',
}


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else 'ygg'
    event = json.load(sys.stdin)
    if event.get('tool_name') != 'Bash':
        return 0
    command = str((event.get('tool_input') or {}).get('command', ''))
    if allowed(command, mode, event.get('cwd') or os.getcwd()):
        return 0
    print(MESSAGES[mode], file=sys.stderr)
    return 2


if __name__ == '__main__':
    sys.exit(main())
