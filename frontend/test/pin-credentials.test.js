import { test } from 'node:test';
import assert from 'node:assert/strict';
import { pinCredentials } from '../src/pin-credentials.js';

test('PIN payload always identifies a profile and preserves leading zeroes', () => {
  assert.deepEqual(pinCredentials(' nv001 ', '001234'), { username: 'nv001', pinCode: '001234' });
  assert.notDeepEqual(pinCredentials('nv001', '001234'), pinCredentials('nv002', '001234'));
});
test('invalid code or PIN cannot be sent as a credential pair', () => {
  for (const code of ['', 'user name', 'a'.repeat(65)]) assert.throws(() => pinCredentials(code, '123456'));
  for (const pin of ['12345', '12345678901', '123a56', '１２３４５６', 123456]) assert.throws(() => pinCredentials('nv001', pin));
});
