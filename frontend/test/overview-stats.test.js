import test from 'node:test';
import assert from 'node:assert/strict';
import { faceStatsView } from '../src/overview-stats.js';

test('unloaded data and a day with no scans are distinct from zero success', () => {
    assert.equal(faceStatsView(undefined).spoofDetected, '—');
    const empty = faceStatsView({ attempts: 0, granted: 0, spoofDetected: 0, successRate: null });
    assert.equal(empty.rate, '—');
    assert.equal(empty.spoofDetected, 0);
    assert.match(empty.description, /Chưa có lượt/);
    const denied = faceStatsView({ attempts: 2, granted: 0, spoofDetected: 1, successRate: 0 });
    assert.equal(denied.rate, '0%');
    assert.equal(denied.spoofDetected, 1);
});

test('formats the real rate and explains that granted access is not measured accuracy or door opening', () => {
    const stats = faceStatsView({ attempts: 3, granted: 1, spoofDetected: 2, successRate: 33.3 });
    assert.equal(stats.rate, '33,3%');
    assert.match(stats.description, /1\/3/);
    assert.match(stats.description, /Không phải độ chính xác AI hoặc tỷ lệ cửa mở/);
    assert.equal(stats.spoofDetected, 2);
});
