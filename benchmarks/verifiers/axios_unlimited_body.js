'use strict';

const {execFileSync} = require('child_process');
const http = require('http');
const path = require('path');

const worktree = process.env.YGG_BENCH_WORKTREE;
execFileSync('npm', ['install', '--ignore-scripts', '--no-audit', '--no-fund'], {
  cwd: worktree,
  stdio: 'inherit'
});

const axios = require(path.join(worktree, 'index.js'));
const body = Buffer.alloc(11 * 1024 * 1024, 'x');
const server = http.createServer((request, response) => {
  let length = 0;
  request.on('data', chunk => { length += chunk.length; });
  request.on('end', () => {
    response.writeHead(length === body.length ? 204 : 400);
    response.end();
  });
});

server.listen(0, '127.0.0.1', async () => {
  try {
    const {port} = server.address();
    const response = await axios.post(`http://127.0.0.1:${port}/upload`, body, {
      headers: {'Content-Type': 'application/octet-stream'}
    });
    if (response.status !== 204) process.exitCode = 1;
  } catch (error) {
    console.error(error && error.stack || error);
    process.exitCode = 1;
  } finally {
    server.close();
  }
});
