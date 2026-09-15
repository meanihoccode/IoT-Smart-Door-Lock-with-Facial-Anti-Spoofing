import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import api from '../src/api.js';

let calls, events, token;
beforeEach(() => {
  calls = []; events = []; token = 'first-token';
  globalThis.window = { dispatchEvent: (event) => events.push(event.type) };
  api.defaults.adapter = async (config) => {
    calls.push(config);
    return { config, status: 200, statusText: 'OK', headers: {}, data:
      config.url === '/auth/csrf' ? { token, headerName: 'X-CSRF-TOKEN' } : {} };
  };
});
test('same-origin client sends cookies and fresh CSRF on every write', async () => {
  assert.equal(api.defaults.baseURL, '/api');
  assert.equal(api.defaults.withCredentials, true);
  await api.post('/auth/login', {});
  assert.deepEqual(calls.map((c) => c.url), ['/auth/csrf', '/auth/login']);
  assert.equal(calls[1].headers.get('X-CSRF-TOKEN'), 'first-token');
  token = 'rotated-token';
  await api.post('/auth/logout', {});
  assert.equal(calls[3].headers.get('X-CSRF-TOKEN'), 'rotated-token');
});
test('read requests do not fetch CSRF or add its header', async () => {
  await api.get('/users');
  assert.equal(calls.length, 1);
  assert.equal(calls[0].headers.get('X-CSRF-TOKEN'), undefined);
});
test('401 on protected API signals expired session, failed PIN/login does not', async () => {
  api.defaults.adapter = async (config) => {
    if (config.url === '/auth/csrf') return { config, status: 200, headers: {}, data: { token, headerName: 'X-CSRF-TOKEN' } };
    throw { config, response: { status: 401 } };
  };
  await assert.rejects(api.post('/verify-pin', {}));
  await assert.rejects(api.post('/auth/login', {}));
  assert.equal(events.length, 0);
  await assert.rejects(api.get('/users'));
  assert.deepEqual(events, ['smartlock:session-expired']);
});
test('failed writes are never automatically replayed', async () => {
  let writes = 0;
  api.defaults.adapter = async (config) => {
    if (config.url === '/auth/csrf') return { config, status: 200, headers: {}, data: { token, headerName: 'X-CSRF-TOKEN' } };
    writes++;
    throw { config, response: { status: 403 } };
  };
  await assert.rejects(api.post('/verify-face', {}));
  assert.equal(writes, 1);
});
