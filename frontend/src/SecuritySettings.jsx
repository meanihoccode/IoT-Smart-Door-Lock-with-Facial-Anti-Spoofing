import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from './auth-context';
import api, { errorMessage } from './api';

export default function SecuritySettings() {
  const { clearSession } = useAuth();
  const navigate = useNavigate();
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  async function submit(event) {
    event.preventDefault(); setError('');
    if (next !== confirm) { setError('Xác nhận mật khẩu không khớp.'); return; }
    if (new TextEncoder().encode(next).length > 72) { setError('Mật khẩu tối đa 72 byte UTF-8.'); return; }
    setBusy(true);
    try {
      await api.post('/auth/password', { currentPassword: current, newPassword: next });
      clearSession();
      navigate('/login', { replace: true, state: { message: 'Đã đổi mật khẩu. Hãy đăng nhập lại bằng mật khẩu mới.' } });
    } catch (err) { setError(errorMessage(err)); }
    finally { setBusy(false); setCurrent(''); setNext(''); setConfirm(''); }
  }
  return <form onSubmit={submit} className="max-w-lg bg-white border rounded-2xl p-6 space-y-5">
    <h2 className="text-xl font-semibold">Đổi mật khẩu quản trị</h2>
    <p className="text-sm text-gray-500">Từ 12 ký tự, tối đa 72 byte UTF-8. Sau khi đổi, tất cả phiên đăng nhập cũ sẽ mất hiệu lực.</p>
    {error && <p role="alert" className="text-red-700">{error}</p>}
    <label className="block">Mật khẩu hiện tại<input required type="password" autoComplete="current-password" maxLength={256} value={current} onChange={(e) => setCurrent(e.target.value)} className="mt-1 w-full border rounded-lg p-3" /></label>
    <label className="block">Mật khẩu mới<input required type="password" autoComplete="new-password" minLength={12} value={next} onChange={(e) => setNext(e.target.value)} className="mt-1 w-full border rounded-lg p-3" /></label>
    <label className="block">Nhập lại mật khẩu mới<input required type="password" autoComplete="new-password" minLength={12} value={confirm} onChange={(e) => setConfirm(e.target.value)} className="mt-1 w-full border rounded-lg p-3" /></label>
    <button disabled={busy} className="bg-gray-900 text-white rounded-lg px-5 py-3 disabled:opacity-50">{busy ? 'Đang cập nhật…' : 'Đổi mật khẩu và đăng xuất'}</button>
  </form>;
}
