import React, { useRef, useState, useCallback, useEffect } from 'react';
import Webcam from 'react-webcam';
import axios, { errorMessage } from './api';
import { Camera, KeyRound, ArrowLeft, ShieldCheck, ShieldAlert, Loader2 } from 'lucide-react';
import { Link } from 'react-router-dom';

const Kiosk = () => {
    const webcamRef = useRef(null);
    const [status, setStatus] = useState('IDLE'); // IDLE, SCANNING, SUCCESS, FAILED
    const [message, setMessage] = useState('Vui lòng hướng mặt vào camera');
    const [pin, setPin] = useState('');
    const [usePin, setUsePin] = useState(false);

    const resetState = useCallback(() => {
        setStatus('IDLE');
        setMessage('Vui lòng hướng mặt vào camera');
        setPin('');
    }, []);

    const captureAndVerify = useCallback(async () => {
        const imageSrc = webcamRef.current?.getScreenshot();
        if (!imageSrc) return;

        setStatus('SCANNING');
        setMessage('Đang xử lý...');

        try {
            const res = await fetch(imageSrc);
            const blob = await res.blob();
            const formData = new FormData();
            formData.append('file', blob, 'face.jpg');

            const response = await axios.post('/verify-face', formData, {
                headers: { 'Content-Type': 'multipart/form-data' }
            });

            if (response.data.status === 'success') {
                setStatus('SUCCESS');
                setMessage(response.data.message || 'Mở khóa thành công');
                setTimeout(() => resetState(), 3000);
            }
        } catch (error) {
            const errorData = error.response?.data;
            setStatus('FAILED');
            if (errorData?.message?.includes('giả mạo')) {
                 setMessage('Phát hiện giả mạo. Từ chối truy cập.');
            } else {
                 setMessage(errorData?.message || 'Không thể nhận diện');
            }
            setTimeout(() => resetState(), 3000);
        }
    }, [webcamRef, resetState]);

    const handlePinSubmit = async (e) => {
        e.preventDefault();
        if (!pin) return;
        try {
            setStatus('SCANNING');
            setMessage('Đang xác thực mã PIN...');
            const response = await axios.post('/verify-pin', { pinCode: pin });
            
            if (response.data.status === 'success') {
                setStatus('SUCCESS');
                setMessage('Mở khóa thành công');
                setTimeout(() => resetState(), 3000);
            }
        } catch (error) {
            setStatus('FAILED');
            setMessage(error.response?.status === 401 ? 'Mã PIN không chính xác' : errorMessage(error));
            setTimeout(() => resetState(), 3000);
        }
    };

    return (
        <div className="min-h-screen bg-[#F9FAFB] flex flex-col font-sans relative selection:bg-gray-200">
            {/* Top Bar for Kiosk */}
            <header className="w-full p-6 flex justify-between items-center bg-white border-b border-gray-200">
                <div className="flex items-center gap-3">
                    <div className="w-10 h-10 bg-gray-900 text-white rounded-xl flex items-center justify-center shadow-sm">
                        <ShieldCheck size={20} />
                    </div>
                    <span className="font-semibold text-lg text-gray-900 tracking-tight">SmartLock OS</span>
                </div>
                <Link to="/admin" className="text-sm font-medium text-gray-500 hover:text-gray-900 transition-colors">
                    Admin Portal
                </Link>
            </header>

            {/* Main Layout */}
            <main className="flex-1 flex flex-col items-center justify-center p-6">
                <div className="w-full max-w-xl mx-auto flex flex-col items-center">
                    
                    {/* Header Text */}
                    <div className="text-center mb-8">
                        <h1 className="text-2xl font-bold text-gray-900 tracking-tight mb-2">Xác thực danh tính</h1>
                        <p className="text-gray-500 text-sm">Sử dụng khuôn mặt hoặc mã PIN để mở khóa cửa.</p>
                    </div>

                    {/* Interactive Area */}
                    <div className="w-full bg-white rounded-2xl shadow-[0_2px_8px_rgba(0,0,0,0.04)] border border-gray-100 p-6 overflow-hidden">
                        {!usePin ? (
                            <div className="flex flex-col items-center animate-in fade-in zoom-in-95 duration-200">
                                {/* Camera Box */}
                                <div className="relative w-full aspect-[4/3] bg-gray-50 rounded-xl overflow-hidden border border-gray-200 mb-6 group">
                                    <Webcam
                                        audio={false}
                                        ref={webcamRef}
                                        screenshotFormat="image/jpeg"
                                        className={`w-full h-full object-cover transition-opacity duration-300 ${status === 'SCANNING' ? 'opacity-50 grayscale' : 'opacity-100'}`}
                                        mirrored={true}
                                    />
                                    
                                    {/* Focus Reticle (Minimal) */}
                                    {status === 'IDLE' && (
                                        <div className="absolute inset-0 pointer-events-none flex items-center justify-center">
                                            <div className="w-48 h-64 border border-white/40 rounded-3xl"></div>
                                        </div>
                                    )}

                                    {/* Scanning State Overlay */}
                                    {status === 'SCANNING' && (
                                        <div className="absolute inset-0 flex flex-col items-center justify-center">
                                            <Loader2 size={32} className="text-gray-900 animate-spin mb-3" />
                                        </div>
                                    )}
                                </div>

                                {/* Status Message Area */}
                                <div className={`w-full text-center py-3 mb-6 rounded-lg text-sm font-medium transition-colors ${
                                    status === 'SUCCESS' ? 'bg-green-50 text-green-700' :
                                    status === 'FAILED' ? 'bg-red-50 text-red-700' :
                                    'text-gray-600'
                                }`}>
                                    {message}
                                </div>

                                {/* Actions */}
                                <div className="w-full flex gap-3">
                                    <button 
                                        onClick={captureAndVerify}
                                        disabled={status === 'SCANNING'}
                                        className="flex-1 bg-gray-900 hover:bg-black text-white font-medium py-3 px-4 rounded-xl transition-all active:scale-[0.98] disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center gap-2 shadow-sm"
                                    >
                                        <Camera size={18} />
                                        Quét khuôn mặt
                                    </button>
                                    <button 
                                        onClick={() => setUsePin(true)}
                                        disabled={status === 'SCANNING'}
                                        className="bg-white hover:bg-gray-50 text-gray-700 font-medium py-3 px-6 rounded-xl border border-gray-200 transition-all active:scale-[0.98] disabled:opacity-50 flex items-center justify-center shadow-sm"
                                    >
                                        <KeyRound size={18} />
                                    </button>
                                </div>
                            </div>
                        ) : (
                            <div className="flex flex-col animate-in fade-in slide-in-from-right-4 duration-200">
                                <form onSubmit={handlePinSubmit} className="flex flex-col items-center">
                                    <div className="w-16 h-16 bg-gray-50 border border-gray-100 rounded-full flex items-center justify-center mb-6 text-gray-400">
                                        <KeyRound size={28} />
                                    </div>
                                    
                                    <input 
                                        type="password" 
                                        value={pin} 
                                        onChange={(e) => setPin(e.target.value)} 
                                        placeholder="Nhập mã PIN"
                                        className="w-full text-center text-3xl tracking-[0.5em] font-mono py-4 border-b-2 border-gray-200 focus:border-gray-900 outline-none bg-transparent transition-colors mb-8 placeholder:tracking-normal placeholder:text-gray-300 placeholder:text-lg"
                                        maxLength="10"
                                        autoFocus
                                    />
                                    
                                    {/* Status Message Area */}
                                    {(status === 'FAILED' || status === 'SUCCESS' || status === 'SCANNING') && (
                                        <div className={`w-full text-center py-2 mb-6 rounded-lg text-sm font-medium ${
                                            status === 'SUCCESS' ? 'text-green-600' :
                                            status === 'FAILED' ? 'text-red-600' :
                                            'text-gray-600'
                                        }`}>
                                            {message}
                                        </div>
                                    )}

                                    <div className="w-full flex gap-3">
                                        <button 
                                            type="button"
                                            onClick={() => {setUsePin(false); resetState();}}
                                            className="w-14 bg-white hover:bg-gray-50 text-gray-600 font-medium py-3 rounded-xl border border-gray-200 transition-all active:scale-[0.98] flex items-center justify-center shadow-sm"
                                        >
                                            <ArrowLeft size={18} />
                                        </button>
                                        <button 
                                            type="submit"
                                            disabled={status === 'SCANNING' || !pin}
                                            className="flex-1 bg-gray-900 hover:bg-black text-white font-medium py-3 px-4 rounded-xl transition-all active:scale-[0.98] disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center gap-2 shadow-sm"
                                        >
                                            {status === 'SCANNING' ? <Loader2 size={18} className="animate-spin" /> : 'Xác nhận'}
                                        </button>
                                    </div>
                                </form>
                            </div>
                        )}
                    </div>

                </div>
            </main>
        </div>
    );
};

export default Kiosk;
