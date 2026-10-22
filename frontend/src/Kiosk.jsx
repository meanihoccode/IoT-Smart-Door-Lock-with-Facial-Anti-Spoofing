import React, { useRef, useState, useCallback, useEffect } from 'react';
import Webcam from 'react-webcam';
import axios, { errorMessage } from './api';
import { Camera, KeyRound, ArrowLeft, ShieldCheck, ShieldAlert, Loader2, Wifi, RefreshCw, Settings2, Lock } from 'lucide-react';
import { pinCredentials } from './pin-credentials';
import { faceFailureMessage, FACE_TIMEOUT_MS, PIN_TIMEOUT_MS, UNCERTAIN_REQUEST_MESSAGE } from './kiosk-feedback';
import { Link } from 'react-router-dom';

const Kiosk = () => {
    const webcamRef = useRef(null);
    const espImgRef = useRef(null);
    const [status, setStatus] = useState('IDLE'); // IDLE, SCANNING, SUCCESS, FAILED
    const [message, setMessage] = useState('Vui lòng hướng mặt vào camera');
    const [pin, setPin] = useState('');
    const resetTimer = useRef(null);
    const pending = useRef(false);
    useEffect(() => () => clearTimeout(resetTimer.current), []);
    const [usePin, setUsePin] = useState(false);

    // Cấu hình Camera Source: 'ESP32' (mặc định) hoặc 'WEBCAM'
    const [cameraSource, setCameraSource] = useState(() => localStorage.getItem('smartlock_camera_source') || 'ESP32');
    const [esp32Ip, setEsp32Ip] = useState(() => localStorage.getItem('smartlock_esp32_ip') || '192.168.110.153');
    const [showIpConfig, setShowIpConfig] = useState(false);
    const [streamError, setStreamError] = useState(false);

    // Tự động làm mới khung hình camera ESP32 dạng snapshot liên tục (~10 FPS)
    const [frameTimestamp, setFrameTimestamp] = useState(Date.now());
    const frameTimer = useRef(null);
    const isPausedPolling = useRef(false);

    // Trạng thái khóa khẩn cấp & đồng bộ mở cửa từ backend
    const [isLockdown, setIsLockdown] = useState(false);
    const lastHandledUnlockTime = useRef(0);
    const failedPinCount = useRef(0);

    const saveCameraConfig = (source, ip) => {
        setCameraSource(source);
        localStorage.setItem('smartlock_camera_source', source);
        if (ip) {
            setEsp32Ip(ip);
            localStorage.setItem('smartlock_esp32_ip', ip);
        }
        setStreamError(false);
        setFrameTimestamp(Date.now());
    };

    const resetState = useCallback(() => {
        setStatus('IDLE');
        setMessage('Vui lòng hướng mặt vào camera');
        setPin('');
        clearTimeout(resetTimer.current);
    }, []);

    const scheduleReset = useCallback((delay) => {
        clearTimeout(resetTimer.current);
        resetTimer.current = setTimeout(resetState, delay);
    }, [resetState]);

    const showFaceFailure = useCallback((payload, fallback) => {
        setStatus('FAILED');
        setMessage(faceFailureMessage(payload, fallback));
        scheduleReset(8000);
    }, [scheduleReset]);

    // Xử lý nạp khung hình tiếp theo của Camera ESP32
    const handleFrameLoad = () => {
        setStreamError(false);
        if (isPausedPolling.current) return;
        clearTimeout(frameTimer.current);
        // Sau 85ms yêu cầu khung hình tiếp theo -> ~11 FPS cực mượt mà không nghẽn socket
        frameTimer.current = setTimeout(() => {
            setFrameTimestamp(Date.now());
        }, 85);
    };

    const handleFrameError = () => {
        clearTimeout(frameTimer.current);
        // Nếu lỗi tạm thời (ESP32 đang chụp hoặc mạng chậm), thử lại sau 1.2s
        frameTimer.current = setTimeout(() => {
            setFrameTimestamp(Date.now());
        }, 1200);
    };

    useEffect(() => {
        return () => clearTimeout(frameTimer.current);
    }, []);

    // Lấy Blob ảnh hiện tại từ Camera (cho cả nhận diện và báo động kẻ lạ)
    const getCurrentImageBlob = async () => {
        let blob = null;
        const cleanIp = esp32Ip.replace(/^http:\/\//, '').replace(/\/$/, '');

        if (cameraSource === 'ESP32') {
            // Thử fetch trực tiếp từ /capture
            try {
                const res = await fetch(`http://${cleanIp}/capture`, { mode: 'cors' });
                if (res.ok) {
                    blob = await res.blob();
                }
            } catch {
                // Nếu fetch bị CORS hoặc bận, vẽ ảnh hiện tại lên canvas
                if (espImgRef.current && espImgRef.current.complete) {
                    try {
                        const canvas = document.createElement('canvas');
                        canvas.width = espImgRef.current.naturalWidth || 640;
                        canvas.height = espImgRef.current.naturalHeight || 480;
                        const ctx = canvas.getContext('2d');
                        ctx.drawImage(espImgRef.current, 0, 0);
                        blob = await new Promise(resolve => canvas.toBlob(resolve, 'image/jpeg', 0.9));
                    } catch {}
                }
            }
        }

        if (!blob && webcamRef.current) {
            const imageSrc = webcamRef.current?.getScreenshot();
            if (imageSrc) {
                const res = await fetch(imageSrc);
                blob = await res.blob();
            }
        }

        return blob;
    };

    // Gửi cảnh báo kẻ lạ kèm ảnh lên Backend
    const sendSecurityAlert = async (alertType, desc) => {
        try {
            const blob = await getCurrentImageBlob();
            const formData = new FormData();
            if (blob) {
                formData.append('file', blob, 'intruder.jpg');
            }
            formData.append('alertType', alertType);
            formData.append('description', desc);
            await axios.post('/security-alerts', formData, {
                headers: { 'Content-Type': 'multipart/form-data' }
            });
        } catch (e) {
            console.error('Không thể gửi cảnh báo kẻ lạ:', e);
        }
    };

    // ĐỒNG BỘ TRẠNG THÁI CỬA VÀ KHÓA KHẨN CẤP TỪ BACKEND
    useEffect(() => {
        const checkDoorStatus = async () => {
            try {
                const res = await axios.get('/door-status');
                const { lockdown, unlocked, lastMessage, timestamp } = res.data;

                setIsLockdown(Boolean(lockdown));

                // Nếu có sự kiện mở cửa mới từ phần cứng hoặc web
                if (unlocked && timestamp && timestamp > lastHandledUnlockTime.current) {
                    lastHandledUnlockTime.current = timestamp;
                    if (!pending.current) {
                        setStatus('SUCCESS');
                        setMessage(lastMessage || 'Cửa đã mở khóa thành công!');
                        scheduleReset(4000);
                    }
                }
            } catch (err) {
                // Ignore network error on polling
            }
        };

        checkDoorStatus();
        const pollInterval = setInterval(checkDoorStatus, 1000);
        return () => clearInterval(pollInterval);
    }, [scheduleReset]);

    // Chụp ảnh từ Camera để nhận diện khuôn mặt
    const captureAndVerify = useCallback(async () => {
        if (pending.current) return;
        if (isLockdown) {
            setStatus('FAILED');
            setMessage('Cửa đang bị vô hiệu hóa khẩn cấp bởi Quản trị viên!');
            scheduleReset(6000);
            return;
        }

        pending.current = true;
        isPausedPolling.current = true;
        clearTimeout(resetTimer.current);

        setStatus('SCANNING');
        setMessage('Đang xử lý nhận diện khuôn mặt...');

        try {
            const blob = await getCurrentImageBlob();

            if (!blob) {
                showFaceFailure(null, 'Không thể lấy hình ảnh từ Camera. Hãy kiểm tra kết nối ESP32 hoặc cấp quyền Webcam.');
                return;
            }

            const formData = new FormData();
            formData.append('file', blob, 'face.jpg');

            const response = await axios.post('/verify-face', formData, {
                headers: { 'Content-Type': 'multipart/form-data' },
                timeout: FACE_TIMEOUT_MS,
            });

            if (response.data.status === 'success') {
                setStatus('SUCCESS');
                setMessage(response.data.message || 'Đã xác thực và gửi lệnh mở cửa.');
                scheduleReset(4000);
            } else {
                showFaceFailure(response.data, 'Không thể xác thực khuôn mặt.');
            }
        } catch (error) {
            if (error.response?.status === 403) {
                setStatus('FAILED');
                setMessage('Cửa đang bị vô hiệu hóa khẩn cấp bởi Quản trị viên!');
                scheduleReset(6000);
            } else {
                showFaceFailure(error.response?.data,
                    !error.response ? UNCERTAIN_REQUEST_MESSAGE : 'Không thể xác thực khuôn mặt.');
            }
        } finally {
            pending.current = false;
            isPausedPolling.current = false;
            setFrameTimestamp(Date.now());
        }
    }, [cameraSource, esp32Ip, isLockdown, showFaceFailure, scheduleReset]);

    // Xử lý gửi mã PIN 6 số trực tiếp (Phương án A - không cần mã nhân viên)
    const handlePinSubmit = async (e) => {
        e.preventDefault();
        if (pending.current) return;
        if (isLockdown) {
            setStatus('FAILED');
            setMessage('Cửa đang bị vô hiệu hóa khẩn cấp bởi Quản trị viên!');
            scheduleReset(6000);
            return;
        }

        let credentials;
        try {
            credentials = pinCredentials(null, pin);
        } catch (error) {
            clearTimeout(resetTimer.current);
            setStatus('FAILED');
            setMessage(error.message);
            return;
        }

        pending.current = true;
        clearTimeout(resetTimer.current);
        const currentPin = pin;
        setPin('');

        try {
            setStatus('SCANNING');
            setMessage('Đang xác thực mã PIN...');
            const response = await axios.post('/verify-pin', credentials, { timeout: PIN_TIMEOUT_MS });
            
            if (response.data.status === 'success') {
                failedPinCount.current = 0;
                setStatus('SUCCESS');
                setMessage(response.data.message || 'Mở cửa thành công!');
                scheduleReset(4000);
            } else {
                failedPinCount.current++;
                handlePinFail();
            }
        } catch (error) {
            failedPinCount.current++;
            if (error.response?.status === 403) {
                setStatus('FAILED');
                setMessage('Cửa đang bị vô hiệu hóa khẩn cấp bởi Quản trị viên!');
                scheduleReset(6000);
            } else {
                handlePinFail();
            }
        } finally {
            pending.current = false;
        }
    };

    const handlePinFail = () => {
        setStatus('FAILED');
        const count = failedPinCount.current;
        if (count >= 3) {
            setMessage('CẢNH BÁO: Nhập sai mã PIN 3 lần liên tiếp! Đang gửi ảnh cảnh báo...');
            sendSecurityAlert('WRONG_PIN_3_TIMES', 'Nhập sai mã PIN 3 lần liên tiếp trên Web Kiosk');
            failedPinCount.current = 0;
            scheduleReset(8000);
        } else {
            setMessage(`Mã PIN không đúng (lần ${count}/3).`);
            scheduleReset(5000);
        }
    };

    const cleanIp = esp32Ip.replace(/^http:\/\//, '').replace(/\/$/, '');
    const currentFrameUrl = `http://${cleanIp}/capture?t=${frameTimestamp}`;

    return (
        <div className="min-h-screen bg-[#F9FAFB] flex flex-col font-sans relative selection:bg-gray-200">
            {/* Top Bar for Kiosk */}
            <header className="w-full p-6 flex justify-between items-center bg-white border-b border-gray-200">
                <div className="flex items-center gap-3">
                    <div className="w-10 h-10 bg-gray-900 text-white rounded-xl flex items-center justify-center shadow-sm">
                        <ShieldCheck size={20} />
                    </div>
                    <div>
                        <span className="font-semibold text-lg text-gray-900 tracking-tight block">SmartLock AIoT</span>
                        <div className="flex items-center gap-2 text-xs text-gray-500">
                            <span className={`inline-block w-2 h-2 rounded-full ${isLockdown ? 'bg-red-500 animate-pulse' : 'bg-green-500'}`}></span>
                            <span>{isLockdown ? 'HỆ THỐNG ĐANG BỊ VÔ HIỆU HÓA' : `Nguồn Camera: ${cameraSource === 'ESP32' ? `ESP32-S3 (${cleanIp})` : 'Webcam Laptop'}`}</span>
                        </div>
                    </div>
                </div>
                <div className="flex items-center gap-4">
                    <button 
                        onClick={() => setShowIpConfig(!showIpConfig)} 
                        title="Cài đặt IP Camera ESP32"
                        className="p-2 text-gray-500 hover:text-gray-900 rounded-lg hover:bg-gray-100 transition-colors"
                    >
                        <Settings2 size={18} />
                    </button>
                    <Link to="/admin" className="text-sm font-medium text-gray-500 hover:text-gray-900 transition-colors">
                        Admin Portal
                    </Link>
                </div>
            </header>

            {/* BANNER CẢNH BÁO NẾU CỬA BỊ ADMIN VÔ HIỆU HÓA */}
            {isLockdown && (
                <div className="w-full bg-red-600 text-white px-6 py-3 shadow-md flex items-center justify-center gap-2 text-sm font-bold tracking-wide animate-pulse">
                    <ShieldAlert size={20} />
                    <span>CỬA ĐANG BỊ VÔ HIỆU HÓA KHẨN CẤP BỞI QUẢN TRỊ VIÊN. MỌI YÊU CẦU MỞ CỬA BỊ TỪ CHỐI!</span>
                </div>
            )}

            {/* Popup Cài đặt IP ESP32 */}
            {showIpConfig && (
                <div className="w-full max-w-xl mx-auto mt-4 p-4 bg-white border border-gray-200 rounded-xl shadow-sm flex flex-col gap-3">
                    <div className="flex justify-between items-center">
                        <span className="text-sm font-semibold text-gray-800">Cấu hình nguồn Camera:</span>
                        <button onClick={() => setShowIpConfig(false)} className="text-xs text-gray-400 hover:text-gray-700">Đóng</button>
                    </div>
                    <div className="flex gap-2">
                        <button 
                            type="button"
                            onClick={() => saveCameraConfig('ESP32', esp32Ip)}
                            className={`flex-1 py-2 text-xs font-medium rounded-lg border transition-all ${cameraSource === 'ESP32' ? 'bg-gray-900 text-white border-gray-900' : 'bg-gray-50 text-gray-700 border-gray-200'}`}
                        >
                            Camera OV2640 (ESP32-S3)
                        </button>
                        <button 
                            type="button"
                            onClick={() => saveCameraConfig('WEBCAM')}
                            className={`flex-1 py-2 text-xs font-medium rounded-lg border transition-all ${cameraSource === 'WEBCAM' ? 'bg-gray-900 text-white border-gray-900' : 'bg-gray-50 text-gray-700 border-gray-200'}`}
                        >
                            Webcam Laptop
                        </button>
                    </div>
                    {cameraSource === 'ESP32' && (
                        <div className="flex gap-2 items-center">
                            <span className="text-xs text-gray-500 whitespace-nowrap">IP ESP32:</span>
                            <input 
                                type="text" 
                                value={esp32Ip} 
                                onChange={(e) => setEsp32Ip(e.target.value)}
                                placeholder="192.168.110.153" 
                                className="flex-1 text-xs border rounded px-3 py-1.5 font-mono"
                            />
                            <button 
                                onClick={() => saveCameraConfig('ESP32', esp32Ip)}
                                className="text-xs bg-gray-100 hover:bg-gray-200 px-3 py-1.5 rounded font-medium text-gray-700"
                            >
                                Lưu
                            </button>
                        </div>
                    )}
                </div>
            )}

            {/* Main Layout */}
            <main className="flex-1 flex flex-col items-center justify-center p-6">
                <div className="w-full max-w-xl mx-auto flex flex-col items-center">
                    
                    {/* Header Text */}
                    <div className="text-center mb-8">
                        <h1 className="text-2xl font-bold text-gray-900 tracking-tight mb-2">Xác thực danh tính</h1>
                        <p className="text-gray-500 text-sm">Nhìn vào camera hoặc bấm mã PIN 6 số để mở khóa cửa.</p>
                    </div>

                    {/* Interactive Area */}
                    <div className="w-full bg-white rounded-2xl shadow-[0_2px_8px_rgba(0,0,0,0.04)] border border-gray-100 p-6 overflow-hidden">
                        {!usePin ? (
                            <div className="flex flex-col items-center animate-in fade-in zoom-in-95 duration-200">
                                {/* Camera Box: Xem trực tiếp mượt mà ngay trên Kiosk */}
                                <div className="relative w-full aspect-[4/3] bg-gray-900 rounded-xl overflow-hidden border border-gray-200 mb-6 group flex items-center justify-center">
                                    {cameraSource === 'ESP32' && !streamError ? (
                                        <img 
                                            ref={espImgRef}
                                            src={currentFrameUrl}
                                            alt="ESP32-S3 Camera Live"
                                            onLoad={handleFrameLoad}
                                            onError={handleFrameError}
                                            className={`w-full h-full object-cover transition-opacity duration-200 ${status === 'SCANNING' ? 'opacity-50 grayscale' : 'opacity-100'}`}
                                        />
                                    ) : (
                                        <Webcam
                                            audio={false}
                                            ref={webcamRef}
                                            screenshotFormat="image/jpeg"
                                            className={`w-full h-full object-cover transition-opacity duration-300 ${status === 'SCANNING' ? 'opacity-50 grayscale' : 'opacity-100'}`}
                                            mirrored={true}
                                            onUserMediaError={() => {
                                                if (!pending.current) showFaceFailure(null, 'Không thể truy cập Webcam laptop.');
                                            }}
                                        />
                                    )}

                                    {/* Cảnh báo nếu Stream ESP32 bị lỗi */}
                                    {cameraSource === 'ESP32' && streamError && (
                                        <div className="absolute inset-0 bg-gray-900/90 text-white flex flex-col items-center justify-center p-6 text-center">
                                            <Wifi size={32} className="text-yellow-400 mb-3 animate-pulse" />
                                            <p className="text-sm font-medium mb-1">Chưa kết nối được Camera OV2640</p>
                                            <p className="text-xs text-gray-400 mb-4">Đang truy cập: http://{cleanIp}/capture</p>
                                            <div className="flex gap-2">
                                                <button 
                                                    onClick={() => { setStreamError(false); setFrameTimestamp(Date.now()); }} 
                                                    className="text-xs bg-white text-gray-900 font-semibold px-3 py-1.5 rounded-lg flex items-center gap-1"
                                                >
                                                    <RefreshCw size={12} /> Thử lại
                                                </button>
                                                <button 
                                                    onClick={() => saveCameraConfig('WEBCAM')} 
                                                    className="text-xs bg-gray-700 hover:bg-gray-600 px-3 py-1.5 rounded-lg text-white"
                                                >
                                                    Dùng Webcam Laptop
                                                </button>
                                            </div>
                                        </div>
                                    )}
                                    
                                    {/* Focus Reticle (Minimal) */}
                                    {status === 'IDLE' && !streamError && (
                                        <div className="absolute inset-0 pointer-events-none flex items-center justify-center">
                                            <div className="w-48 h-64 border-2 border-white/50 rounded-3xl shadow-sm"></div>
                                        </div>
                                    )}

                                    {/* Scanning State Overlay */}
                                    {status === 'SCANNING' && (
                                        <div className="absolute inset-0 flex flex-col items-center justify-center bg-black/30">
                                            <Loader2 size={36} className="text-white animate-spin mb-3" />
                                            <span className="text-xs font-semibold text-white tracking-wide">ĐANG PHÂN TÍCH AI...</span>
                                        </div>
                                    )}
                                </div>

                                {/* Status Message Area */}
                                <div className={`w-full text-center py-3 mb-6 rounded-lg text-sm font-medium transition-colors ${
                                    status === 'SUCCESS' ? 'bg-green-50 text-green-700 font-bold' :
                                    status === 'FAILED' ? 'bg-red-50 text-red-700' :
                                    'text-gray-600'
                                }`}>
                                    {message}
                                </div>

                                {/* Actions */}
                                <div className="w-full flex gap-3">
                                    <button 
                                        onClick={captureAndVerify}
                                        disabled={status === 'SCANNING' || isLockdown}
                                        className="flex-1 bg-gray-900 hover:bg-black text-white font-medium py-3 px-4 rounded-xl transition-all active:scale-[0.98] disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center gap-2 shadow-sm"
                                    >
                                        <Camera size={18} />
                                        {isLockdown ? 'Đã vô hiệu hóa' : 'Quét khuôn mặt'}
                                    </button>
                                    <button 
                                        onClick={() => { resetState(); setUsePin(true); }}
                                        disabled={status === 'SCANNING' || isLockdown}
                                        title="Chuyển sang mở khóa bằng mã PIN"
                                        className="bg-white hover:bg-gray-50 text-gray-700 font-medium py-3 px-6 rounded-xl border border-gray-200 transition-all active:scale-[0.98] disabled:opacity-50 flex items-center justify-center shadow-sm"
                                    >
                                        <KeyRound size={18} />
                                    </button>
                                </div>
                            </div>
                        ) : (
                            /* GIAO DIỆN NHẬP PIN PHƯƠNG ÁN A (CHỈ CẦN NHẬP MÃ PIN 6 SỐ) */
                            <div className="flex flex-col animate-in fade-in slide-in-from-right-4 duration-200">
                                <form onSubmit={handlePinSubmit} className="flex flex-col items-center">
                                    <div className="w-16 h-16 bg-gray-50 border border-gray-100 rounded-full flex items-center justify-center mb-4 text-gray-700 shadow-inner">
                                        <KeyRound size={28} />
                                    </div>

                                    <h2 className="text-base font-semibold text-gray-900 mb-1">Mã PIN mở khóa</h2>
                                    <p className="text-xs text-gray-500 mb-6">Nhập trực tiếp 6 chữ số mã PIN của bạn</p>
                                    
                                    <input
                                        type="password" 
                                        aria-label="Mã PIN 6 số" 
                                        required 
                                        inputMode="numeric" 
                                        pattern="[0-9]{6,10}" 
                                        minLength={6} 
                                        maxLength={10} 
                                        autoComplete="off"
                                        autoFocus
                                        value={pin} 
                                        onChange={(e) => setPin(e.target.value.replace(/\D/g, ''))} 
                                        placeholder="••••••"
                                        className="w-full text-center text-4xl tracking-[0.4em] font-mono py-4 border-b-2 border-gray-200 focus:border-gray-900 outline-none bg-transparent transition-colors mb-8 placeholder:tracking-normal placeholder:text-gray-300 placeholder:text-2xl"
                                        disabled={status === 'SCANNING' || isLockdown}
                                    />
                                    
                                    {/* Status Message Area */}
                                    {(status === 'FAILED' || status === 'SUCCESS' || status === 'SCANNING') && (
                                        <div className={`w-full text-center py-2 mb-6 rounded-lg text-sm font-medium ${
                                            status === 'SUCCESS' ? 'text-green-600 bg-green-50 font-bold' :
                                            status === 'FAILED' ? 'text-red-600 bg-red-50' :
                                            'text-gray-600'
                                        }`}>
                                            {message}
                                        </div>
                                    )}

                                    <div className="w-full flex gap-3">
                                        <button 
                                            type="button"
                                            disabled={status === 'SCANNING'}
                                            onClick={() => {setUsePin(false); resetState();}}
                                            className="w-14 bg-white hover:bg-gray-50 text-gray-600 font-medium py-3 rounded-xl border border-gray-200 transition-all active:scale-[0.98] flex items-center justify-center shadow-sm"
                                        >
                                            <ArrowLeft size={18} />
                                        </button>
                                        <button 
                                            type="submit"
                                            disabled={status === 'SCANNING' || !pin || pin.length < 6 || isLockdown}
                                            className="flex-1 bg-gray-900 hover:bg-black text-white font-medium py-3 px-4 rounded-xl transition-all active:scale-[0.98] disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center gap-2 shadow-sm"
                                        >
                                            {status === 'SCANNING' ? <Loader2 size={18} className="animate-spin" /> : isLockdown ? 'Đã vô hiệu hóa' : 'Mở khóa'}
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
