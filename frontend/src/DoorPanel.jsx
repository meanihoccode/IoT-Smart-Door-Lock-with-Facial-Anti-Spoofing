import React, { useCallback, useEffect, useState } from 'react';
import { DoorOpen, Loader2 } from 'lucide-react';
import api, { errorMessage } from './api';
import LiveCamera from './LiveCamera';
import { STATUS_POLL_MS, TONE_CLASSES, commandLabel, commandTypeLabel, connectionLabel, lockdownLabel, methodLabel } from './door-status';

// Admin view of the door node: connection, lockdown applied/pending, command lifecycle and remote open.
export default function DoorPanel() {
    const [door, setDoor] = useState(null);
    const [busy, setBusy] = useState(false);
    const [notice, setNotice] = useState('');
    const [error, setError] = useState('');

    const [scan, setScan] = useState(null);

    const load = useCallback(async () => {
        try { const { data } = await api.get('/admin/door'); setDoor(data); }
        catch (err) { setError(errorMessage(err)); }
        try { const { data, status } = await api.get('/admin/door/last-capture'); setScan(status === 204 ? null : data); }
        catch { /* preview only */ }
    }, []);

    useEffect(() => {
        load();
        const timer = setInterval(load, STATUS_POLL_MS);
        return () => clearInterval(timer);
    }, [load]);

    // One click = one command. Never retried automatically: a lost response may still have opened the door.
    async function remoteOpen() {
        if (!window.confirm('Gửi lệnh mở cửa từ xa? Thao tác được ghi nhật ký kiểm toán.')) return;
        setBusy(true); setError(''); setNotice('');
        try {
            const { data } = await api.post('/admin/door/open');
            setNotice(data.message);
        } catch (err) {
            setError(errorMessage(err));
        } finally {
            setBusy(false); load();
        }
    }

    const connection = connectionLabel(door?.connection);
    return (
        <section className="bg-white p-6 rounded-2xl shadow-sm border border-gray-200">
            <div className="flex flex-wrap items-start justify-between gap-4 mb-4">
                <div>
                    <h3 className="text-lg font-semibold text-gray-900">Thiết bị cửa {door?.deviceId ? `(${door.deviceId})` : ''}</h3>
                    <p className="text-sm text-gray-500 mt-1">
                        <span className={`text-xs font-medium px-2 py-1 rounded-full mr-2 ${TONE_CLASSES[connection.tone]}`}>{connection.label}</span>
                        {door && lockdownLabel(door.lockdown, door.lockdownApplied)}
                    </p>
                </div>
                <button onClick={remoteOpen} disabled={busy || !door || door.lockdown}
                        className="flex items-center gap-2 bg-gray-900 text-white rounded-lg px-4 py-2 text-sm disabled:opacity-50">
                    {busy ? <Loader2 className="animate-spin" size={16} /> : <DoorOpen size={16} />}
                    Mở cửa từ xa
                </button>
            </div>
            {notice && <p className="text-sm text-gray-700 mb-3" aria-live="polite">{notice}</p>}
            {error && <p role="alert" className="text-sm text-red-700 mb-3">{error}</p>}
            <div className="sm:w-96"><LiveCamera /></div>
            <h4 className="text-sm font-medium text-gray-900 mb-2">Lần quét khuôn mặt gần nhất (camera cửa)</h4>
            {scan ? (
                <div className="flex flex-col sm:flex-row gap-4 mb-5">
                    <img src={scan.imageUrl} alt="Ảnh camera ESP32 chụp gần nhất"
                         className="w-full sm:w-64 rounded-lg border border-gray-200 bg-gray-100 object-contain" />
                    <div className="text-sm">
                        <span className={`text-xs font-medium px-2 py-1 rounded-full ${scan.commandId ? TONE_CLASSES.ok : TONE_CLASSES.bad}`}>
                            {scan.reasonCode}
                        </span>
                        <p className="text-gray-700 mt-2">{scan.message}</p>
                        <p className="text-xs text-gray-400 mt-1">{new Date(scan.capturedAt).toLocaleString('vi-VN')}</p>
                    </div>
                </div>
            ) : <p className="text-sm text-gray-500 mb-5">Chưa có lần quét nào kể từ khi máy chủ khởi động.</p>}
            <h4 className="text-sm font-medium text-gray-900 mb-2">Lệnh gần đây</h4>
            <ul className="divide-y divide-gray-50">
                {(door?.commands || []).map(command => {
                    const status = commandLabel(command.status);
                    return (
                        <li key={command.commandId} className="py-2 flex items-center justify-between gap-3 text-sm">
                            <span className="text-gray-700">
                                {commandTypeLabel(command.type)} · {methodLabel(command.method)}
                                <span className="block text-xs text-gray-400">{new Date(command.issuedAt).toLocaleString('vi-VN')}{command.statusReason ? ` · ${command.statusReason}` : ''}</span>
                            </span>
                            <span className={`text-xs font-medium px-2 py-1 rounded-full text-right ${TONE_CLASSES[status.tone]}`}>{status.label}</span>
                        </li>
                    );
                })}
                {door && door.commands?.length === 0 && <li className="py-2 text-sm text-gray-500">Chưa có lệnh nào.</li>}
            </ul>
        </section>
    );
}
