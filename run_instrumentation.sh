#!/bin/bash
set -e

APK="android/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"

echo "Installing on Device 2 (Bob/Alice)..."
adb -s 3651585744453098 install -r -t $APK
adb -s 3651585744453098 install -r -t $TEST_APK

UNIQUE_ID=$(date +%s)
echo "Running tests with Unique ID: $UNIQUE_ID"
adb -s 3651585744453098 shell pm clear com.rknepp.parity
adb -s 3651585744453098 shell am instrument -w -e class com.rknepp.parity.E2ETests#testUnifiedE2EFlow -e uniqueId $UNIQUE_ID com.rknepp.parity.test/androidx.test.runner.AndroidJUnitRunner > test.log 2>&1

echo "Test Result:"
cat test.log
