import React, { useEffect, useState } from 'react';
import api from './api';

const FRAME_POLL_MS = 350;

// Live door-camera view for admins. Display only: frames never go to the AI or open the door.
// Polling keeps the device streaming; it stops when the tab is hidden or the view is turned off.
export default function LiveCamera() {
    const [on, setOn] = useState(true);
    const [src, setSrc] = useState(null);
    const [waiting, setWaiting] = useState(true);

    useEffect(() => {
        if (!on) return undefined;
        let active = true, timer, url;
        const tick = async () => {
            if (!document.hidden) {
                try {
                    const response = await api.get('/admin/door/live', { responseType: 'blob' });
                    if (!active) return;
                    if (response.status === 200) {
                        const next = URL.createObjectURL(response.data);
                        setSrc(next); setWaiting(false);
                        if (url) URL.revokeObjectURL(url);
                        url = next;
                    } else setWaiting(true);
                } catch { if (active) setWaiting(true); }
            }
            if (active) timer = setTimeout(tick, FRAME_POLL_MS);
        };
        tick();
        return () => { active = false; clearTimeout(timer); if (url) URL.revokeObjectURL(url); };
    }, [on]);

    return (
        <div className="mb-5">
            <div className="flex items-center justify-between mb-2">
                <h4 className="text-sm font-medium text-gray-900">Camera cửa trực tiếp</h4>
                <button onClick={() => setOn(v => !v)} className="text-xs border border-gray-200 rounded-lg px-3 py-1">
                    {on ? 'Tắt xem trực tiếp' : 'Bật xem trực tiếp'}
                </button>
            </div>
            {on && (
                <div className="relative w-full sm:w-96 aspect-[4/3] rounded-lg border border-gray-200 bg-gray-900 overflow-hidden">
                    {src && <img src={src} alt="Camera ESP32 trực tiếp" className="w-full h-full object-contain" />}
                    {waiting && (
                        <p className="absolute inset-0 flex items-center justify-center text-xs text-gray-300 text-center p-4">
                            Đang chờ khung hình từ thiết bị… (thiết bị phải online)
                        </p>
                    )}
                </div>
            )}
            <p className="text-xs text-gray-400 mt-1">Chỉ để xem; mở cửa bằng khuôn mặt vẫn do PIR kích hoạt chụp và AI trên cloud quyết định.</p>
        </div>
    );
}
