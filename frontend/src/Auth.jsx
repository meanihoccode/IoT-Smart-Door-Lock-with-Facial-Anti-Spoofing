import { useEffect, useState } from 'react';
import { Navigate, Outlet } from 'react-router-dom';
import api, { errorMessage } from './api';
import { AuthContext, useAuth } from './auth-context';

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  useEffect(() => {
    let active = true;
    const expire = () => { setUser(null); setError(''); };
    window.addEventListener('smartlock:session-expired', expire);
    api.get('/auth/me').then(({ data }) => { if (active) setUser(data); })
      .catch((err) => { if (active && err.response?.status !== 401) setError(errorMessage(err)); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; window.removeEventListener('smartlock:session-expired', expire); };
  }, []);
  const login = async (username, password) => {
    const { data } = await api.post('/auth/login', { username, password });
    setError(''); setUser(data);
  };
  const logout = async () => { await api.post('/auth/logout'); setUser(null); };
  return <AuthContext.Provider value={{ user, loading, error, login, logout, clearSession: () => setUser(null) }}>
    {children}
  </AuthContext.Provider>;
}

export function ProtectedAdmin() {
  const { user, loading, error } = useAuth();
  if (loading) return <p className="p-8" role="status">Đang kiểm tra phiên đăng nhập…</p>;
  if (error) return <div className="p-8" role="alert">{error} <button onClick={() => window.location.reload()} className="underline">Thử lại</button></div>;
  return user ? <Outlet /> : <Navigate to="/login" replace />;
}
