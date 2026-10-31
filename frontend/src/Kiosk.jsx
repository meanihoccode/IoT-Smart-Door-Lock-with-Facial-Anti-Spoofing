import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ShieldCheck, ShieldAlert, ScanFace, KeyRound, Loader2, Wifi, CheckCircle2, XCircle } from 'lucide-react';
import api from './api';
import { useLiveFrame } from './use-live-frame';
import { KIOSK_POLL_MS, kioskView } from './kiosk-feedback';
import { TONE_CLASSES, commandLabel, commandTypeLabel, connectionLabel, methodLabel } from './door-status';

// Public status screen. Unlocking happens at the door: the ESP32 camera (PIR) or keypad sends to the cloud
// with device credentials. The browser has no unlock route and holds no device secret (D02, D04).
// The live camera is display-only and public by the user's choice (D11); names are masked by the cloud.
const MESSAGE_CLASSES = {
    success: 'bg-green-50 text-green-700 font-bold',
    danger: 'bg-red-600 text-white font-bold',
    warn: 'bg-red-50 text-red-700',
    info: 'text-gray-600',
};

const Kiosk = () => {
    const [door, setDoor] = useState(null);
    const [error, setError] = useState('');
    const { src, waiting } = useLiveFrame('/kiosk/live');

    useEffect(() => {
        let active = true, timer;
        const load = async () => {
            try {
                const { data } = await api.get('/door-status');
                if (active) { setDoor(data); setError(''); }
            } catch {
                if (active) setError('Không kết nối được máy chủ cloud. Trạng thái có thể đã cũ.');
            }
            if (active) timer = setTimeout(load, KIOSK_POLL_MS);
        };
        load();
        return () => { active = false; clearTimeout(timer); };
    }, []);

    const view = kioskView(door);
    const connection = connectionLabel(door?.connection);
    const last = door?.lastCommand;
    const lastStatus = last ? commandLabel(last.status) : null;
    const scanning = view.status === 'SCANNING';

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
                            <span className={`inline-block w-2 h-2 rounded-full ${door?.lockdown ? 'bg-red-500 animate-pulse' : connection.tone === 'ok' ? 'bg-green-500' : connection.tone === 'bad' ? 'bg-red-500' : 'bg-amber-500'}`}></span>
                            <span>{door?.lockdown ? 'HỆ THỐNG ĐANG BỊ VÔ HIỆU HÓA' : `Camera ESP32-S3 · ${door ? connection.label : 'Đang tải…'}`}</span>
                        </div>
                    </div>
                </div>
                <Link to="/admin" className="text-sm font-medium text-gray-500 hover:text-gray-900 transition-colors">
                    Admin Portal
                </Link>
            </header>

            {door?.lockdown && (
                <div role="alert" className="w-full bg-red-600 text-white px-6 py-3 shadow-md flex items-center justify-center gap-2 text-sm font-bold tracking-wide animate-pulse">
                    <ShieldAlert size={20} />
                    <span>
                        CỬA ĐANG BỊ VÔ HIỆU HÓA KHẨN CẤP BỞI QUẢN TRỊ VIÊN. MỌI YÊU CẦU MỞ CỬA BỊ TỪ CHỐI
                        {door.lockdownApplied ? '!' : ' (chờ thiết bị xác nhận).'}
                    </span>
                </div>
            )}

            <main className="flex-1 flex flex-col items-center justify-center p-6">
                <div className="w-full max-w-xl mx-auto flex flex-col items-center">
                    <div className="text-center mb-8">
                        <h1 className="text-2xl font-bold text-gray-900 tracking-tight mb-2">Xác thực danh tính</h1>
                        <p className="text-gray-500 text-sm">Đứng trước camera ở cửa để quét khuôn mặt, hoặc nhập PIN trên bàn phím rồi bấm #.</p>
                    </div>

                    <div className="w-full bg-white rounded-2xl shadow-[0_2px_8px_rgba(0,0,0,0.04)] border border-gray-100 p-6 overflow-hidden">
                        <div className="relative w-full aspect-[4/3] bg-gray-900 rounded-xl overflow-hidden border border-gray-200 mb-6 flex items-center justify-center">
                            {src && (
                                <img src={src} alt="Camera ESP32-S3 trực tiếp"
                                     className={`w-full h-full object-cover transition-opacity duration-200 ${scanning ? 'opacity-50 grayscale' : 'opacity-100'}`} />
                            )}

                            {waiting && !src && (
                                <div className="absolute inset-0 bg-gray-900/90 text-white flex flex-col items-center justify-center p-6 text-center">
                                    <Wifi size={32} className="text-yellow-400 mb-3 animate-pulse" />
                                    <p className="text-sm font-medium mb-1">Đang chờ hình từ Camera OV2640</p>
                                    <p className="text-xs text-gray-400">Thiết bị cửa phải trực tuyến với cloud.</p>
                                </div>
                            )}

                            {view.status === 'IDLE' && src && (
                                <div className="absolute inset-0 pointer-events-none flex items-center justify-center">
                                    <div className="w-48 h-64 border-2 border-white/50 rounded-3xl shadow-sm"></div>
                                </div>
                            )}

                            {scanning && (
                                <div className="absolute inset-0 flex flex-col items-center justify-center bg-black/30">
                                    <Loader2 size={36} className="text-white animate-spin mb-3" />
                                    <span className="text-xs font-semibold text-white tracking-wide">ĐANG PHÂN TÍCH AI...</span>
                                </div>
                            )}

                            {view.status === 'SUCCESS' && (
                                <div className="absolute inset-0 flex items-center justify-center bg-green-600/25 pointer-events-none">
                                    <CheckCircle2 size={72} className="text-white drop-shadow-lg" />
                                </div>
                            )}

                            {view.status === 'FAILED' && (
                                <div className={`absolute inset-0 flex items-center justify-center pointer-events-none ${view.tone === 'danger' ? 'bg-red-700/40' : 'bg-red-600/20'}`}>
                                    {view.tone === 'danger'
                                        ? <ShieldAlert size={72} className="text-white drop-shadow-lg" />
                                        : <XCircle size={72} className="text-white drop-shadow-lg" />}
                                </div>
                            )}
                        </div>

                        <div aria-live="polite" className={`w-full text-center py-3 px-3 rounded-lg text-sm font-medium transition-colors ${MESSAGE_CLASSES[view.tone]}`}>
                            {view.message}
                        </div>
                        {error && <p role="alert" className="text-sm text-red-700 mt-3 text-center">{error}</p>}
                    </div>

                    <div className="w-full grid grid-cols-1 sm:grid-cols-2 gap-4 mt-6">
                        <div className="bg-white rounded-2xl border border-gray-100 p-4 flex items-start gap-3">
                            <ScanFace className="text-gray-700 shrink-0" size={20} />
                            <p className="text-xs text-gray-600">Camera cửa tự chụp khi phát hiện người; chống giả mạo và nhận diện chạy trên cloud.</p>
                        </div>
                        <div className="bg-white rounded-2xl border border-gray-100 p-4 flex items-start gap-3">
                            <KeyRound className="text-gray-700 shrink-0" size={20} />
                            <p className="text-xs text-gray-600">PIN 6–10 chữ số nhập trên bàn phím của thiết bị. Trang này không nhận PIN.</p>
                        </div>
                    </div>

                    {last && (
                        <div className="w-full mt-4 flex items-center justify-between gap-4 text-xs text-gray-500 px-1">
                            <span>Lệnh gần nhất: {commandTypeLabel(last.type)} · {methodLabel(last.method)} · {new Date(last.issuedAt).toLocaleTimeString('vi-VN')}</span>
                            <span className={`font-medium px-2 py-1 rounded-full text-right ${TONE_CLASSES[lastStatus.tone]}`}>{lastStatus.label}</span>
                        </div>
                    )}
                </div>
            </main>
        </div>
    );
};

export default Kiosk;
