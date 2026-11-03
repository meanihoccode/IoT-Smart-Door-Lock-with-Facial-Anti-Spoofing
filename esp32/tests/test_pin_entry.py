"""Host simulation of actual firmware keypad handler/guards; requires g++ on PATH.

Run from root: python esp32/tests/test_pin_entry.py
Hardware, HTTP, and WebSocket are stubbed; this never connects to a door.
"""
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[2]
source = (root / 'esp32/main/main.ino').read_text(encoding='utf-8')
handler = source.split('void handleKeypadInput(char key) {', 1)[1].split('// SETUP & MAIN LOOP', 1)[0]
handler = 'void handleKeypadInput(char key) {' + handler[:handler.rfind('// ============================================================================')]
expiry = source.split('  if (pinEntryActive && (isDoorUnlocked ||', 1)[1].split('  // Đọc bàn phím', 1)[0]
expiry = 'void expirePin() { unsigned long now = millis(); if (pinEntryActive && (isDoorUnlocked ||' + expiry + '}\n'
pir = source.split('  if (now > pirWarmupEnd &&', 1)[1].split(') {', 1)[0]
pir = 'bool canScanPir() { unsigned long now = millis(); return now > pirWarmupEnd &&' + pir + '; }\n'
stub = r'''
#include <cassert>
#include <climits>
#include <string>
#include <iostream>
struct String : std::string { using std::string::string; using std::string::operator=; };
struct Logger { template<class... T> void println(T...) {} template<class... T> void printf(T...) {} } Serial;
const int PIN_LED=19, HIGH=1, LOW=0;
const unsigned long PIN_ENTRY_TIMEOUT_MS=15000;
String inputPinBuffer;
bool pinEntryActive=false, isDoorUnlocked=false, isEmergencyLockdown=false, isSystemLockedOut=false;
unsigned long lastPinActivity=0, awaitCommandUntil=0, lockoutStartTime=0, pirWarmupEnd=0;
int consecutivePirFaceFails=0, failedPinAttempts=0, faceCalls=0, pinCalls=0;
unsigned long tick=100;
enum class Decision { Granted, Denied, Throttled, Lockdown, Unavailable };
Decision reply=Decision::Denied;
unsigned long millis() { return tick; }
void beep(int,int,int=0) {}
void digitalWrite(int,int) {}
void publishEvent(const String&) {}
void sendSecurityAlertWithPhoto(const String&,const String&) {}
void onGranted() { awaitCommandUntil=tick+15000; }
void onCloudError() {}
Decision captureAndVerifyFace() { ++faceCalls; return reply; }
Decision verifyPinWithBackend(const String&) { ++pinCalls; tick+=20000; return reply; }
void reset() {
 inputPinBuffer=""; pinEntryActive=false; isDoorUnlocked=false; isEmergencyLockdown=false;
 isSystemLockedOut=false; lastPinActivity=0; awaitCommandUntil=0; failedPinAttempts=0;
 faceCalls=pinCalls=0; tick=100; reply=Decision::Denied;
}
'''
cases = r'''
int main() {
 reset(); assert(canScanPir()); handleKeypadInput('1'); assert(pinEntryActive && inputPinBuffer=="1" && !canScanPir());
 handleKeypadInput('D'); assert(faceCalls==0);
 tick+=14999; expirePin(); assert(pinEntryActive); ++tick; expirePin(); assert(!pinEntryActive && inputPinBuffer.empty() && canScanPir());
 reset(); handleKeypadInput('1'); tick+=14000; handleKeypadInput('2'); tick+=1000; expirePin(); assert(pinEntryActive && inputPinBuffer=="12");
 for(char cancel : {'*','C'}) { reset(); handleKeypadInput('1'); handleKeypadInput(cancel); assert(!pinEntryActive && inputPinBuffer.empty() && canScanPir()); handleKeypadInput('D'); assert(faceCalls==1); }
 for(auto result : {Decision::Denied, Decision::Unavailable}) {
  reset(); handleKeypadInput('1'); reply=result; handleKeypadInput('#'); expirePin();
  assert(pinCalls==1 && pinEntryActive && inputPinBuffer.empty() && !canScanPir());
  assert(failedPinAttempts==(result==Decision::Denied ? 1 : 0));
  tick+=14999; expirePin(); assert(pinEntryActive); ++tick; expirePin(); assert(!pinEntryActive);
 }
 reset(); handleKeypadInput('1'); reply=Decision::Granted; handleKeypadInput('#'); tick+=20000; expirePin();
 assert(pinEntryActive && !canScanPir()); handleKeypadInput('D'); handleKeypadInput('2'); handleKeypadInput('#'); assert(faceCalls==0 && pinCalls==1 && inputPinBuffer.empty());
 handleKeypadInput('*'); assert(!canScanPir()); handleKeypadInput('D'); assert(faceCalls==0);
 reset(); handleKeypadInput('1'); isDoorUnlocked=true; expirePin(); assert(!pinEntryActive && inputPinBuffer.empty());
 reset(); isEmergencyLockdown=true; handleKeypadInput('1'); handleKeypadInput('D'); assert(!pinEntryActive && faceCalls==0);
 reset(); for(int i=0;i<3;++i) { handleKeypadInput('1'); handleKeypadInput('#'); } assert(isSystemLockedOut && !canScanPir());
 reset(); handleKeypadInput('#'); assert(pinCalls==0 && !pinEntryActive);
 reset(); tick=ULONG_MAX-5000; handleKeypadInput('1'); tick+=15000; expirePin(); assert(!pinEntryActive && inputPinBuffer.empty());
 std::cout << "PASS: first digit, D guard, timeout, activity refresh, both cancel keys, PIN denied/unavailable/granted, pending guards, unlock, lockdown, lockout, empty submit, clock wrap\n";
}
'''
cpp = root / '.runtime/pin_entry_test.cpp'
exe = root / '.runtime/pin_entry_test.exe'
cpp.parent.mkdir(parents=True, exist_ok=True)
cpp.write_text(stub + handler + expiry + pir + cases, encoding='utf-8')
subprocess.run(['g++', '-std=c++11', '-Wall', '-Wextra', str(cpp), '-o', str(exe)], check=True)
subprocess.run([str(exe)], check=True)
