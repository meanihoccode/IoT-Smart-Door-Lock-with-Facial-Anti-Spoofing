import React, { useState, useEffect } from 'react';
import axios from './api';
import { Users, DoorOpen, ShieldCheck, Activity } from 'lucide-react';

const StatCard = ({ title, value, icon: Icon, trend }) => (
    <div className="bg-white p-6 rounded-2xl shadow-sm border border-gray-200">
        <div className="flex items-center justify-between mb-4">
            <h3 className="text-sm font-medium text-gray-500">{title}</h3>
            <div className="p-2 bg-gray-50 rounded-lg text-gray-600">
                <Icon size={20} />
            </div>
        </div>
        <div className="flex items-baseline gap-2">
            <span className="text-3xl font-bold text-gray-900">{value}</span>
            {trend && (
                <span className={`text-sm font-medium ${trend.startsWith('+') ? 'text-green-600' : 'text-red-600'}`}>
                    {trend}
                </span>
            )}
        </div>
    </div>
);

const Overview = () => {
    const [data, setData] = useState({
        totalEmployees: 0,
        accessesToday: 0,
        recentLogs: []
    });

    useEffect(() => {
        const fetchData = async () => {
            try {
                const res = await axios.get('/overview');
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
                <StatCard title="Tổng hồ sơ ra vào" value={data.totalEmployees} icon={Users} />
                <StatCard title="Lượt xác thực (Hôm nay)" value={data.accessesToday} icon={DoorOpen} />
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
                                        <p className="text-xs text-gray-500">Xác thực bằng {log.method === 'FACE' ? 'Khuôn mặt' : 'Mã hồ sơ + PIN'}</p>
                                    </div>
                                </div>
                                <div className="text-right">
                                    <span className={`text-xs font-medium px-2 py-1 rounded-full ${log.status === 'SUCCESS' ? 'bg-green-50 text-green-700' : 'bg-red-50 text-red-700'}`}>
                                        {log.status === 'SUCCESS' ? 'Đã gửi lệnh' : log.status === 'COMMAND_FAILED' ? 'Lỗi gửi lệnh' : 'Từ chối / lỗi'}
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
                    <h3 className="text-lg font-semibold text-gray-900">Kiểm soát quyền ra vào</h3>
                    <p className="text-sm text-gray-500 mt-2">Hồ sơ bị thu hồi quyền không được mở cửa bằng PIN hoặc khuôn mặt. Trạng thái gửi lệnh chưa xác nhận chốt cửa đã mở thực tế.</p>
                </div>
            </div>
        </div>
    );
};

export default Overview;
