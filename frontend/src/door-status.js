// Honest wording for command/device state (docs/DEVICE_CONTRACT.md): a publish or an actuator ACK
// is never presented as "the door is open" because there is no door sensor.
export const STATUS_POLL_MS = 2000;

const CONNECTION = {
  ONLINE: { label: 'Thiết bị trực tuyến', tone: 'ok' },
  STALE: { label: 'Thiết bị không phản hồi gần đây', tone: 'warn' },
  OFFLINE: { label: 'Thiết bị ngoại tuyến', tone: 'bad' },
};

export function connectionLabel(connection) {
  return CONNECTION[connection] || { label: 'Chưa rõ trạng thái thiết bị', tone: 'warn' };
}

const COMMAND = {
  PENDING: { label: 'Đang chuẩn bị gửi lệnh', tone: 'warn' },
  PUBLISHED: { label: 'Đã gửi lệnh, chờ thiết bị nhận', tone: 'warn' },
  RECEIVED: { label: 'Thiết bị đã nhận lệnh, chờ thực thi', tone: 'warn' },
  EXECUTED: { label: 'Thiết bị đã điều khiển khóa (không có cảm biến cửa)', tone: 'ok' },
  REJECTED: { label: 'Thiết bị từ chối lệnh', tone: 'bad' },
  FAILED: { label: 'Không gửi được lệnh tới thiết bị', tone: 'bad' },
  UNKNOWN: { label: 'Không rõ kết quả (không nhận được xác nhận)', tone: 'bad' },
};

export function commandLabel(status) {
  return COMMAND[status] || { label: 'Trạng thái lệnh chưa xác định', tone: 'warn' };
}

const METHOD = { FACE: 'Khuôn mặt (camera cửa)', PIN: 'PIN bàn phím', REMOTE_ADMIN: 'Quản trị viên mở từ xa', ADMIN: 'Quản trị viên' };

export function methodLabel(method) {
  return METHOD[method] || 'Không xác định';
}

const COMMAND_TYPE = { UNLOCK: 'Mở khóa', LOCKDOWN_ON: 'Bật khóa khẩn cấp', LOCKDOWN_OFF: 'Tắt khóa khẩn cấp' };

export function commandTypeLabel(type) {
  return COMMAND_TYPE[type] || type || '';
}

export function lockdownLabel(lockdown, applied) {
  if (!lockdown) return applied ? 'Bình thường' : 'Bình thường (chờ thiết bị xác nhận)';
  return applied ? 'Đang khóa khẩn cấp (thiết bị đã xác nhận)' : 'Đã lưu khóa khẩn cấp, chờ thiết bị xác nhận';
}

// Access-log rows mirror command status as COMMAND_<STATUS>; other values are decision failures.
export function accessStatusLabel(status) {
  if (typeof status === 'string' && status.startsWith('COMMAND_')) return commandLabel(status.slice(8));
  if (status === 'SUCCESS') return { label: 'Đã gửi lệnh (bản ghi cũ)', tone: 'warn' };
  return { label: 'Từ chối / lỗi', tone: 'bad' };
}

export const TONE_CLASSES = {
  ok: 'bg-green-50 text-green-700',
  warn: 'bg-amber-50 text-amber-700',
  bad: 'bg-red-50 text-red-700',
};
