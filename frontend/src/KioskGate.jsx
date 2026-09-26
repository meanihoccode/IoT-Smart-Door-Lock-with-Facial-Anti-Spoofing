import { useEffect, useState } from 'react';
import api, { errorMessage } from './api';
import { useAuth } from './auth-context';
import { handoffToKiosk } from './kiosk-handoff';
import Kiosk from './Kiosk';
import { listenSession } from './session-events';

export default function KioskGate() {
  const { clearSession } = useAuth();
  const [state, setState] = useState('waiting');
  const [error, setError] = useState('');
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    const check = () => { setState('waiting'); setAttempt(n => n + 1); };
    const stop = listenSession(type => { if (type === 'admin-login') check(); });
    window.addEventListener('focus', check);
    return () => { stop(); window.removeEventListener('focus', check); };
  }, []);
  useEffect(() => {
    let active = true;
    handoffToKiosk(api).then(() => {
      if (active) { clearSession(); setState('ready'); }
    }).catch(err => { if (active) { setError(errorMessage(err)); setState('error'); } });
    return () => { active = false; };
  }, [attempt, clearSession]);
  if (state === 'ready') return <Kiosk />;
  return <main className="p-8 max-w-xl mx-auto" aria-live="polite">
    <h1 className="text-xl font-semibold">Bàn giao máy cho Kiosk</h1>
    <p>Chỉ sử dụng Kiosk sau khi máy chủ xác nhận phiên quản trị đã kết thúc.</p>
    {state === 'waiting' ? <p>Đang kết thúc phiên…</p> : <>
      <p role="alert" className="text-red-700 my-4">{error} Chưa thể bàn giao máy.</p>
      <button onClick={() => { setState('waiting'); setAttempt(n => n + 1); }} className="border rounded px-4 py-2">Thử lại đăng xuất</button>
    </>}
  </main>;
}
