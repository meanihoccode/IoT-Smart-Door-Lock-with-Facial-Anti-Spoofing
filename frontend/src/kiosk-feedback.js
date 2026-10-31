// Kiosk wording for door activity reported by the cloud (/api/door-status). The kiosk never sends images or PINs;
// it only shows what the authenticated door device did. "Đã mở khóa" means the device actuated the lock
// (EXECUTED ACK), not a door sensor.
const DENIAL_MESSAGES = {
    SPOOF_DETECTED: 'Cảnh báo: phát hiện khuôn mặt giả mạo (ảnh hoặc màn hình điện thoại). Từ chối truy cập!',
    NOT_RECOGNIZED: 'Người lạ: khuôn mặt chưa được đăng ký. Từ chối truy cập.',
    PROFILE_NOT_ALLOWED: 'Hồ sơ không được phép vào. Hãy liên hệ quản trị viên.',
    NO_ENROLLMENT: 'Chưa có khuôn mặt nào được đăng ký. Hãy liên hệ quản trị viên.',
    NO_FACE: 'Không tìm thấy khuôn mặt. Hãy nhìn thẳng vào camera và thử lại.',
    MULTIPLE_FACES: 'Chỉ một người được đứng trước camera.',
    LOW_QUALITY: 'Ảnh chưa đủ rõ. Hãy giữ yên và bảo đảm khuôn mặt đủ sáng.',
    LIVENESS_UNCERTAIN: 'Chưa xác định được khuôn mặt thật. Hãy thử lại trong điều kiện sáng hơn.',
    MODEL_UNAVAILABLE: 'Dịch vụ AI chưa sẵn sàng. Vui lòng thử lại sau.',
    DB_UNAVAILABLE: 'Không thể đọc dữ liệu khuôn mặt. Vui lòng thử lại sau.',
    INVALID_TEMPLATE: 'Dữ liệu khuôn mặt đăng ký đang có lỗi. Hãy liên hệ quản trị viên.',
    INVALID_IMAGE: 'Ảnh chụp không hợp lệ. Hãy thử lại.',
    INVALID_AI_RESPONSE: 'Kết quả AI không hợp lệ. Cửa giữ nguyên khóa.',
    INTERNAL_ERROR: 'Máy chủ gặp lỗi khi xác thực. Vui lòng thử lại sau.',
    PIN_REJECTED: 'Mã PIN không đúng. Vui lòng thử lại.',
};

export const KIOSK_POLL_MS = 1000;
export const IDLE_MESSAGE = 'Vui lòng đứng trước camera, hệ thống sẽ tự quét khuôn mặt.';
const RESULT_MS = 8000;   // how long a result stays on screen
const OPENING_MS = 20000; // granted, waiting for the device ACK
const SCAN_MS = 12000;    // PIR motion without a result yet

export function denialMessage(reasonCode) {
    return DENIAL_MESSAGES[reasonCode] || 'Không thể xác thực. Cửa giữ nguyên khóa.';
}

// Returns { status: IDLE|SCANNING|SUCCESS|FAILED|LOCKED, tone, message }. Ages use the server clock.
export function kioskView(door) {
    if (!door) return { status: 'IDLE', tone: 'info', message: 'Đang kết nối máy chủ…' };
    const now = Date.parse(door.serverTime) || Date.now();
    const age = (ts) => (ts ? now - Date.parse(ts) : Infinity);
    if (door.lockdown) return { status: 'LOCKED', tone: 'danger', message: 'Cửa đang bị vô hiệu hóa khẩn cấp bởi Quản trị viên.' };

    const attempt = door.lastAttempt;
    if (attempt) {
        const granted = attempt.reasonCode === 'FACE_VERIFIED' || attempt.reasonCode === 'PIN_VERIFIED';
        const who = attempt.person || 'bạn';
        const via = attempt.method === 'PIN' ? 'mã PIN' : 'khuôn mặt';
        if (granted && age(attempt.at) < OPENING_MS) {
            const done = attempt.commandStatus === 'EXECUTED';
            const failed = ['REJECTED', 'FAILED', 'UNKNOWN'].includes(attempt.commandStatus);
            if ((done || failed) && age(attempt.at) >= RESULT_MS + 4000) return idle(door);
            if (done) return { status: 'SUCCESS', tone: 'success', message: `Xin chào ${who}! Đã xác thực bằng ${via} và mở khóa, mời vào.` };
            if (failed) return { status: 'FAILED', tone: 'warn', message: `Đã nhận diện ${who} nhưng thiết bị chưa mở khóa. Vui lòng thử lại.` };
            return { status: 'SCANNING', tone: 'info', message: `Đã nhận diện ${who}. Đang mở khóa…` };
        }
        if (attempt.reasonCode === 'COMMAND_FAILED' && age(attempt.at) < RESULT_MS)
            return { status: 'FAILED', tone: 'warn', message: `Đã nhận diện ${who} nhưng không gửi được lệnh tới cửa. Kiểm tra kết nối thiết bị.` };
        if (!granted && age(attempt.at) < RESULT_MS)
            return { status: 'FAILED', tone: attempt.reasonCode === 'SPOOF_DETECTED' ? 'danger' : 'warn', message: denialMessage(attempt.reasonCode) };
    }

    if (age(door.motionAt) < SCAN_MS && (!attempt || Date.parse(door.motionAt) > Date.parse(attempt.at)))
        return { status: 'SCANNING', tone: 'info', message: 'Phát hiện người trước cửa. Đang phân tích khuôn mặt bằng AI…' };

    const cmd = door.lastCommand;
    if (cmd?.method === 'REMOTE_ADMIN' && cmd.status === 'EXECUTED' && age(cmd.updatedAt !== 'null' ? cmd.updatedAt : cmd.issuedAt) < RESULT_MS)
        return { status: 'SUCCESS', tone: 'success', message: 'Quản trị viên đã mở khóa từ xa.' };
    return idle(door);
}

function idle(door) {
    return door.connection === 'ONLINE'
        ? { status: 'IDLE', tone: 'info', message: IDLE_MESSAGE }
        : { status: 'IDLE', tone: 'warn', message: 'Thiết bị cửa đang mất kết nối với cloud.' };
}
