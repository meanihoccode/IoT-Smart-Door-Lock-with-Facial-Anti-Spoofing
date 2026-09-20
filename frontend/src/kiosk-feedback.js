const FACE_ERROR_MESSAGES = {
    NO_ENROLLMENT: 'Chưa có khuôn mặt nào được đăng ký. Hãy liên hệ quản trị viên.',
    NO_FACE: 'Không tìm thấy khuôn mặt. Hãy nhìn thẳng vào camera và thử lại.',
    MULTIPLE_FACES: 'Chỉ một người được đứng trước camera.',
    LOW_QUALITY: 'Ảnh chưa đủ rõ. Hãy giữ yên và bảo đảm khuôn mặt đủ sáng.',
    LIVENESS_UNCERTAIN: 'Chưa xác định được khuôn mặt thật. Hãy thử lại trong điều kiện sáng hơn.',
    SPOOF_DETECTED: 'Phát hiện dấu hiệu giả mạo. Từ chối truy cập.',
    NOT_RECOGNIZED: 'Khuôn mặt chưa được nhận diện hoặc chưa đăng ký.',
    MODEL_UNAVAILABLE: 'Dịch vụ AI chưa sẵn sàng. Vui lòng thử lại sau.',
    DB_UNAVAILABLE: 'Không thể đọc dữ liệu khuôn mặt. Vui lòng thử lại sau.',
    INVALID_TEMPLATE: 'Dữ liệu khuôn mặt đăng ký đang có lỗi. Hãy liên hệ quản trị viên.',
    INVALID_IMAGE: 'Ảnh chụp không hợp lệ. Hãy thử lại.',
    INVALID_AI_RESPONSE: 'Kết quả AI không hợp lệ. Vui lòng thử lại sau.',
    INTERNAL_ERROR: 'Máy chủ gặp lỗi khi xác thực. Vui lòng thử lại sau.',
};

export function faceFailureMessage(payload, fallbackMessage) {
    if (payload?.reasonCode === 'PROFILE_NOT_ALLOWED') return 'Hồ sơ không được phép vào. Hãy liên hệ quản trị viên.';
    if (payload?.reasonCode === 'COMMAND_FAILED') return 'Chưa xác nhận được việc gửi lệnh. Kiểm tra trạng thái cửa trước khi thử lại.';
    return FACE_ERROR_MESSAGES[payload?.reasonCode] || payload?.message || fallbackMessage;
}

export const FACE_TIMEOUT_MS = 35000; // Core: AI connect 3s + read 20s + MQTT wait 5s, plus overhead.
export const PIN_TIMEOUT_MS = 10000;
export const UNCERTAIN_REQUEST_MESSAGE = 'Yêu cầu quá thời gian hoặc mất kết nối. Có thể lệnh đã được gửi; hãy kiểm tra trạng thái cửa trước khi thử lại.';
