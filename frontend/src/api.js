import axios from 'axios';

const api = axios.create({ baseURL: '/api', withCredentials: true });
// Read a fresh CSRF token before each write, including after session rotation.
// Never replay a failed write automatically (registration/unlock may have side effects).
api.interceptors.request.use(async (config) => {
  if (!['get', 'head', 'options'].includes(config.method?.toLowerCase())) {
    const { data } = await api.get('/auth/csrf');
    config.headers.set(data.headerName, data.token);
  }
  return config;
});
api.interceptors.response.use(undefined, (error) => {
  const url = error.config?.url || '';
  if (error.response?.status === 401 &&
      !['/auth/login', '/verify-pin', '/verify-face'].includes(url)) {
    window.dispatchEvent(new Event('smartlock:session-expired'));
  }
  return Promise.reject(error);
});

export function errorMessage(error) {
  if (error.response?.status === 429) return 'Thử quá nhiều lần. Vui lòng chờ 5 phút rồi thử lại.';
  if (error.response?.status === 403) return 'Phiên bảo mật không hợp lệ. Hãy tải lại trang rồi thử lại.';
  return error.response?.data?.message || 'Không kết nối được máy chủ. Vui lòng thử lại.';
}
export default api;
