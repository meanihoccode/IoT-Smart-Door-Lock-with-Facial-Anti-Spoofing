import test from 'node:test';
import assert from 'node:assert/strict';
import { handoffToKiosk } from '../src/kiosk-handoff.js';

test('handoff waits for server confirmation and coalesces concurrent effects', async () => {
  let finish; let calls = 0;
  const api = { post(path) { assert.equal(path, '/auth/kiosk'); calls++; return new Promise(resolve => { finish = resolve; }); } };
  const first = handoffToKiosk(api); const second = handoffToKiosk(api);
  assert.equal(first,second); assert.equal(calls,1);
  finish({ data: { status: 'success' } }); await first;
});
test('failed or malformed handoff never reports success and permits explicit retry', async () => {
  await assert.rejects(handoffToKiosk({ post: async () => { throw new Error('offline'); } }));
  await assert.rejects(handoffToKiosk({ post: async () => ({ data: { status: 'error' } }) }));
  await handoffToKiosk({ post: async () => ({ data: { status: 'success' } }) });
});
