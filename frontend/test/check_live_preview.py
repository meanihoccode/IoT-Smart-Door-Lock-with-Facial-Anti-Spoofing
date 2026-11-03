"""Browser regression test with simulated frames/API; never connects to a door.

Requires Python Playwright and an installed Chromium. Build frontend first, then:
python frontend/test/check_live_preview.py --build-dir frontend/dist [--browser PATH]
"""
import argparse
import asyncio
import base64
import json
import mimetypes
from pathlib import Path
from urllib.parse import urlparse

from playwright.async_api import async_playwright


async def check(args):
    build = Path(args.build_dir).resolve()
    state = {'count': 0, 'delay': 0.08, 'mode': 'image'}
    async with async_playwright() as p:
        browser = await p.chromium.launch(headless=True, **({'executable_path': args.browser} if args.browser else {}))
        page = await browser.new_page()
        jpeg = base64.b64decode(await page.evaluate("""() => {
            const canvas = document.createElement('canvas'); canvas.width = canvas.height = 16;
            return canvas.toDataURL('image/jpeg').split(',')[1];
        }"""))
        await page.add_init_script("""
            window.previewTest = { urls: new Set(), decoded: new Set(), errors: [], hidden: false, decoding: 0 };
            Object.defineProperty(document, 'hidden', { get: () => previewTest.hidden });
            const create = URL.createObjectURL.bind(URL), revoke = URL.revokeObjectURL.bind(URL);
            URL.createObjectURL = blob => { const url = create(blob); previewTest.urls.add(url); return url; };
            URL.revokeObjectURL = url => { previewTest.urls.delete(url); revoke(url); };
            const decode = HTMLImageElement.prototype.decode;
            HTMLImageElement.prototype.decode = async function() {
                previewTest.decoding++;
                try { await decode.call(this); await new Promise(resolve => setTimeout(resolve, 100)); previewTest.decoded.add(this.src); }
                finally { previewTest.decoding--; }
            };
            document.addEventListener('DOMContentLoaded', () => {
                new MutationObserver(() => {
                    const image = document.querySelector('img[alt="Camera ESP32 trực tiếp"]');
                    if (image && !previewTest.decoded.has(image.src)) previewTest.errors.push('Displayed before decode: ' + image.src);
                }).observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['src'] });
            });
        """)
        errors, inflight = [], set()
        peak = 0
        page.on('pageerror', lambda error: errors.append(str(error)))

        def started(request):
            nonlocal peak
            if urlparse(request.url).path == '/api/admin/door/live':
                inflight.add(request)
                peak = max(peak, len(inflight))

        def finished(request):
            inflight.discard(request)

        def failed(request):
            if request in inflight:
                errors.append('aborted-preview')
            finished(request)

        page.on('request', started)
        page.on('requestfinished', finished)
        page.on('requestfailed', failed)

        async def route_request(route):
            path = urlparse(route.request.url).path
            assert route.request.method == 'GET', 'Unexpected write: ' + path
            if path == '/api/admin/door/live':
                state['count'] += 1
                mode = state['mode']
                await asyncio.sleep(state['delay'])
                try:
                    await route.fulfill(status=204 if mode == 'empty' else 200, content_type='image/jpeg',
                                        body=b'' if mode == 'empty' else b'invalid-image' if mode == 'bad' else jpeg)
                except Exception:
                    # Cancellation is expected when the view is switched off/unmounted.
                    if not route.request.failure:
                        raise
            elif path.startswith('/api/'):
                if path == '/api/auth/me':
                    data = {'username': 'preview-test'}
                elif path == '/api/overview':
                    data = {'totalEmployees': 0, 'accessesToday': 0, 'recentLogs': []}
                elif path == '/api/admin/door/last-capture':
                    await route.fulfill(status=204)
                    return
                else:
                    data = {'commands': [], 'connection': 'OFFLINE', 'lockdown': False}
                await route.fulfill(content_type='application/json', body=json.dumps(data))
            else:
                file = build / path.lstrip('/') if path.startswith('/assets/') else build / 'index.html'
                await route.fulfill(content_type=mimetypes.guess_type(str(file))[0] or 'text/plain', body=file.read_bytes())

        await page.route('**/*', route_request)
        await page.goto('http://preview.test/admin')
        image = page.get_by_alt_text('Camera ESP32 trực tiếp', exact=True)
        await image.wait_for()
        await page.wait_for_timeout(1500)
        assert 4 <= state['count'] <= 10, state
        assert peak == 1, peak
        assert not await page.evaluate('previewTest.errors.length')

        # A slow network must not create overlapping requests.
        state['delay'] = 0.45
        await page.wait_for_timeout(1800)
        assert peak == 1, peak

        # A corrupt image keeps the last good frame; candidates are released.
        state['mode'] = 'bad'
        await page.wait_for_timeout(750)
        previous = await image.get_attribute('src')
        await page.wait_for_timeout(1200)
        assert await image.get_attribute('src') == previous
        assert await page.evaluate('previewTest.urls.size') == 1

        state.update(mode='empty', delay=0.08)
        before = state['count']
        await page.wait_for_timeout(2200)
        assert state['count'] - before <= 3, state

        # Hiding while decoding must not permanently stop the polling loop.
        state['mode'] = 'image'
        await page.wait_for_function('previewTest.decoding > 0')
        await page.evaluate('previewTest.hidden = true')
        await page.wait_for_timeout(1200)
        before = state['count']
        await page.wait_for_timeout(1100)
        assert state['count'] == before
        await page.evaluate('previewTest.hidden = false')
        await page.wait_for_function('previewTest.decoding > 0')
        await page.wait_for_timeout(200)

        # Stopping during a request aborts it and frees every blob URL.
        state['delay'] = 1
        await page.wait_for_timeout(400)
        await page.get_by_role('button', name='Tắt xem trực tiếp', exact=True).click()
        before = state['count']
        await page.wait_for_timeout(1200)
        assert state['count'] == before
        assert await page.evaluate('previewTest.urls.size') == 0
        assert 'aborted-preview' in errors
        state['delay'] = 0.08
        await page.get_by_role('button', name='Bật xem trực tiếp', exact=True).click()
        await image.wait_for()
        await page.wait_for_timeout(300)
        await page.get_by_role('link', name='Đổi mật khẩu', exact=True).click()
        await page.wait_for_timeout(300)
        assert await page.evaluate('previewTest.urls.size') == 0
        assert not await page.evaluate('previewTest.errors.length')
        assert not [error for error in errors if error != 'aborted-preview'], errors
        print('PASS: decoded frames, bounded polling, single request on slow network, corrupt/empty frames, hidden/resume, abort, toggle and unmount cleanup. All API simulated; no writes.')
        await browser.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--build-dir', default='frontend/dist')
    parser.add_argument('--browser')
    asyncio.run(check(parser.parse_args()))
