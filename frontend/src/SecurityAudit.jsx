import { useEffect, useState } from 'react';
import api, { errorMessage } from './api';

export default function SecurityAudit() {
  const [filters, setFilters] = useState({ actor: '', action: '', from: '', to: '' });
  const [query, setQuery] = useState({ page: 0, size: 20 });
  const [result, setResult] = useState(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(true);
  useEffect(() => {
    let active = true;
    api.get('/admin/security-audits', { params: query }).then(({ data }) => { if (active) setResult(data); })
      .catch(err => { if (active) setError(errorMessage(err)); })
      .finally(() => { if (active) setBusy(false); });
    return () => { active = false; };
  }, [query]);
  function load(nextQuery) { setBusy(true); setError(''); setResult(null); setQuery(nextQuery); }
  function submit(e) {
    e.preventDefault();
    if (filters.from && filters.to && filters.from > filters.to) { setError('Ngày bắt đầu phải trước hoặc bằng ngày kết thúc.'); return; }
    load({ page: 0, size: 20, ...Object.fromEntries(Object.entries(filters).filter(([, v]) => v !== '')) });
  }
  return <section className="space-y-5">
    <h2 className="text-xl font-semibold">Nhật ký bảo mật</h2>
    <p className="text-sm text-gray-600">Chỉ đọc. Thời gian theo đồng hồ máy chủ. Nhật ký bảo mật khác lịch sử xác thực mở cửa.</p>
    <form onSubmit={submit} className="flex flex-wrap gap-3 items-end">
      {[['actor','Tài khoản (khớp chính xác)','text'],['action','Mã hành động, ví dụ LOGIN_FAILED','text'],['from','Từ ngày','date'],['to','Đến ngày','date']].map(([key,label,type]) =>
        <label key={key} className="text-sm">{label}<input type={type} maxLength={64} value={filters[key]}
          onChange={e => setFilters({ ...filters, [key]: e.target.value })} className="block border rounded p-2" /></label>)}
      <button disabled={busy} className="border rounded px-4 py-2">Lọc / tải lại</button>
    </form>
    {error && <p role="alert" className="text-red-700">{error}</p>}
    {busy && <p role="status">Đang tải nhật ký…</p>}
    {!busy && result && <>
      <div className="overflow-x-auto bg-white border rounded">
        <table className="w-full text-left text-sm"><thead><tr>{['Thời gian (server)','Tài khoản','Hành động','Đối tượng'].map(h => <th key={h} className="p-3">{h}</th>)}</tr></thead>
          <tbody>{result.items.map(item => <tr key={item.id} className="border-t">
            <td className="p-3 whitespace-nowrap">{item.time?.replace('T',' ')}</td>
            <td className="p-3 break-all">{item.actor}</td><td className="p-3">{item.action}</td><td className="p-3">{item.target ?? '—'}</td>
          </tr>)}</tbody></table>
        {!result.items.length && <p className="p-4">Không có nhật ký phù hợp.</p>}
      </div>
      <div className="flex items-center gap-4">
        <button disabled={query.page === 0} onClick={() => load({ ...query, page: query.page - 1 })} className="border rounded p-2 disabled:opacity-40">Trước</button>
        <span>Trang {result.page + 1}/{Math.max(1,result.totalPages)} · {result.totalElements} bản ghi</span>
        <button disabled={query.page + 1 >= result.totalPages} onClick={() => load({ ...query, page: query.page + 1 })} className="border rounded p-2 disabled:opacity-40">Sau</button>
      </div>
    </>}
  </section>;
}
