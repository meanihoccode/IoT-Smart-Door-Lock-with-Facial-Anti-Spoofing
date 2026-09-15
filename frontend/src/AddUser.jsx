import React, { useState } from 'react';
import axios from './api';
import { ImagePlus, KeyRound, User, Loader2, CheckCircle2, AlertCircle, Save } from 'lucide-react';

const AddUser = () => {
    const [formData, setFormData] = useState({
        username: '',
        fullName: '',
        pinCode: '',
        file: null
    });
    const [previewUrl, setPreviewUrl] = useState(null);
    const [status, setStatus] = useState({ type: '', message: '' });
    const [loading, setLoading] = useState(false);

    const handleInputChange = (e) => {
        const { name, value } = e.target;
        setFormData(prev => ({ ...prev, [name]: value }));
    };

    const handleFileChange = (e) => {
        const file = e.target.files[0];
        if (file) {
            setFormData(prev => ({ ...prev, file }));
            setPreviewUrl(URL.createObjectURL(file));
        }
    };

    const handleSubmit = async (e) => {
        e.preventDefault();
        
        if (!formData.username || !formData.fullName || !formData.pinCode || !formData.file) {
            setStatus({ type: 'error', message: 'Vui lòng điền đầy đủ thông tin và chọn ảnh hợp lệ.' });
            return;
        }

        const data = new FormData();
        data.append('username', formData.username);
        data.append('fullName', formData.fullName);
        data.append('pinCode', formData.pinCode);
        data.append('file', formData.file);

        setLoading(true);
        setStatus({ type: 'info', message: 'Đang trích xuất Vector khuôn mặt...' });

        try {
            const response = await axios.post('/register', data, {
                headers: { 'Content-Type': 'multipart/form-data' }
            });

            if (response.data.status === 'success') {
                setStatus({ type: 'success', message: 'Cấp quyền mở khóa thành công.' });
                setFormData({ username: '', fullName: '', pinCode: '', file: null });
                setPreviewUrl(null);
            }
        } catch (error) {
            const errorMsg = error.response?.data?.message || 'Có lỗi xảy ra khi kết nối tới máy chủ.';
            setStatus({ type: 'error', message: errorMsg });
        } finally {
            setLoading(false);
        }
    };

    return (
        <div className="max-w-2xl mx-auto">
            <div className="mb-8">
                <h2 className="text-2xl font-bold tracking-tight text-gray-900">Thêm người dùng mới</h2>
                <p className="text-sm text-gray-500 mt-1">Hệ thống sẽ trích xuất vector khuôn mặt và lưu trữ an toàn dưới dạng mã hóa.</p>
            </div>

            {status.message && (
                <div className={`mb-6 p-4 rounded-xl flex items-start gap-3 text-sm font-medium animate-in fade-in slide-in-from-top-2 border ${
                    status.type === 'success' ? 'bg-green-50/50 text-green-700 border-green-200/50' :
                    status.type === 'error' ? 'bg-red-50/50 text-red-700 border-red-200/50' :
                    'bg-gray-50 text-gray-700 border-gray-200'
                }`}>
                    {status.type === 'success' && <CheckCircle2 className="shrink-0 w-5 h-5 mt-0.5 text-green-600" />}
                    {status.type === 'error' && <AlertCircle className="shrink-0 w-5 h-5 mt-0.5 text-red-600" />}
                    {status.type === 'info' && <Loader2 className="animate-spin shrink-0 w-5 h-5 mt-0.5 text-gray-500" />}
                    <div className="leading-relaxed">{status.message}</div>
                </div>
            )}

            <div className="bg-white rounded-2xl shadow-sm border border-gray-200 overflow-hidden">
                <form onSubmit={handleSubmit} className="p-6 sm:p-8">
                    <div className="space-y-6">
                        
                        <div className="grid grid-cols-1 sm:grid-cols-2 gap-6">
                            <div className="space-y-1.5">
                                <label className="text-sm font-medium text-gray-700 flex items-center gap-2">
                                    Mã nhân viên (Username)
                                </label>
                                <div className="relative">
                                    <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
                                        <User size={16} className="text-gray-400" />
                                    </div>
                                    <input 
                                        type="text" 
                                        name="username" 
                                        value={formData.username} 
                                        onChange={handleInputChange} 
                                        placeholder="VD: nv001"
                                        className="w-full pl-10 pr-4 py-2.5 rounded-lg border border-gray-300 focus:border-gray-900 focus:ring-1 focus:ring-gray-900 transition-shadow outline-none text-sm placeholder:text-gray-400 bg-white"
                                    />
                                </div>
                            </div>
                            
                            <div className="space-y-1.5">
                                <label className="text-sm font-medium text-gray-700 flex items-center gap-2">
                                    Họ và Tên
                                </label>
                                <input 
                                    type="text" 
                                    name="fullName" 
                                    value={formData.fullName} 
                                    onChange={handleInputChange} 
                                    placeholder="Nguyễn Văn A"
                                    className="w-full px-4 py-2.5 rounded-lg border border-gray-300 focus:border-gray-900 focus:ring-1 focus:ring-gray-900 transition-shadow outline-none text-sm placeholder:text-gray-400 bg-white"
                                />
                            </div>
                        </div>

                        <div className="space-y-1.5">
                            <label className="text-sm font-medium text-gray-700 flex items-center gap-2">
                                Mã PIN dự phòng
                            </label>
                            <div className="relative max-w-xs">
                                <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
                                    <KeyRound size={16} className="text-gray-400" />
                                </div>
                                <input 
                                    type="password" 
                                    name="pinCode" 
                                    value={formData.pinCode} 
                                    onChange={handleInputChange} 
                                    placeholder="••••••"
                                    maxLength="10"
                                    className="w-full pl-10 pr-4 py-2.5 rounded-lg border border-gray-300 focus:border-gray-900 focus:ring-1 focus:ring-gray-900 transition-shadow outline-none tracking-widest font-mono bg-white"
                                />
                            </div>
                            <p className="text-xs text-gray-500">Sử dụng để mở cửa khi camera hoặc mạng có sự cố.</p>
                        </div>

                        <div className="space-y-1.5 pt-2">
                            <label className="text-sm font-medium text-gray-700 flex items-center gap-2">
                                Dữ liệu khuôn mặt
                            </label>
                            
                            <div className="mt-1 flex justify-center px-6 pt-5 pb-6 border-2 border-gray-300 border-dashed rounded-xl hover:bg-gray-50/50 transition-colors relative group">
                                <input 
                                    type="file" 
                                    accept="image/*" 
                                    onChange={handleFileChange}
                                    className="absolute inset-0 w-full h-full opacity-0 cursor-pointer z-10"
                                />
                                
                                <div className="space-y-2 text-center">
                                    {previewUrl ? (
                                        <div className="flex flex-col items-center">
                                            <div className="w-24 h-24 rounded-full overflow-hidden border-2 border-gray-200 mb-3 group-hover:border-gray-300 transition-colors bg-gray-50">
                                                <img src={previewUrl} alt="Preview" className="w-full h-full object-cover" />
                                            </div>
                                            <div className="flex text-sm text-gray-600">
                                                <span className="font-medium text-gray-900 underline decoration-gray-300 underline-offset-2 cursor-pointer">Đổi ảnh khác</span>
                                            </div>
                                        </div>
                                    ) : (
                                        <>
                                            <div className="mx-auto h-12 w-12 text-gray-300 flex items-center justify-center">
                                                <ImagePlus size={32} strokeWidth={1.5} />
                                            </div>
                                            <div className="flex text-sm text-gray-600 justify-center">
                                                <span className="font-medium text-gray-900 underline decoration-gray-300 underline-offset-2 cursor-pointer">Tải ảnh lên</span>
                                                <span className="pl-1">hoặc kéo thả vào đây</span>
                                            </div>
                                            <p className="text-xs text-gray-500">PNG, JPG, GIF up to 5MB</p>
                                        </>
                                    )}
                                </div>
                            </div>
                        </div>
                    </div>
                    
                    <div className="mt-8 pt-6 border-t border-gray-100 flex items-center justify-end gap-3">
                        <button 
                            type="button" 
                            className="px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-lg hover:bg-gray-50 transition-colors active:bg-gray-100"
                            onClick={() => {setFormData({ username: '', fullName: '', pinCode: '', file: null }); setPreviewUrl(null);}}
                        >
                            Hủy
                        </button>
                        <button 
                            type="submit" 
                            disabled={loading}
                            className="flex items-center justify-center gap-2 px-6 py-2 rounded-lg font-medium text-sm text-white bg-gray-900 hover:bg-black transition-all active:scale-[0.98] disabled:opacity-50 disabled:cursor-not-allowed shadow-sm"
                        >
                            {loading ? (
                                <><Loader2 className="animate-spin" size={16} /> Đang lưu...</>
                            ) : (
                                <><Save size={16} /> Lưu hồ sơ</>
                            )}
                        </button>
                    </div>
                </form>
            </div>
        </div>
    );
};

export default AddUser;
