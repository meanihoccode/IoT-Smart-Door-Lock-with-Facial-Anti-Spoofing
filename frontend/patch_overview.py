import re

with open('e:/BTL_IOT/frontend/src/Overview.jsx', 'r', encoding='utf-8') as f:
    code = f.read()

code = code.replace("import React from 'react';", "import React, { useState, useEffect } from 'react';\nimport axios from 'axios';")

new_comp = """const Overview = () => {
    const [data, setData] = useState({
        totalEmployees: 0,
        accessesToday: 0,
        recentLogs: []
    });

    useEffect(() => {
        const fetchData = async () => {
            try {
                const res = await axios.get('http://localhost:8080/api/overview');
                setData(res.data);
            } catch (error) {
                console.error("Lỗi khi tải dữ liệu overview:", error);
            }
        };
        fetchData();
        const interval = setInterval(fetchData, 5000); // Tự động cập nhật sau mỗi 5s
        return () => clearInterval(interval);
    }, []);

    const formatTime = (timeStr) => {
        if (!timeStr) return '';
        const date = new Date(timeStr);
        return date.toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' }) + ' ' + date.toLocaleDateString('vi-VN');
    };

    return (
        <div className="max-w-5xl mx-auto space-y-6 animate-in fade-in slide-in-from-bottom-4 duration-300">
            <div className="mb-8">
                <h2 className="text-2xl font-bold tracking-tight text-gray-900">Dashboard</h2>
                <p className="text-sm text-gray-500 mt-1">Tổng quan hoạt động của hệ thống kiểm soát cửa.</p>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
                <StatCard title="Tổng số nhân viên" value={data.totalEmployees} icon={Users} />
                <StatCard title="Lượt mở cửa (Hôm nay)" value={data.accessesToday} icon={DoorOpen} />
                <StatCard title="Tỷ lệ nhận diện đúng" value="--" icon={ShieldCheck} />
                <StatCard title="Cảnh báo giả mạo" value="--" icon={Activity} />
            </div>

            <div className="grid grid-cols-1 lg:grid-cols-3 gap-6 mt-6">
                <div className="lg:col-span-2 bg-white p-6 rounded-2xl shadow-sm border border-gray-200">
                    <h3 className="text-lg font-semibold text-gray-900 mb-4">Lịch sử ra vào gần nhất</h3>
                    <div className="space-y-4">
                        {data.recentLogs.map((log, i) => (
                            <div key={log.id || i} className="flex items-center justify-between py-3 border-b border-gray-50 last:border-0">
                                <div className="flex items-center gap-3">
                                    <div className="w-10 h-10 rounded-full bg-gray-100 flex items-center justify-center font-medium text-gray-600">
                                        {log.userName ? log.userName.charAt(0).toUpperCase() : 'U'}
                                    </div>
                                    <div>
                                        <p className="text-sm font-medium text-gray-900">{log.userName}</p>
                                        <p className="text-xs text-gray-500">Mở khóa bằng {log.method === 'FACE' ? 'Khuôn mặt' : 'Mã PIN'}</p>
                                    </div>
                                </div>
                                <div className="text-right">
                                    <span className={`text-xs font-medium px-2 py-1 rounded-full ${log.status === 'SUCCESS' ? 'bg-green-50 text-green-700' : 'bg-red-50 text-red-700'}`}>
                                        {log.status === 'SUCCESS' ? 'Thành công' : 'Thất bại'}
                                    </span>
                                    <p className="text-xs text-gray-500 mt-1">{formatTime(log.time)}</p>
                                </div>
                            </div>
                        ))}
                    </div>
                </div>

                <div className="bg-white p-6 rounded-2xl shadow-sm border border-gray-200 flex flex-col items-center justify-center text-center">
                    <div className="w-20 h-20 bg-green-50 rounded-full flex items-center justify-center mb-4 text-green-500">
                        <ShieldCheck size={32} />
                    </div>
                    <h3 className="text-lg font-semibold text-gray-900">Hệ thống an toàn</h3>
                    <p className="text-sm text-gray-500 mt-2">Server đang kết nối bình thường với thiết bị ESP32 thông qua MQTT.</p>
                </div>
            </div>
        </div>
    );
};"""

code = re.sub(r'const Overview = \(\) => \{.*?\n\};', new_comp, code, flags=re.DOTALL)

with open('e:/BTL_IOT/frontend/src/Overview.jsx', 'w', encoding='utf-8') as f:
    f.write(code)
