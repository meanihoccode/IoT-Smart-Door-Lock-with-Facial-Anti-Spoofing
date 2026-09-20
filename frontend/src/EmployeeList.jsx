import { useEffect, useState } from 'react';
import api, { errorMessage } from './api';

export default function EmployeeList() {
  const [profiles, setProfiles] = useState([]);
  const [query, setQuery] = useState('');
  const [editing, setEditing] = useState(null);
  const [fullName, setFullName] = useState('');
  const [pin, setPin] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');

  async function refresh() {
    setEditing(null); setPin(''); setConfirmation('');
    setLoading(true); setError('');
    try { setProfiles((await api.get('/users')).data); }
    catch (err) { setError(errorMessage(err)); }
    finally { setLoading(false); }
  }
  useEffect(() => {
    let active = true;
    api.get('/users').then(({ data }) => { if (active) setProfiles(data); })
      .catch((err) => { if (active) setError(errorMessage(err)); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, []);
  function edit(profile) {
    setEditing(profile); setFullName(profile.name || '');
    setPin(''); setConfirmation(''); setError(''); setMessage('');
  }
  function accept(updated, notice) {
    setProfiles((current) => current.map((p) => p.id === updated.id ? updated : p));
    setEditing(null); setMessage(notice);
  }
  async function save(event) {
    event.preventDefault(); setBusy(true); setError('');
    try {
      const { data } = await api.patch('/users/' + editing.id, {
        fullName, enabled: editing.enabled, version: editing.version,
      });
      accept(data, 'Đã cập nhật hồ sơ.');
    } catch (err) { setError(errorMessage(err)); }
    finally { setBusy(false); }
  }
  async function toggle(profile) {
    if (!window.confirm((profile.enabled ? 'Thu hồi' : 'Cấp lại') + ' quyền mở cửa của ' + profile.name + '?')) return;
    setBusy(true); setError(''); setMessage('');
    try {
      const { data } = await api.patch('/users/' + profile.id, {
        enabled: !profile.enabled, version: profile.version,
      });
      accept(data, data.enabled ? 'Đã cấp lại quyền ra vào.' : 'Đã thu hồi quyền mở cửa bằng cả PIN và khuôn mặt.');
    } catch (err) { setError(errorMessage(err)); }
    finally { setBusy(false); }
  }
  async function resetPin(event) {
    event.preventDefault(); setError('');
    if (pin !== confirmation) { setError('Xác nhận PIN không khớp.'); return; }
    setBusy(true);
    try {
      const { data } = await api.put('/users/' + editing.id + '/pin', {
        pinCode: pin, version: editing.version,
      });
      accept(data, 'Đã đặt PIN mới. PIN cũ không còn dùng được; trạng thái quyền ra vào giữ nguyên.');
    } catch (err) { setError(errorMessage(err)); }
    finally { setBusy(false); setPin(''); setConfirmation(''); }
  }
  const filtered = profiles.filter((p) => (p.username + ' ' + p.name).toLocaleLowerCase('vi').includes(query.toLocaleLowerCase('vi')));
  const input = 'mt-1 w-full border border-gray-300 rounded-lg p-3';
  return <div className="max-w-6xl mx-auto space-y-5">
    <div className="flex flex-wrap justify-between gap-4">
      <div><h2 className="text-2xl font-bold">Hồ sơ người được phép vào</h2>
        <p className="text-sm text-gray-500 mt-1">Độc lập với tài khoản quản trị. Thu hồi quyền giữ lại hồ sơ và lịch sử ra vào.</p></div>
      <button onClick={refresh} disabled={busy || loading} className="border rounded-lg px-4 py-2 disabled:opacity-50">Tải lại danh sách</button>
    </div>
    {error && <p role="alert" className="text-red-700 bg-red-50 p-3 rounded-lg">{error}</p>}
    {message && <p role="status" className="text-green-700 bg-green-50 p-3 rounded-lg">{message}</p>}
    <label className="block max-w-md text-sm">Tìm theo mã hồ sơ hoặc họ tên
      <input value={query} onChange={(e) => setQuery(e.target.value)} className={input} />
    </label>
    {loading ? <p role="status">Đang tải hồ sơ…</p> : <div className="bg-white border rounded-xl overflow-x-auto">
      <table className="w-full text-left text-sm">
        <thead className="bg-gray-50"><tr>{['Họ tên / Mã hồ sơ', 'Phương thức', 'Quyền ra vào', 'Hoạt động cuối', 'Thao tác'].map((title) => <th key={title} className="p-4">{title}</th>)}</tr></thead>
        <tbody>{filtered.map((profile) => <tr key={profile.id} className="border-t">
          <td className="p-4"><p className="font-medium">{profile.name}</p><p className="text-gray-500">{profile.username}</p></td>
          <td className="p-4">{profile.method}{profile.pinResetRequired && <p className="text-amber-700">Cần đặt PIN mới hoặc chuyển đổi PIN cũ</p>}</td>
          <td className={'p-4 ' + (profile.enabled ? 'text-green-700' : 'text-red-700')}>{profile.enabled ? 'Được phép' : 'Đã thu hồi'}</td>
          <td className="p-4">{profile.lastActive ? new Date(profile.lastActive).toLocaleString('vi-VN') : 'Chưa hoạt động'}</td>
          <td className="p-4"><div className="flex flex-wrap gap-2">
            <button onClick={() => edit(profile)} disabled={busy} className="border rounded px-3 py-2 disabled:opacity-50">Sửa / Đặt PIN</button>
            <button onClick={() => toggle(profile)} disabled={busy} className="border rounded px-3 py-2 disabled:opacity-50">{profile.enabled ? 'Thu hồi quyền' : 'Cấp lại quyền'}</button>
          </div></td>
        </tr>)}</tbody>
      </table>
      {!filtered.length && <p className="p-4 text-gray-500">Không có hồ sơ phù hợp.</p>}
    </div>}
    {editing && <section className="bg-white border rounded-xl p-6 space-y-5" aria-label="Chỉnh sửa hồ sơ">
      <h3 className="text-lg font-semibold">Hồ sơ: {editing.username}</h3>
      <p className="text-sm text-gray-500">Mã hồ sơ không đổi. Không thể xem lại PIN đã lưu; chỉ đặt PIN mới.</p>
      <form onSubmit={save} className="max-w-md space-y-3">
        <label className="block">Họ tên<input required maxLength={100} value={fullName} onChange={(e) => setFullName(e.target.value)} className={input} /></label>
        <button disabled={busy} className="bg-gray-900 text-white rounded-lg px-4 py-2 disabled:opacity-50">Lưu họ tên</button>
      </form>
      <form onSubmit={resetPin} className="max-w-md space-y-3 border-t pt-5">
        <label className="block">PIN mới (6–10 chữ số)<input required type="password" autoComplete="new-password" inputMode="numeric" pattern="[0-9]{6,10}" minLength={6} maxLength={10} value={pin} onChange={(e) => setPin(e.target.value)} className={input} /></label>
        <label className="block">Xác nhận PIN<input required type="password" autoComplete="new-password" inputMode="numeric" pattern="[0-9]{6,10}" maxLength={10} value={confirmation} onChange={(e) => setConfirmation(e.target.value)} className={input} /></label>
        <button disabled={busy} className="bg-gray-900 text-white rounded-lg px-4 py-2 disabled:opacity-50">Đặt PIN mới</button>
      </form>
      <button disabled={busy} onClick={() => { setEditing(null); setPin(''); setConfirmation(''); }} className="underline">Đóng chỉnh sửa</button>
    </section>}
  </div>;
}
