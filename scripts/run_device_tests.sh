#!/usr/bin/env bash
# Run after ./gradlew installDebug installDebugAndroidTest.
# Permission revocation kills the app UID, so perform it before instrumentation.
set -euo pipefail
adb_bin="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/platform-tools/adb"
if [[ ! -x "$adb_bin" ]]; then adb_bin=adb; fi
mkdir -p evidence/device
collect_evidence() {
  local result=$?
  trap - EXIT
  if [[ -n "${logcat_pid:-}" ]]; then kill "$logcat_pid" 2>/dev/null || true; wait "$logcat_pid" 2>/dev/null || true; fi
  "$adb_bin" shell dumpsys meminfo com.huanban.ar > evidence/device/meminfo.txt 2>&1 || true
  "$adb_bin" shell dumpsys gfxinfo com.huanban.ar > evidence/device/gfxinfo.txt 2>&1 || true
  "$adb_bin" shell dumpsys activity lastanr > evidence/device/lastanr.txt 2>&1 || true
  "$adb_bin" exec-out screencap -p > evidence/device/final-screen.png 2>/dev/null || true
  # Native PixelCopy captures and measurements live in the debug app's private
  # files directory, not its external-files directory. run-as is debug-only.
  "$adb_bin" exec-out run-as com.huanban.ar tar -cf - files > evidence/device/app-private-files.tar 2> evidence/device/private-pull.txt || true
  "$adb_bin" pull /sdcard/Android/data/com.huanban.ar/files evidence/device/app-files > evidence/device/pull.txt 2>&1 || true
  "$adb_bin" pull /sdcard/Pictures/HuanBanAR evidence/device/pictures >> evidence/device/pull.txt 2>&1 || true
  "$adb_bin" pull /sdcard/Movies/HuanBanAR evidence/device/movies >> evidence/device/pull.txt 2>&1 || true
  exit "$result"
}
trap collect_evidence EXIT
"$adb_bin" logcat -c
"$adb_bin" logcat -v threadtime > evidence/device/logcat.txt 2>&1 &
logcat_pid=$!
"$adb_bin" shell getprop > evidence/device/device-properties.txt
"$adb_bin" shell am force-stop com.huanban.ar
"$adb_bin" shell pm revoke com.huanban.ar android.permission.CAMERA || true
"$adb_bin" shell appops set com.huanban.ar SYSTEM_ALERT_WINDOW deny
# A blocked native/UI thread must still yield logs instead of consuming the
# entire CI job. This is a suite timeout; individual measured modes need 60s.
timeout --signal=TERM --kill-after=30s 20m "$adb_bin" shell am instrument -w -r -e class com.huanban.ar.AppRegressionTest,com.huanban.ar.NativeIntegrationTest com.huanban.ar.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee evidence/device/instrumentation.txt
python3 - <<'PY'
import pathlib,re,sys
log=pathlib.Path('evidence/device/instrumentation.txt').read_text()
if not re.search(r'OK \(19 tests\)',log) or any(marker in log for marker in ['FAILURES!!!','INSTRUMENTATION_FAILED','Process crashed']):
    sys.exit('Android regression did not pass; retain logcat and inspect the failing test.')
PY
