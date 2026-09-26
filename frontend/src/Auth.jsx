import { useCallback, useEffect, useRef, useState } from 'react';
import { announceSession, listenSession } from './session-events';
import { Navigate, Outlet } from 'react-router-dom';
import api, { errorMessage } from './api';
import { AuthContext, useAuth } from './auth-context';

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const generation = useRef(0);
  useEffect(() => listenSession(type => {
    if (type === 'session-ended') { generation.current++; setUser(null); }
  }), []);
  useEffect(() => {
    let active = true;
    const revision = generation.current;
    const expire = () => { generation.current++; setUser(null); setError(''); };
    window.addEventListener('smartlock:session-expired', expire);
    api.get('/auth/me').then(({ data }) => { if (active && revision === generation.current) setUser(data); })
      .catch((err) => { if (active && err.response?.status !== 401) setError(errorMessage(err)); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; window.removeEventListener('smartlock:session-expired', expire); };
  }, []);
  const login = async (username, password) => {
    const { data } = await api.post('/auth/login', { username, password });
    setError(''); setUser(data);
    announceSession('admin-login');
  };
  const clearSession = useCallback(() => { generation.current++; setUser(null); announceSession('session-ended'); }, []);
  const logout = async () => { await api.post('/auth/logout'); clearSession(); };
  return <AuthContext.Provider value={{ user, loading, error, login, logout, clearSession }}>
    {children}
  </AuthContext.Provider>;
}

export function ProtectedAdmin() {
  const { user, loading, error } = useAuth();
  useEffect(() => {
    if (!user) return;
    const check = () => { if (!document.hidden) api.get('/auth/me').catch(() => {}); };
    const timer = setInterval(check, 30000);
    window.addEventListener('focus', check);
    return () => { clearInterval(timer); window.removeEventListener('focus', check); };
  }, [user]);
  if (loading) return <p className="p-8" role="status">Đang kiểm tra phiên đăng nhập…</p>;
  if (error) return <div className="p-8" role="alert">{error} <button onClick={() => window.location.reload()} className="underline">Thử lại</button></div>;
  return user ? <Outlet /> : <Navigate to="/login" replace />;
}
