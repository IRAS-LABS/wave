#!/usr/bin/env bash
# Installs Wave to every attached device, owner profile only.
#
# The --user 0 is not optional. On phones with secondary profiles - work
# profiles, vendor app-cloning features, secure folders - a bare `adb install`
# sprays the package into every one of them, leaving duplicate launcher icons
# that then have to be hunted down profile by profile.
set -u

PKG=com.wave.scanner
APK=$(dirname "$0")/../app/build/outputs/apk/release/app-release.apk

[ -f "$APK" ] || { echo "no APK at $APK - run ./gradlew assembleRelease"; exit 1; }

serials=$(adb devices | awk '/\tdevice$/ {print $1}')
[ -n "$serials" ] || { echo "no authorised devices attached"; exit 1; }

for s in $serials; do
  model=$(adb -s "$s" shell getprop ro.product.model | tr -d '\r')
  rel=$(adb -s "$s" shell getprop ro.build.version.release | tr -d '\r')
  echo "=== $model (Android $rel, $s) ==="

  # Clear the package out of every profile first, not just user 0, so a stray
  # clone from an earlier mistake does not survive the reinstall.
  for u in $(adb -s "$s" shell pm list users | grep -o 'UserInfo{[0-9]*' | cut -d{ -f2); do
    if adb -s "$s" shell pm list packages --user "$u" 2>/dev/null | grep -q "^package:$PKG$"; then
      echo "  removing existing copy from user $u"
      adb -s "$s" shell pm uninstall --user "$u" "$PKG" >/dev/null 2>&1
    fi
  done

  echo "  installing to user 0"
  out=$(adb -s "$s" install --user 0 -r -d "$APK" 2>&1 | tail -2)
  echo "  $out"
done
