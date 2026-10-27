import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ShieldCheck, ShieldAlert, ScanFace, KeyRound, Loader2 } from 'lucide-react';
import api from './api';
import { STATUS_POLL_MS, TONE_CLASSES, commandLabel, commandTypeLabel, connectionLabel, methodLabel } from './door-status';

// Public status screen only. Unlocking happens at the door: the ESP32 camera or keypad sends to the cloud
// with device credentials. The browser has no unlock route and holds no device secret (D02, D04).
const Kiosk = () => {
    const [door, setDoor] = useState(null);
    const [error, setError] = useState('');

    useEffect(() => {
        let active = true;
        const load = async () => {
            try {
                const { data } = await api.get('/door-status');
                if (active) { setDoor(data); setError(''); }
            } catch {
                if (active) setError('Không kết nối được máy chủ cloud. Trạng thái có thể đã cũ.');
            }
        };
        load();
        const timer = setInterval(load, STATUS_POLL_MS);
        return () => { active = false; clearInterval(timer); };
    }, []);

    const connection = connectionLabel(door?.connection);
    const last = door?.lastCommand;
    const lastStatus = last ? commandLabel(last.status) : null;

    return (
        <div className="min-h-screen bg-[#F9FAFB] flex flex-col font-sans relative selection:bg-gray-200">
            <header className="w-full p-6 flex justify-between items-center bg-white border-b border-gray-200">
                <div className="flex items-center gap-3">
                    <div className="w-10 h-10 bg-gray-900 text-white rounded-xl flex items-center justify-center shadow-sm">
                        <ShieldCheck size={20} />
                    </div>
                    <div>
                        <span className="font-semibold text-lg text-gray-900 tracking-tight block">SmartLock AIoT</span>
                        <div className="flex items-center gap-2 text-xs text-gray-500">
                            <span className={`inline-block w-2 h-2 rounded-full ${connection.tone === 'ok' ? 'bg-green-500' : connection.tone === 'bad' ? 'bg-red-500' : 'bg-amber-500'}`}></span>
                            <span>{door ? connection.label : 'Đang tải trạng thái…'}</span>
                        </div>
                    </div>
                </div>
                <Link to="/admin" className="text-sm font-medium text-gray-500 hover:text-gray-900 transition-colors">
                    Admin Portal
                </Link>
            </header>

            {door?.lockdown && (
                <div role="alert" className="w-full bg-red-600 text-white px-6 py-3 shadow-md flex items-center justify-center gap-2 text-sm font-bold tracking-wide">
                    <ShieldAlert size={20} />
                    <span>
                        CỬA ĐANG BỊ VÔ HIỆU HÓA KHẨN CẤP. MỌI YÊU CẦU MỞ CỬA BỊ TỪ CHỐI
                        {door.lockdownApplied ? '' : ' (chờ thiết bị xác nhận)'}.
                    </span>
                </div>
            )}

            <main className="flex-1 flex flex-col items-center justify-center p-6">
                <div className="w-full max-w-xl mx-auto flex flex-col items-center" aria-live="polite">
                    <div className="text-center mb-8">
                        <h1 className="text-2xl font-bold text-gray-900 tracking-tight mb-2">Mở khóa tại cửa</h1>
                        <p className="text-gray-500 text-sm">Nhìn vào camera trên thiết bị (hoặc bấm phím D), hoặc nhập PIN trên bàn phím rồi bấm #.</p>
                    </div>

                    <div className="w-full grid grid-cols-1 sm:grid-cols-2 gap-4 mb-6">
                        <div className="bg-white rounded-2xl border border-gray-100 p-5 flex items-start gap-3">
                            <ScanFace className="text-gray-700 shrink-0" size={22} />
                            <p className="text-sm text-gray-600">Ảnh chỉ được chụp bởi camera cửa đã xác thực; liveness và nhận diện chạy trên cloud.</p>
                        </div>
                        <div className="bg-white rounded-2xl border border-gray-100 p-5 flex items-start gap-3">
                            <KeyRound className="text-gray-700 shrink-0" size={22} />
                            <p className="text-sm text-gray-600">PIN 6–10 chữ số nhập trên bàn phím của thiết bị. Trang này không nhận PIN.</p>
                        </div>
                    </div>

                    <section className="w-full bg-white rounded-2xl shadow-[0_2px_8px_rgba(0,0,0,0.04)] border border-gray-100 p-6">
                        <h2 className="text-sm font-semibold text-gray-900 mb-4">Lệnh gần nhất</h2>
                        {!door && !error && (
                            <p className="text-sm text-gray-500 flex items-center gap-2"><Loader2 className="animate-spin" size={16} /> Đang tải…</p>
                        )}
                        {door && !last && <p className="text-sm text-gray-500">Chưa có lệnh nào.</p>}
                        {last && (
                            <div className="flex items-center justify-between gap-4">
                                <div>
                                    <p className="text-sm font-medium text-gray-900">{commandTypeLabel(last.type)} · {methodLabel(last.method)}</p>
                                    <p className="text-xs text-gray-500 mt-1">{new Date(last.issuedAt).toLocaleString('vi-VN')}</p>
                                </div>
                                <span className={`text-xs font-medium px-2 py-1 rounded-full text-right ${TONE_CLASSES[lastStatus.tone]}`}>
                                    {lastStatus.label}
                                </span>
                            </div>
                        )}
                        {door?.lastScan && (
                            <div className="mt-4 pt-4 border-t border-gray-100">
                                <p className="text-xs text-gray-500">Lần quét khuôn mặt gần nhất · {new Date(door.lastScan.capturedAt).toLocaleString('vi-VN')}</p>
                                <p className="text-sm font-medium text-gray-900 mt-1">{door.lastScan.message}</p>
                            </div>
                        )}
                        {error && <p role="alert" className="text-sm text-red-700 mt-4">{error}</p>}
                    </section>
                </div>
            </main>
        </div>
    );
};

export default Kiosk;
