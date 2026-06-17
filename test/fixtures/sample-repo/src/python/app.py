import os
import requests as req
from demo.core import Client
from . import local


class Service:
    def __init__(self):
        self.client = Client()

    async def fetch(self):
        return req.get("https://example.com")


def build():
    return Service()


async def main():
    return build()
