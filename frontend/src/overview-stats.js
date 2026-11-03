export function faceStatsView(stats) {
    if (!stats) {
        return { rate: '—', spoofDetected: '—', description: 'Chưa tải được thống kê khuôn mặt.' };
    }
    const rate = Number.isFinite(stats.successRate)
        ? `${stats.successRate.toLocaleString('vi-VN', { maximumFractionDigits: 1 })}%`
        : '—';
    return {
        rate,
        spoofDetected: stats.spoofDetected,
        description: stats.attempts === 0
            ? 'Chưa có lượt xác thực khuôn mặt hôm nay.'
            : `${stats.granted}/${stats.attempts} lượt quét được cấp lệnh mở (gồm cả lượt lỗi trong tổng). Không phải độ chính xác AI hoặc tỷ lệ cửa mở.`,
    };
}
