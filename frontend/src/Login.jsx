import { useState } from 'react';
import { Link, Navigate, useLocation } from 'react-router-dom';
import { useAuth } from './auth-context';
import { errorMessage } from './api';

export default function Login() {
  const { user, login, loading } = useAuth();
  const location = useLocation();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  if (loading) return <p className="p-8">Đang kiểm tra phiên đăng nhập…</p>;
  if (user) return <Navigate to="/admin" replace />;
  async function submit(event) {
    event.preventDefault(); setBusy(true); setError('');
    try { await login(username, password); }
    catch (err) { setError(errorMessage(err)); }
    finally { setBusy(false); setPassword(''); }
  }
  return <main className="min-h-screen bg-gray-50 flex items-center justify-center p-6">
    <form onSubmit={submit} className="w-full max-w-md bg-white border border-gray-200 rounded-2xl p-8 space-y-5 shadow-sm">
      <h1 className="text-2xl font-bold">Đăng nhập quản trị</h1>
      <p className="text-sm text-gray-500">Tài khoản quản trị độc lập với hồ sơ và PIN mở cửa.</p>
      {location.state?.message && <p role="status" className="text-green-700">{location.state.message}</p>}
      {error && <p role="alert" className="text-red-700">{error}</p>}
      <label className="block">Tên đăng nhập
        <input required autoComplete="username" maxLength={64} pattern="[A-Za-z0-9_-]+" value={username} onChange={(e) => setUsername(e.target.value)} className="mt-1 w-full border rounded-lg p-3" />
      </label>
      <label className="block">Mật khẩu
        <input required type="password" autoComplete="current-password" maxLength={256} value={password} onChange={(e) => setPassword(e.target.value)} className="mt-1 w-full border rounded-lg p-3" />
      </label>
      <button disabled={busy} className="w-full bg-gray-900 text-white rounded-lg p-3 disabled:opacity-50">{busy ? 'Đang đăng nhập…' : 'Đăng nhập'}</button>
      <Link to="/kiosk" className="block text-sm underline">Về màn hình cửa</Link>
    </form>
  </main>;
}
