import { useEffect, useMemo, useState } from 'react';
import api from './api';

const FRAME_INTERVAL_MS = 200; // Target ~5 requests/s, including transfer and decoding time.
const FRAME_MIN_DELAY_MS = 75; // Still yield when a slow request exceeds the target interval.
const FRAME_RETRY_MS = 1000;

// Polls the latest door-camera frame. Display only: frames never go to the AI or open the door.
// Polling keeps the device streaming; it stops when the tab is hidden or `on` is false.
export function useLiveFrame(endpoint, on = true) {
    const [frame, setFrame] = useState(null);
    // Old/revoked URLs must not reappear after toggling the camera or changing endpoints.
    const session = useMemo(() => ({ endpoint, on }), [endpoint, on]);
    useEffect(() => {
        if (!on) return undefined;
        let active = true, timer, url, image;
        const controller = new AbortController();
        const urls = new Set();
        const release = (value) => {
            if (urls.delete(value)) URL.revokeObjectURL(value);
        };
        const tick = async () => {
            const started = performance.now();
            let received = false, next;
            if (!document.hidden) {
                try {
                    const response = await api.get(endpoint, {
                        responseType: 'blob', signal: controller.signal, timeout: 5000,
                    });
                    if (!active) return;
                    if (response.status === 200) {
                        next = URL.createObjectURL(response.data);
                        urls.add(next);
                        image = new Image();
                        image.src = next;
                        await image.decode();
                        if (!active) return;
                        if (!document.hidden) {
                            const previous = url;
                            url = next;
                            next = undefined;
                            setFrame({ session, src: url, waiting: false });
                            // Keep the decoded previous frame until the next browser paint.
                            requestAnimationFrame(() => release(previous));
                            received = true;
                        }
                    } else {
                        setFrame(previous => ({ session, src: previous?.session === session ? previous.src : null, waiting: true }));
                    }
                } catch {
                    if (active) setFrame(previous => ({ session, src: previous?.session === session ? previous.src : null, waiting: true }));
                } finally {
                    release(next);
                    image = undefined;
                }
            }
            const delay = received
                ? Math.max(FRAME_MIN_DELAY_MS, FRAME_INTERVAL_MS - (performance.now() - started))
                : FRAME_RETRY_MS;
            if (active) timer = setTimeout(tick, delay);
        };
        tick();
        return () => {
            active = false;
            clearTimeout(timer);
            controller.abort();
            if (image) image.src = '';
            for (const value of urls) release(value);
        };
    }, [on, endpoint, session]);
    return frame?.session === session ? { src: frame.src, waiting: frame.waiting } : { src: null, waiting: true };
}
