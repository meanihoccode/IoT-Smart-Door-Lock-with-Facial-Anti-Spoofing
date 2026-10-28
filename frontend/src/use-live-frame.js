import { useEffect, useState } from 'react';
import api from './api';

const FRAME_POLL_MS = 350;

// Polls the latest door-camera frame. Display only: frames never go to the AI or open the door.
// Polling keeps the device streaming; it stops when the tab is hidden or `on` is false.
export function useLiveFrame(endpoint, on = true) {
    const [src, setSrc] = useState(null);
    const [waiting, setWaiting] = useState(true);
    useEffect(() => {
        if (!on) return undefined;
        let active = true, timer, url;
        const tick = async () => {
            if (!document.hidden) {
                try {
                    const response = await api.get(endpoint, { responseType: 'blob' });
                    if (!active) return;
                    if (response.status === 200) {
                        const next = URL.createObjectURL(response.data);
                        setSrc(next); setWaiting(false);
                        if (url) URL.revokeObjectURL(url);
                        url = next;
                    } else setWaiting(true);
                } catch { if (active) setWaiting(true); }
            }
            if (active) timer = setTimeout(tick, FRAME_POLL_MS);
        };
        tick();
        return () => { active = false; clearTimeout(timer); if (url) URL.revokeObjectURL(url); };
    }, [on, endpoint]);
    return { src, waiting };
}
