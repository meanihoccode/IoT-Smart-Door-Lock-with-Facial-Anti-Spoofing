import test from 'node:test';
import assert from 'node:assert/strict';
import { IDLE_MESSAGE, kioskView } from '../src/kiosk-feedback.js';

const NOW = '2026-10-28T10:00:10Z';
const base = { connection: 'ONLINE', lockdown: false, serverTime: NOW };
const at = (secondsAgo) => new Date(Date.parse(NOW) - secondsAgo * 1000).toISOString();

test('greets the masked person only after the device executed the unlock', () => {
    const attempt = { method: 'FACE', reasonCode: 'FACE_VERIFIED', person: 'V*** Q*** Hưng', at: at(2) };
    const opening = kioskView({ ...base, lastAttempt: { ...attempt, commandStatus: 'PUBLISHED' } });
    assert.equal(opening.status, 'SCANNING');
    assert.match(opening.message, /V\*\*\* Q\*\*\* Hưng/);
    const done = kioskView({ ...base, lastAttempt: { ...attempt, commandStatus: 'EXECUTED' } });
    assert.equal(done.status, 'SUCCESS');
    assert.match(done.message, /Xin chào V\*\*\* Q\*\*\* Hưng/);
    assert.doesNotMatch(done.message, /cửa đã mở/i);
    assert.equal(kioskView({ ...base, lastAttempt: { ...attempt, commandStatus: 'UNKNOWN' } }).status, 'FAILED');
});

test('spoof and strangers get explicit warnings', () => {
    const spoof = kioskView({ ...base, lastAttempt: { method: 'FACE', reasonCode: 'SPOOF_DETECTED', at: at(1) } });
    assert.equal(spoof.tone, 'danger');
    assert.match(spoof.message, /giả mạo \(ảnh hoặc màn hình điện thoại\)/);
    assert.match(kioskView({ ...base, lastAttempt: { method: 'FACE', reasonCode: 'NOT_RECOGNIZED', at: at(1) } }).message, /Người lạ/);
    assert.match(kioskView({ ...base, lastAttempt: { method: 'PIN', reasonCode: 'PIN_REJECTED', at: at(1) } }).message, /PIN không đúng/);
});

test('motion shows analysing until a newer result; old results fall back to idle', () => {
    assert.equal(kioskView({ ...base, motionAt: at(1) }).status, 'SCANNING');
    const old = { method: 'FACE', reasonCode: 'NO_FACE', at: at(5) };
    assert.equal(kioskView({ ...base, motionAt: at(1), lastAttempt: old }).status, 'FAILED');
    assert.equal(kioskView({ ...base, motionAt: at(30), lastAttempt: { ...old, at: at(30) } }).message, IDLE_MESSAGE);
    assert.equal(kioskView({ ...base, lockdown: true, motionAt: at(1) }).status, 'LOCKED');
    assert.equal(kioskView({ ...base, connection: 'OFFLINE' }).tone, 'warn');
});
