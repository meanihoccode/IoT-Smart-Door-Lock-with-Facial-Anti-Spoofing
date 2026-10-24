import React, { useEffect, useState } from 'react';
import api, { errorMessage } from './api';
import { DoorOpen, ShieldCheck, KeyRound, Camera, RefreshCw, CheckCircle2, XCircle } from 'lucide-react';

export default function SecurityAudit() {
  const [activeTab, setActiveTab] = useState('access'); // 'access' hoặc 'audit'

  // Access Logs State (Lịch sử mở cửa của User)
  const [accessPage, setAccessPage] = useState(0);
  const [accessData, setAccessData] = useState(null);
  const [accessBusy, setAccessBusy] = useState(true);

  // System Audit State (Nhật ký Admin)
  const [filters, setFilters] = useState({ actor: '', action: '', from: '', to: '' });
  const [auditQuery, setAuditQuery] = useState({ page: 0, size: 20 });
  const [auditResult, setAuditResult] = useState(null);
  const [auditBusy, setAuditBusy] = useState(true);

  const [error, setError] = useState('');

  // Tải dữ liệu Access Logs
  const fetchAccessLogs = (page = 0) => {
    setAccessBusy(true);
    setError('');
    api.get('/admin/access-logs', { params: { page, size: 15 } })
      .then(({ data }) => {
        setAccessData(data);
        setAccessPage(page);
      })
      .catch(err => setError(errorMessage(err)))
      .finally(() => setAccessBusy(false));
  };

  // Tải dữ liệu Audit Logs
  const fetchAuditLogs = (query) => {
    setAuditBusy(true);
    setError('');
    api.get('/admin/security-audits', { params: query })
      .then(({ data }) => setAuditResult(data))
      .catch(err => setError(errorMessage(err)))
      .finally(() => setAuditBusy(false));
  };

  useEffect(() => {
    if (activeTab === 'access') {
      fetchAccessLogs(accessPage);
    } else {
      fetchAuditLogs(auditQuery);
    }
  }, [activeTab]);

  const handleAuditFilterSubmit = (e) => {
    e.preventDefault();
    if (filters.from && filters.to && filters.from > filters.to) {
      setError('Ngày bắt đầu phải trước hoặc bằng ngày kết thúc.');
      return;
    }
    const next = { page: 0, size: 20, ...Object.fromEntries(Object.entries(filters).filter(([, v]) => v !== '')) };
    setAuditQuery(next);
    fetchAuditLogs(next);
  };

  const formatDateTime = (str) => {
    if (!str) return '—';
    const d = new Date(str);
    return d.toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit', second: '2-digit' }) + 
           ' ' + d.toLocaleDateString('vi-VN');
  };

  return (
    <section className="max-w-6xl mx-auto space-y-6">
      {/* Header & Tabs */}
      <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4">
        <div>
          <h2 className="text-2xl font-bold tracking-tight text-gray-900">Nhật ký an ninh &amp; Lịch sử mở cửa</h2>
          <p className="text-sm text-gray-500 mt-1">
            Theo dõi chi tiết các lượt mở cửa của nhân viên và lịch sử thao tác hệ thống của Quản trị viên.
          </p>
        </div>
        <button
          onClick={() => activeTab === 'access' ? fetchAccessLogs(accessPage) : fetchAuditLogs(auditQuery)}
          className="flex items-center gap-1.5 px-3.5 py-2 text-xs font-semibold text-gray-700 bg-white border border-gray-200 rounded-lg hover:bg-gray-50 shadow-sm"
        >
          <RefreshCw size={14} className={accessBusy || auditBusy ? 'animate-spin' : ''} />
          Làm mới dữ liệu
        </button>
      </div>

      {/* Tab Switcher */}
      <div className="flex border-b border-gray-200">
        <button
          onClick={() => setActiveTab('access')}
          className={`py-3 px-6 text-sm font-semibold border-b-2 flex items-center gap-2 transition-all ${
            activeTab === 'access'
              ? 'border-gray-900 text-gray-900'
              : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'
          }`}
        >
          <DoorOpen size={18} />
          Lịch sử mở cửa của User
        </button>
        <button
          onClick={() => setActiveTab('audit')}
          className={`py-3 px-6 text-sm font-semibold border-b-2 flex items-center gap-2 transition-all ${
            activeTab === 'audit'
              ? 'border-gray-900 text-gray-900'
              : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'
          }`}
        >
          <ShieldCheck size={18} />
          Nhật ký hệ thống Admin
        </button>
      </div>

      {error && (
        <div className="p-3.5 bg-red-50 border border-red-200 text-red-700 text-sm rounded-xl">
          {error}
        </div>
      )}

      {/* TAB 1: ACCESS LOGS (LỊCH SỬ MỞ CỬA CỦA USER) */}
      {activeTab === 'access' && (
        <div className="space-y-4">
          {accessBusy && !accessData ? (
            <div className="p-8 text-center text-gray-500 bg-white rounded-2xl border">Đang tải lịch sử mở cửa…</div>
          ) : !accessData?.items?.length ? (
            <div className="p-12 text-center bg-white rounded-2xl border border-gray-200">
              <DoorOpen size={36} className="mx-auto text-gray-300 mb-2" />
              <p className="text-gray-600 font-medium">Chưa có lượt mở cửa nào được ghi nhận.</p>
              <p className="text-xs text-gray-400 mt-1">Các lượt quét mặt hoặc bấm mã PIN sẽ hiển thị tại đây.</p>
            </div>
          ) : (
            <>
              <div className="overflow-x-auto bg-white border border-gray-200 rounded-2xl shadow-sm">
                <table className="w-full text-left text-sm">
                  <thead className="bg-gray-50 border-b border-gray-100 text-xs font-semibold text-gray-600 uppercase tracking-wider">
                    <tr>
                      <th className="p-4">Thời gian</th>
                      <th className="p-4">Người mở cửa</th>
                      <th className="p-4">Phương thức</th>
                      <th className="p-4">Kết quả xác thực</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-100">
                    {accessData.items.map((item) => (
                      <tr key={item.id} className="hover:bg-gray-50/80 transition-colors">
                        <td className="p-4 whitespace-nowrap text-gray-600 font-mono text-xs">
                          {formatDateTime(item.time)}
                        </td>
                        <td className="p-4">
                          <div className="font-semibold text-gray-900">{item.userName}</div>
                          {item.username && item.username !== '—' && (
                            <div className="text-xs text-gray-400">@{item.username}</div>
                          )}
                        </td>
                        <td className="p-4">
                          <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-xs font-medium bg-gray-100 text-gray-800">
                            {item.method === 'FACE' ? (
                              <>
                                <Camera size={13} className="text-indigo-600" />
                                <span>Khuôn mặt AI</span>
                              </>
                            ) : item.method === 'PIN' ? (
                              <>
                                <KeyRound size={13} className="text-amber-600" />
                                <span>Mã PIN</span>
                              </>
                            ) : item.method?.startsWith('OFFLINE_PIN') ? (
                              <>
                                <KeyRound size={13} className="text-orange-600" />
                                <span>Mã PIN ngoại tuyến</span>
                              </>
                            ) : item.method?.startsWith('OFFLINE') || item.method === 'HARDWARE' ? (
                              <>
                                <KeyRound size={13} className="text-gray-600" />
                                <span>Phần cứng ngoại tuyến</span>
                              </>
                            ) : (
                              <span>{item.method}</span>
                            )}
                          </span>
                        </td>
                        <td className="p-4">
                          {item.status === 'SUCCESS' ? (
                            <span className="inline-flex items-center gap-1 px-2.5 py-1 rounded-full text-xs font-semibold bg-green-50 text-green-700 border border-green-200">
                              <CheckCircle2 size={13} /> Thành công (Đã mở cửa)
                            </span>
                          ) : item.status === 'COMMAND_FAILED' ? (
                            <span className="inline-flex items-center gap-1 px-2.5 py-1 rounded-full text-xs font-semibold bg-amber-50 text-amber-700 border border-amber-200">
                              Chưa gửi được lệnh thiết bị
                            </span>
                          ) : (
                            <span className="inline-flex items-center gap-1 px-2.5 py-1 rounded-full text-xs font-semibold bg-red-50 text-red-700 border border-red-200">
                              <XCircle size={13} /> {item.status || 'Thất bại'}
                            </span>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              {/* Pagination */}
              <div className="flex items-center justify-between px-2 pt-2 text-xs text-gray-500">
                <span>
                  Trang {accessData.page + 1} / {Math.max(1, accessData.totalPages)} ({accessData.totalElements} bản ghi)
                </span>
                <div className="flex gap-2">
                  <button
                    disabled={accessData.page === 0 || accessBusy}
                    onClick={() => fetchAccessLogs(accessData.page - 1)}
                    className="px-3 py-1.5 border border-gray-200 rounded-lg hover:bg-gray-100 disabled:opacity-40"
                  >
                    Trang trước
                  </button>
                  <button
                    disabled={accessData.page + 1 >= accessData.totalPages || accessBusy}
                    onClick={() => fetchAccessLogs(accessData.page + 1)}
                    className="px-3 py-1.5 border border-gray-200 rounded-lg hover:bg-gray-100 disabled:opacity-40"
                  >
                    Trang sau
                  </button>
                </div>
              </div>
            </>
          )}
        </div>
      )}

      {/* TAB 2: SYSTEM AUDIT LOGS (NHẬT KÝ ADMIN) */}
      {activeTab === 'audit' && (
        <div className="space-y-4">
          <form onSubmit={handleAuditFilterSubmit} className="flex flex-wrap gap-3 items-end bg-white p-4 rounded-xl border border-gray-200 shadow-sm">
            {[
              ['actor', 'Tài khoản Admin', 'text'],
              ['action', 'Hành động (ví dụ: LOGIN, LOCKDOWN)', 'text'],
              ['from', 'Từ ngày', 'date'],
              ['to', 'Đến ngày', 'date']
            ].map(([key, label, type]) => (
              <label key={key} className="text-xs font-medium text-gray-700 flex-1 min-w-[160px]">
                {label}
                <input
                  type={type}
                  maxLength={64}
                  value={filters[key]}
                  onChange={e => setFilters({ ...filters, [key]: e.target.value })}
                  className="mt-1 block w-full border border-gray-200 rounded-lg p-2 text-xs"
                />
              </label>
            ))}
            <button
              disabled={auditBusy}
              className="bg-gray-900 text-white rounded-lg px-4 py-2 text-xs font-semibold hover:bg-black transition-colors"
            >
              Lọc nhật ký
            </button>
          </form>

          {auditBusy && !auditResult ? (
            <div className="p-8 text-center text-gray-500 bg-white rounded-2xl border">Đang tải nhật ký hệ thống…</div>
          ) : !auditResult?.items?.length ? (
            <div className="p-8 text-center bg-white rounded-2xl border text-gray-500">Không có bản ghi phù hợp.</div>
          ) : (
            <>
              <div className="overflow-x-auto bg-white border border-gray-200 rounded-2xl shadow-sm">
                <table className="w-full text-left text-sm">
                  <thead className="bg-gray-50 border-b border-gray-100 text-xs font-semibold text-gray-600 uppercase tracking-wider">
                    <tr>
                      <th className="p-4">Thời gian</th>
                      <th className="p-4">Tài khoản thực hiện</th>
                      <th className="p-4">Mã hành động</th>
                      <th className="p-4">Chi tiết đối tượng</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-100">
                    {auditResult.items.map(item => (
                      <tr key={item.id} className="hover:bg-gray-50/80 transition-colors">
                        <td className="p-4 whitespace-nowrap text-gray-600 font-mono text-xs">
                          {formatDateTime(item.time)}
                        </td>
                        <td className="p-4 font-semibold text-gray-900">{item.actor}</td>
                        <td className="p-4">
                          <span className={`inline-block px-2 py-0.5 rounded text-xs font-mono font-semibold ${
                            item.action?.includes('LOCKDOWN') ? 'bg-red-100 text-red-800' :
                            item.action?.includes('LOGIN') ? 'bg-blue-100 text-blue-800' :
                            'bg-gray-100 text-gray-800'
                          }`}>
                            {item.action}
                          </span>
                        </td>
                        <td className="p-4 text-xs text-gray-500">{item.target ?? '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              {/* Pagination */}
              <div className="flex items-center justify-between px-2 pt-2 text-xs text-gray-500">
                <span>
                  Trang {auditResult.page + 1} / {Math.max(1, auditResult.totalPages)} ({auditResult.totalElements} bản ghi)
                </span>
                <div className="flex gap-2">
                  <button
                    disabled={auditQuery.page === 0 || auditBusy}
                    onClick={() => {
                      const next = { ...auditQuery, page: auditQuery.page - 1 };
                      setAuditQuery(next);
                      fetchAuditLogs(next);
                    }}
                    className="px-3 py-1.5 border border-gray-200 rounded-lg hover:bg-gray-100 disabled:opacity-40"
                  >
                    Trang trước
                  </button>
                  <button
                    disabled={auditQuery.page + 1 >= auditResult.totalPages || auditBusy}
                    onClick={() => {
                      const next = { ...auditQuery, page: auditQuery.page + 1 };
                      setAuditQuery(next);
                      fetchAuditLogs(next);
                    }}
                    className="px-3 py-1.5 border border-gray-200 rounded-lg hover:bg-gray-100 disabled:opacity-40"
                  >
                    Trang sau
                  </button>
                </div>
              </div>
            </>
          )}
        </div>
      )}
    </section>
  );
}
