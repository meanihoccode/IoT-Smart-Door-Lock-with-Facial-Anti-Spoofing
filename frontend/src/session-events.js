const CHANNEL = 'smartlock-session';
export function announceSession(type) {
  if (typeof BroadcastChannel === 'undefined') return;
  const channel = new BroadcastChannel(CHANNEL);
  channel.postMessage(type); channel.close();
}
export function listenSession(listener) {
  if (typeof BroadcastChannel === 'undefined') return () => {};
  const channel = new BroadcastChannel(CHANNEL);
  channel.onmessage = event => listener(event.data);
  return () => channel.close();
}
