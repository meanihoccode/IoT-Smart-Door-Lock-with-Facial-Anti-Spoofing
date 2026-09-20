// Keep PIN as text: numeric conversion would discard leading zeroes.
export function pinCredentials(username, pin) {
  const code = username.trim();
  if (!/^[A-Za-z0-9_-]{1,64}$/.test(code)) throw new Error('Nhập mã hồ sơ gồm chữ, số, gạch ngang hoặc gạch dưới.');
  if (typeof pin !== 'string' || !/^[0-9]{6,10}$/.test(pin)) throw new Error('PIN cần từ 6 đến 10 chữ số.');
  return { username: code, pinCode: pin };
}
