import React, { useState } from 'react';
import { useLiveFrame } from './use-live-frame';

// Admin view with an on/off toggle.
export default function LiveCamera({ endpoint = '/admin/door/live', title = 'Camera cửa trực tiếp' }) {
    const [on, setOn] = useState(true);
    const { src, waiting } = useLiveFrame(endpoint, on);

    return (
        <div className="mb-5">
            <div className="flex items-center justify-between mb-2">
                <h4 className="text-sm font-medium text-gray-900">{title}</h4>
                <button onClick={() => setOn(v => !v)} className="text-xs border border-gray-200 rounded-lg px-3 py-1">
                    {on ? 'Tắt xem trực tiếp' : 'Bật xem trực tiếp'}
                </button>
            </div>
            {on && (
                <div className="relative w-full aspect-[4/3] rounded-lg border border-gray-200 bg-gray-900 overflow-hidden">
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
