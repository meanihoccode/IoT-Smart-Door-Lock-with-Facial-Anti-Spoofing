let pending;
// Coalesce StrictMode's repeated effect; never retain credentials in browser storage.
export function handoffToKiosk(api) {
  if (!pending) {
    pending = api.post('/auth/kiosk').then(({ data }) => {
      if (data?.status !== 'success') throw new Error('Chưa xác nhận đăng xuất.');
    }).finally(() => { pending = undefined; });
  }
  return pending;
}
