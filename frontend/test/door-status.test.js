import test from 'node:test';
import assert from 'node:assert/strict';
import { accessStatusLabel, commandLabel, connectionLabel, lockdownLabel, methodLabel } from '../src/door-status.js';

test('no command state is ever worded as the door being physically open', () => {
  for (const status of ['PENDING', 'PUBLISHED', 'RECEIVED', 'EXECUTED', 'REJECTED', 'FAILED', 'UNKNOWN', 'bogus']) {
    assert.doesNotMatch(commandLabel(status).label, /đã mở cửa|cửa đã mở/i, status);
  }
  assert.match(commandLabel('EXECUTED').label, /không có cảm biến/);
  assert.match(commandLabel('PUBLISHED').label, /chờ thiết bị/);
});

test('unconfirmed outcomes are warnings or failures, not success', () => {
  for (const status of ['PENDING', 'PUBLISHED', 'RECEIVED']) assert.equal(commandLabel(status).tone, 'warn');
  for (const status of ['FAILED', 'UNKNOWN', 'REJECTED']) assert.equal(commandLabel(status).tone, 'bad');
  assert.equal(accessStatusLabel('COMMAND_UNKNOWN').tone, 'bad');
  assert.equal(accessStatusLabel('SPOOF_DETECTED').tone, 'bad');
  assert.notEqual(accessStatusLabel('SUCCESS').tone, 'ok');
});

test('lockdown is only described as applied after device confirmation', () => {
  assert.match(lockdownLabel(true, false), /chờ thiết bị xác nhận/);
  assert.match(lockdownLabel(true, true), /đã xác nhận/);
  assert.equal(connectionLabel('OFFLINE').tone, 'bad');
  assert.equal(connectionLabel(undefined).tone, 'warn');
});

test('remote and device methods are distinguishable', () => {
  assert.notEqual(methodLabel('REMOTE_ADMIN'), methodLabel('FACE'));
  assert.equal(methodLabel('x'), 'Không xác định');
});
