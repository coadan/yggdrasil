#!/usr/bin/env python3
"""Thin executable entrypoint for the cacheable Yggdrasil client module."""

import sys

from ygg_server_client import main


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
