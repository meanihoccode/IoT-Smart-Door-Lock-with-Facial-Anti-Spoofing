import test from 'node:test';
import assert from 'node:assert/strict';
import { faceFailureMessage, FACE_TIMEOUT_MS, PIN_TIMEOUT_MS, UNCERTAIN_REQUEST_MESSAGE } from '../src/kiosk-feedback.js';

test('AI reason code takes precedence over arbitrary upstream message', () => {
    assert.match(faceFailureMessage({ reasonCode: 'SPOOF_DETECTED', message: 'success' }, 'fallback'), /giả mạo/);
    assert.match(faceFailureMessage({ reasonCode: 'MODEL_UNAVAILABLE' }, 'fallback'), /chưa sẵn sàng/);
    assert.match(faceFailureMessage({ reasonCode: 'INVALID_AI_RESPONSE' }, 'fallback'), /không hợp lệ/);
});

test('profile revocation and unconfirmed MQTT delivery are never shown as success', () => {
    assert.match(faceFailureMessage({ reasonCode: 'PROFILE_NOT_ALLOWED' }), /không được phép/);
    assert.match(faceFailureMessage({ reasonCode: 'COMMAND_FAILED' }), /Kiểm tra trạng thái cửa/);
});

test('missing camera or other local failures retain their fallback', () => {
    assert.equal(faceFailureMessage(null, 'Camera chưa sẵn sàng'), 'Camera chưa sẵn sàng');
    assert.equal(faceFailureMessage({ message: 'CSRF không hợp lệ' }, 'fallback'), 'CSRF không hợp lệ');
});

test('timeouts allow Core AI plus MQTT waits and warn about uncertain command delivery', () => {
    assert.ok(FACE_TIMEOUT_MS > (3 + 20 + 5) * 1000);
    assert.ok(PIN_TIMEOUT_MS > 5000);
    assert.match(UNCERTAIN_REQUEST_MESSAGE, /Có thể lệnh đã được gửi/);
});
