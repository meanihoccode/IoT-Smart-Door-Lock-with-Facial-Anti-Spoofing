import React from 'react';
import { Outlet, Link, useLocation } from 'react-router-dom';
import { UserPlus, LogOut, LayoutDashboard, Users, ShieldCheck, Settings } from 'lucide-react';

const AdminLayout = () => {
    const location = useLocation();
    const currentPath = location.pathname;

    const isActive = (path) => currentPath === path;

    return (
        <div className="min-h-screen bg-[#F9FAFB] flex font-sans text-gray-900 selection:bg-gray-200">
            {/* Sidebar */}
            <aside className="w-64 bg-white border-r border-gray-200 flex flex-col shrink-0">
                <div className="h-16 flex items-center px-6 border-b border-gray-100">
                    <div className="flex items-center gap-2 font-bold text-lg tracking-tight">
                        <div className="bg-gray-900 text-white p-1.5 rounded-lg shadow-sm">
                            <ShieldCheck size={18} />
                        </div>
                        SmartLock Admin
                    </div>
                </div>
                
                <nav className="flex-1 px-4 py-6 space-y-1">
                    <div className="px-3 py-2 text-xs font-semibold text-gray-400 uppercase tracking-wider mb-2">Quản lý</div>
                    
                    <Link 
                        to="/admin" 
                        className={`flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-colors ${
                            isActive('/admin') ? 'bg-gray-100 text-gray-900' : 'text-gray-600 hover:bg-gray-50 hover:text-gray-900'
                        }`}
                    >
                        <LayoutDashboard size={18} className={isActive('/admin') ? 'text-gray-700' : 'text-gray-400'} />
                        Tổng quan
                    </Link>
                    
                    <Link 
                        to="/admin/add-user" 
                        className={`flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-colors ${
                            isActive('/admin/add-user') ? 'bg-gray-100 text-gray-900' : 'text-gray-600 hover:bg-gray-50 hover:text-gray-900'
                        }`}
                    >
                        <UserPlus size={18} className={isActive('/admin/add-user') ? 'text-gray-700' : 'text-gray-400'} />
                        Cấp quyền truy cập
                    </Link>

                    <Link 
                        to="/admin/users" 
                        className={`flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-colors ${
                            isActive('/admin/users') ? 'bg-gray-100 text-gray-900' : 'text-gray-600 hover:bg-gray-50 hover:text-gray-900'
                        }`}
                    >
                        <Users size={18} className={isActive('/admin/users') ? 'text-gray-700' : 'text-gray-400'} />
                        Danh sách nhân viên
                    </Link>
                </nav>

                <div className="p-4 border-t border-gray-100">
                    <Link to="/kiosk" className="flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium text-gray-500 hover:bg-gray-50 hover:text-gray-900 transition-colors">
                        <LogOut size={18} />
                        Thoát về Kiosk
                    </Link>
                </div>
            </aside>

            {/* Main Content Area */}
            <main className="flex-1 flex flex-col min-w-0">
                {/* Header */}
                <header className="h-16 bg-white border-b border-gray-200 flex items-center px-8 justify-between shrink-0">
                    <h1 className="text-lg font-semibold text-gray-800">
                        {isActive('/admin') ? 'Tổng Quan' : isActive('/admin/add-user') ? 'Cấp Quyền Truy Cập' : 'Danh Sách Nhân Viên'}
                    </h1>
                    <div className="flex items-center gap-4">
                        <button className="text-gray-400 hover:text-gray-600 transition-colors">
                            <Settings size={20} />
                        </button>
                        <div className="w-8 h-8 rounded-full bg-gray-200 border border-gray-300 flex items-center justify-center text-sm font-medium text-gray-600 cursor-pointer hover:bg-gray-300 transition-colors">
                            A
                        </div>
                    </div>
                </header>

                {/* Page Content (Outlet renders the matched child route) */}
                <div className="flex-1 overflow-auto p-8">
                    <Outlet />
                </div>
            </main>
        </div>
    );
};

export default AdminLayout;
