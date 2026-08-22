import re

with open('e:/BTL_IOT/frontend/src/EmployeeList.jsx', 'r', encoding='utf-8') as f:
    code = f.read()

code = code.replace("import React, { useState, useEffect } from 'react';", "import React, { useState, useEffect } from 'react';\nimport axios from 'axios';")

new_comp = """const EmployeeList = () => {
    const [employees, setEmployees] = useState([]);

    useEffect(() => {
        const fetchEmployees = async () => {
            try {
                const res = await axios.get('http://localhost:8080/api/users');
                setEmployees(res.data);
            } catch (error) {
                console.error("Lỗi khi tải danh sách nhân viên:", error);
            }
        };
        fetchEmployees();
    }, []);

    const formatTime = (timeStr) => {
        if (!timeStr || timeStr === 'Chưa từng hoạt động') return 'Chưa từng hoạt động';
        try {
            const date = new Date(timeStr);
            return date.toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' }) + ' ' + date.toLocaleDateString('vi-VN');
        } catch (e) {
            return timeStr;
        }
    };

    return (
        <div className="max-w-5xl mx-auto animate-in fade-in slide-in-from-bottom-4 duration-300">
            <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center mb-8 gap-4">
                <div>
                    <h2 className="text-2xl font-bold tracking-tight text-gray-900">Danh Sách Nhân Viên</h2>
                    <p className="text-sm text-gray-500 mt-1">Quản lý quyền truy cập của toàn bộ nhân viên trong công ty.</p>
                </div>
                
                <div className="relative w-full sm:w-auto">
                    <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
                        <Search size={16} className="text-gray-400" />
                    </div>
                    <input 
                        type="text" 
                        placeholder="Tìm kiếm nhân viên..." 
                        className="w-full sm:w-64 pl-10 pr-4 py-2 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-gray-900 focus:border-transparent transition-all"
                    />
                </div>
            </div>

            <div className="bg-white rounded-2xl shadow-sm border border-gray-200 overflow-hidden">
                <div className="overflow-x-auto">
                    <table className="w-full text-left border-collapse">
                        <thead>
                            <tr className="bg-gray-50/50 border-b border-gray-200 text-xs uppercase tracking-wider text-gray-500 font-semibold">
                                <th className="p-4 pl-6">Nhân Viên</th>
                                <th className="p-4">Username</th>
                                <th className="p-4">Phương thức</th>
                                <th className="p-4">Trạng thái</th>
                                <th className="p-4">Hoạt động cuối</th>
                                <th className="p-4 text-right pr-6">Thao tác</th>
                            </tr>
                        </thead>
                        <tbody className="divide-y divide-gray-100">
                            {employees.map((emp) => (
                                <tr key={emp.id} className="hover:bg-gray-50/50 transition-colors group">
                                    <td className="p-4 pl-6">
                                        <div className="flex items-center gap-3">
                                            <div className="w-10 h-10 rounded-full bg-gray-100 flex items-center justify-center font-bold text-gray-700">
                                                {emp.name ? emp.name.charAt(0).toUpperCase() : 'U'}
                                            </div>
                                            <span className="font-medium text-gray-900">{emp.name}</span>
                                        </div>
                                    </td>
                                    <td className="p-4 text-gray-500 text-sm">{emp.username}</td>
                                    <td className="p-4">
                                        <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-md text-xs font-medium bg-blue-50 text-blue-700">
                                            {emp.method}
                                        </span>
                                    </td>
                                    <td className="p-4">
                                        <div className="flex items-center gap-2">
                                            <div className={`w-2 h-2 rounded-full ${emp.status === 'Active' ? 'bg-green-500' : 'bg-gray-400'}`}></div>
                                            <span className="text-sm text-gray-600">{emp.status}</span>
                                        </div>
                                    </td>
                                    <td className="p-4 text-sm text-gray-500">{formatTime(emp.lastActive)}</td>
                                    <td className="p-4 pr-6 text-right">
                                        <button className="p-2 text-gray-400 hover:text-gray-900 hover:bg-gray-100 rounded-lg transition-colors">
                                            <MoreVertical size={16} />
                                        </button>
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            </div>
            
            <div className="mt-6 flex items-center justify-between text-sm text-gray-500">
                <p>Hiển thị danh sách nhân viên</p>
            </div>
        </div>
    );
};"""

code = re.sub(r'const EmployeeList = \(\) => \{.*?\n\};', new_comp, code, flags=re.DOTALL)

with open('e:/BTL_IOT/frontend/src/EmployeeList.jsx', 'w', encoding='utf-8') as f:
    f.write(code)
