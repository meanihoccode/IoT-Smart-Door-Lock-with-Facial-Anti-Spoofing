import React, { useState, useEffect } from 'react';
import api, { errorMessage } from './api';
import { 
    AlertTriangle, 
    ShieldAlert, 
    ShieldCheck, 
    Lock, 
    Unlock, 
    CheckCircle2, 
    RefreshCw, 
    Clock, 
    UserX, 
    Eye,
    X,
    PhoneCall
} from 'lucide-react';

export default function SecurityAlerts() {
    const [alerts, setAlerts] = useState([]);
    const [lockdown, setLockdown] = useState(false);
    const [loading, setLoading] = useState(true);
    const [lockdownBusy, setLockdownBusy] = useState(false);
    const [error, setError] = useState('');
    const [successMsg, setSuccessMsg] = useState('');
    const [selectedImage, setSelectedImage] = useState(null);

    const fetchData = async () => {
        try {
            const [alertsRes, lockdownRes] = await Promise.all([
                api.get('/security-alerts'),
                api.get('/lockdown/status')
            ]);
            setAlerts(alertsRes.data || []);
            setLockdown(Boolean(lockdownRes.data?.lockdown));
            setError('');
        } catch (err) {
            setError(errorMessage(err));
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        fetchData();
        const interval = setInterval(fetchData, 4000);
        return () => clearInterval(interval);
    }, []);

    const handleToggleLockdown = async () => {
        if (!window.confirm(
            lockdown 
                ? "Bạn có chắc chắn muốn TẮT chế độ vô hiệu hóa và cho phép mở cửa trở lại?" 
                : "CẢNH BÁO: Bật vô hiệu hóa sẽ CHẶN TOÀN BỘ việc mở cửa bằng cả Mã PIN và Quét khuôn mặt! Bạn có chắc chắn không?"
        )) return;

        setLockdownBusy(true);
        setError('');
        try {
            const res = await api.post('/lockdown/toggle', { enabled: !lockdown });
            setLockdown(res.data.lockdown);
            setSuccessMsg(res.data.message);
            setTimeout(() => setSuccessMsg(''), 5000);
        } catch (err) {
            setError(errorMessage(err));
        } finally {
            setLockdownBusy(false);
        }
    };

    const handleResolve = async (id) => {
        try {
            await api.post(`/security-alerts/${id}/resolve`);
            setAlerts(prev => prev.map(a => a.id === id ? { ...a, resolved: true } : a));
            setSuccessMsg('Đã đánh dấu xử lý cảnh báo.');
            setTimeout(() => setSuccessMsg(''), 3000);
        } catch (err) {
            setError(errorMessage(err));
        }
    };

    const formatTime = (timeStr) => {
        if (!timeStr) return '';
        const d = new Date(timeStr);
        return d.toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit', second: '2-digit' }) + 
               ' - ' + d.toLocaleDateString('vi-VN');
    };

    const getAlertBadge = (type) => {
        if (type.includes('PIR') || type.includes('5_TIMES') || type.includes('INTRUDER')) {
            return (
                <span className="inline-flex items-center gap-1 px-2.5 py-1 rounded-full text-xs font-semibold bg-red-100 text-red-800 border border-red-200">
                    <UserX size={13} /> Kẻ lạ quét mặt &gt; 5 lần
                </span>
            );
        }
        if (type.includes('PIN')) {
            return (
                <span className="inline-flex items-center gap-1 px-2.5 py-1 rounded-full text-xs font-semibold bg-amber-100 text-amber-800 border border-amber-200">
                    <AlertTriangle size={13} /> Nhập sai mã PIN 3 lần
                </span>
            );
        }
        return (
            <span className="inline-flex items-center gap-1 px-2.5 py-1 rounded-full text-xs font-semibold bg-gray-100 text-gray-800">
                <AlertTriangle size={13} /> {type}
            </span>
        );
    };

    return (
        <div className="max-w-6xl mx-auto space-y-6">
            {/* Header & Status messages */}
            <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4">
                <div>
                    <h2 className="text-2xl font-bold tracking-tight text-gray-900 flex items-center gap-2">
                        <ShieldAlert className="text-red-600" />
                        Cảnh báo an ninh &amp; Khóa khẩn cấp
                    </h2>
                    <p className="text-sm text-gray-500 mt-1">
                        Theo dõi ảnh chụp kẻ lạ gửi từ Camera ESP32-S3 và quản lý trạng thái vô hiệu hóa cửa.
                    </p>
                </div>
                <button 
                    onClick={fetchData} 
                    className="flex items-center gap-1.5 px-3 py-2 text-xs font-medium text-gray-600 bg-white border border-gray-200 rounded-lg hover:bg-gray-50 shadow-sm"
                >
                    <RefreshCw size={14} className={loading ? 'animate-spin' : ''} />
                    Làm mới
                </button>
            </div>

            {error && (
                <div className="p-4 bg-red-50 border border-red-200 rounded-xl text-red-700 text-sm font-medium">
                    {error}
                </div>
            )}

            {successMsg && (
                <div className="p-4 bg-green-50 border border-green-200 rounded-xl text-green-700 text-sm font-medium flex items-center gap-2">
                    <CheckCircle2 size={16} />
                    {successMsg}
                </div>
            )}

            {/* EMERGENCY LOCKDOWN CARD */}
            <div className={`p-6 rounded-2xl border transition-all ${
                lockdown 
                    ? 'bg-red-500 text-white border-red-600 shadow-lg shadow-red-200' 
                    : 'bg-white border-gray-200 shadow-sm'
            }`}>
                <div className="flex flex-col md:flex-row justify-between items-start md:items-center gap-4">
                    <div className="flex items-start gap-4">
                        <div className={`p-3.5 rounded-2xl ${
                            lockdown ? 'bg-white/20 text-white animate-pulse' : 'bg-gray-100 text-gray-700'
                        }`}>
                            {lockdown ? <Lock size={28} /> : <Unlock size={28} />}
                        </div>
                        <div>
                            <div className="flex items-center gap-2">
                                <h3 className={`text-lg font-bold ${lockdown ? 'text-white' : 'text-gray-900'}`}>
                                    {lockdown 
                                        ? 'CỬA ĐANG BỊ VÔ HIỆU HÓA (KHÓA KHẨN CẤP)' 
                                        : 'Chế độ Vô hiệu hóa mở cửa (Emergency Lockdown)'}
                                </h3>
                                <span className={`px-2 py-0.5 rounded-full text-xs font-bold uppercase tracking-wider ${
                                    lockdown ? 'bg-white text-red-700' : 'bg-green-100 text-green-800'
                                }`}>
                                    {lockdown ? 'ĐANG KÍCH HOẠT' : 'BÌNH THƯỜNG'}
                                </span>
                            </div>
                            <p className={`text-sm mt-1 max-w-2xl ${lockdown ? 'text-white/90' : 'text-gray-500'}`}>
                                {lockdown 
                                    ? 'Cửa bị khóa cứng! Mọi nỗ lực mở cửa bằng cả Quét khuôn mặt AI và Bấm mã PIN đều bị từ chối cho đến khi Quản trị viên tắt nút này.' 
                                    : 'Khi phát hiện kẻ lạ đáng ngờ qua camera, bấm nút bên dưới để vô hiệu hóa hoàn toàn cửa và bảo vệ an toàn tòa nhà.'}
                            </p>
                        </div>
                    </div>

                    <div className="flex flex-col sm:flex-row gap-2.5 w-full md:w-auto">
                        <button
                            onClick={handleToggleLockdown}
                            disabled={lockdownBusy}
                            className={`px-5 py-3 rounded-xl font-bold text-sm transition-all shadow-md active:scale-95 flex items-center justify-center gap-2 ${
                                lockdown 
                                    ? 'bg-white text-red-600 hover:bg-red-50' 
                                    : 'bg-red-600 text-white hover:bg-red-700'
                            }`}
                        >
                            {lockdown ? <Unlock size={18} /> : <Lock size={18} />}
                            {lockdownBusy 
                                ? 'Đang xử lý...' 
                                : lockdown 
                                    ? 'TẮT VÔ HIỆU HÓA (MỞ LẠI CỬA)' 
                                    : 'KHÓA CỬA KHẨN CẤP & GỌI BẢO VỆ'}
                        </button>
                    </div>
                </div>
            </div>

            {/* ALERTS SECTION */}
            <div className="space-y-4">
                <div className="flex justify-between items-center">
                    <h3 className="text-lg font-bold text-gray-900 flex items-center gap-2">
                        <span>Danh sách cảnh báo kẻ lạ &amp; sự cố</span>
                        <span className="text-xs bg-gray-200 text-gray-700 px-2 py-0.5 rounded-full font-medium">
                            {alerts.length} sự kiện
                        </span>
                    </h3>
                </div>

                {loading && !alerts.length ? (
                    <div className="p-12 text-center text-gray-500 bg-white rounded-2xl border border-gray-100">
                        Đang tải danh sách cảnh báo...
                    </div>
                ) : !alerts.length ? (
                    <div className="p-12 text-center bg-white rounded-2xl border border-gray-200">
                        <div className="w-12 h-12 bg-green-50 text-green-600 rounded-full flex items-center justify-center mx-auto mb-3">
                            <ShieldCheck size={24} />
                        </div>
                        <h4 className="font-semibold text-gray-800">Không có cảnh báo nào</h4>
                        <p className="text-sm text-gray-500 mt-1">Hệ thống an toàn, chưa ghi nhận trường hợp kẻ lạ hoặc sai PIN liên tiếp.</p>
                    </div>
                ) : (
                    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-5">
                        {alerts.map((alert) => (
                            <div 
                                key={alert.id} 
                                className={`bg-white rounded-2xl border overflow-hidden flex flex-col justify-between transition-shadow hover:shadow-md ${
                                    alert.resolved ? 'border-gray-200 opacity-75' : 'border-red-200 ring-1 ring-red-100 shadow-sm'
                                }`}
                            >
                                {/* Photo Container */}
                                <div className="relative aspect-[4/3] bg-gray-950 overflow-hidden group">
                                    {alert.imageUrl ? (
                                        <>
                                            <img 
                                                src={alert.imageUrl} 
                                                alt="Ảnh kẻ lạ"
                                                className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" 
                                            />
                                            <button 
                                                onClick={() => setSelectedImage(alert.imageUrl)}
                                                className="absolute bottom-2 right-2 p-2 bg-black/60 hover:bg-black/90 text-white rounded-lg text-xs flex items-center gap-1 backdrop-blur-sm"
                                            >
                                                <Eye size={14} /> Phóng to
                                            </button>
                                        </>
                                    ) : (
                                        <div className="w-full h-full flex flex-col items-center justify-center text-gray-500 text-xs gap-2">
                                            <UserX size={32} />
                                            <span>Không có ảnh đính kèm</span>
                                        </div>
                                    )}

                                    <div className="absolute top-2 left-2">
                                        {getAlertBadge(alert.alertType)}
                                    </div>

                                    {alert.resolved && (
                                        <div className="absolute top-2 right-2 bg-green-600 text-white text-[10px] font-bold px-2 py-0.5 rounded-full uppercase">
                                            Đã xử lý
                                        </div>
                                    )}
                                </div>

                                {/* Content */}
                                <div className="p-4 flex-1 flex flex-col justify-between">
                                    <div>
                                        <div className="flex items-center gap-1.5 text-xs text-gray-500 mb-2">
                                            <Clock size={13} />
                                            <span>{formatTime(alert.createdAt)}</span>
                                        </div>
                                        <p className="text-sm font-semibold text-gray-900 leading-snug">
                                            {alert.description}
                                        </p>
                                    </div>

                                    {/* Action Buttons */}
                                    <div className="mt-4 pt-3 border-t border-gray-100 flex items-center justify-between gap-2">
                                        {!alert.resolved ? (
                                            <button 
                                                onClick={() => handleResolve(alert.id)}
                                                className="flex-1 py-2 px-3 text-xs font-semibold bg-gray-100 hover:bg-gray-200 text-gray-800 rounded-lg transition-colors flex items-center justify-center gap-1"
                                            >
                                                <CheckCircle2 size={14} /> Đã xử lý
                                            </button>
                                        ) : (
                                            <span className="text-xs text-gray-400 italic">Đã giải quyết</span>
                                        )}

                                        {!lockdown && (
                                            <button 
                                                onClick={handleToggleLockdown}
                                                title="Khóa cửa ngay lập tức"
                                                className="py-2 px-3 text-xs font-semibold bg-red-50 hover:bg-red-100 text-red-700 rounded-lg transition-colors flex items-center gap-1"
                                            >
                                                <Lock size={13} /> Khóa cửa
                                            </button>
                                        )}
                                    </div>
                                </div>
                            </div>
                        ))}
                    </div>
                )}
            </div>

            {/* IMAGE ZOOM MODAL */}
            {selectedImage && (
                <div 
                    className="fixed inset-0 z-50 bg-black/80 flex items-center justify-center p-4 backdrop-blur-sm"
                    onClick={() => setSelectedImage(null)}
                >
                    <div className="relative max-w-3xl max-h-[90vh] bg-gray-900 rounded-2xl overflow-hidden shadow-2xl" onClick={e => e.stopPropagation()}>
                        <button 
                            onClick={() => setSelectedImage(null)}
                            className="absolute top-3 right-3 p-2 bg-black/50 hover:bg-black text-white rounded-full transition-colors z-10"
                        >
                            <X size={20} />
                        </button>
                        <img 
                            src={selectedImage} 
                            alt="Chi tiết ảnh kẻ lạ" 
                            className="max-h-[85vh] w-auto object-contain"
                        />
                    </div>
                </div>
            )}
        </div>
    );
}
