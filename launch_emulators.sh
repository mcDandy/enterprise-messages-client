#!/bin/bash

# Could also be a Gradle script

echo "starting automatic emulator testing for Android"

# setup ANDROID_HOME
#export ANDROID_HOME="~/Android/Sdk" # setup ANDROID_SDK_ROOT
#export ANDROID_SDK_ROOT=$ANDROID_HOME
# setup adb path
#~/Library/Android/sdk/platform-tools:~/Library/Android/sdk/cmdline-tools/latest/bin:
echo "setup ENV"
export ANDROID_HOME=./Android/Sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME

echo "setup tool aliases"
android="./Library/Android/sdk/cmdline-tools/latest/bin/android"
adb="./Android/Sdk/platform-tools/adb"
script_status=0

echo "verify tools"
$android --version
$adb --version

echo "preparing gradlew"
chmod 0766 gradlew

if [[ $1 == "gradleTasks" ]];
then
    echo "listing gradlew tasks"
    ./gradlew tasks

    echo "reverting gradlew"
    chmod 0666 gradlew

    exit
fi

#if [[ $1 == "emus" ]];
#then
echo "starting 2 emulators"

$android emulator start small_phone
$android emulator start medium_phone
#fi

if [[ $1 == "gradleInstall" ]];
then
    echo "building, installing, and testing the appliction code"

    ./gradlew installDebug
    ./gradlew installDebugAndroidTest
fi

if [[ $1 == "androidTests" ]];
then
    echo "launching androidTests"

    ./gradlew connectedDebugAndroidTest
fi

if [[ $1 == "goal:test:multi" ]];
then
    echo "launching multi-emulator tests"

    #$adb -s emulator-5554 shell am instrument -w -e class cz.mkdaniel.enterprisemessenger.TwoEmulatorMessageFlowTest#twoEmulatorMessageFlow -e role sender -e runId run-001 -e username sender_test -e password test-password -e serverName "E2E Test Server" -e channelName "e2e-test" cz.mkdaniel.enterprisemessenger.test/androidx.test.runner.AndroidJUnitRunner

    #$adb -s emulator-5556 shell am instrument -w -e class cz.mkdaniel.enterprisemessenger.TwoEmulatorMessageFlowTest#twoEmulatorMessageFlow -e role receiver -e runId run-001 -e username receiver_test -e password test-password -e serverName "E2E Test Server" -e channelName "e2e-test" cz.mkdaniel.enterprisemessenger.test/androidx.test.runner.AndroidJUnitRunner

    ./gradlew installDebugAndroidTest

    $adb -s emulator-5556 shell am instrument -w \
	-e class cz.mkdaniel.enterprisemessenger.TwoEmulatorMessageFlowTest#twoEmulatorMessageFlow \
	-e role receiver \
	-e runId run-001 \
	-e username android_user3 \
    	-e password secret \
	-e serverName "test_servver" \
	-e channelName "test" \
	cz.mkdaniel.enterprisemessenger.test/androidx.test.runner.AndroidJUnitRunner &
    receiver_pid=$!
    $adb -s emulator-5554 shell am instrument -w \
	-e class cz.mkdaniel.enterprisemessenger.TwoEmulatorMessageFlowTest#twoEmulatorMessageFlow \
	-e username android_user2 \
    	-e password secret \
	-e serverName "test_servver" \
	-e role sender \
    	-e runId run-001 \
	-e channelName "test" \
	cz.mkdaniel.enterprisemessenger.test/androidx.test.runner.AndroidJUnitRunner
    sender_status=$?
    wait "$receiver_pid"
    receiver_status=$?
    if [[ $sender_status -ne 0 || $receiver_status -ne 0 ]];
    then
        echo "two-emulator message flow failed"
        script_status=1
    fi
fi

if [[ $1 == "goal:test:keyRotation" ]];
then
    echo "launching the two-emulator channel key rotation test"

    ./gradlew installDebugAndroidTest

    $adb -s emulator-5554 shell am instrument -w \
	-e class cz.mkdaniel.enterprisemessenger.ChannelKeyRotationTest#channelKeyRotationAfterAddAndRemove \
	-e role administrator \
	-e runId key-rotation-001 \
	-e username android_user2 \
	-e password secret \
	-e serverName "test_servver" \
	-e channelName "test" \
	-e memberUserId 2 \
	cz.mkdaniel.enterprisemessenger.test/androidx.test.runner.AndroidJUnitRunner &
    administrator_pid=$!

    $adb -s emulator-5556 shell am instrument -w \
	-e class cz.mkdaniel.enterprisemessenger.ChannelKeyRotationTest#channelKeyRotationAfterAddAndRemove \
	-e role member \
	-e runId key-rotation-001 \
	-e username android_user3 \
	-e password secret \
	-e serverName "test_servver" \
	-e channelName "test" \
	-e memberUserId 2 \
	cz.mkdaniel.enterprisemessenger.test/androidx.test.runner.AndroidJUnitRunner &

    member_pid=$!
    wait "$administrator_pid"
    administrator_status=$?
    wait "$member_pid"
    member_status=$?
    if [[ $administrator_status -ne 0 || $member_status -ne 0 ]];
    then
        echo "two-emulator key rotation test failed"
        script_status=1
    fi
fi

if [[ $1 == "logcats" ]];
then
    TERM_EMU=$2

    echo "starting logcats with $TERM_EMU as background jobs"

    #$TERM_EMU -e bash -c 'export ANDROID_HOME=./Android/Sdk; export ANDROID_SDK_ROOT=$ANDROID_HOME; ./Android/Sdk/platform-tools/adb -s emulator-5554 logcat *:S WebSocketClient:* CryptoManager:* ServerConnectionManager:* -T 1' &
    #$TERM_EMU -e bash -c 'pwd; ping ::1' &
    #$TERM_EMU -e bash -c "pwd; ping ::1" &
    #$TERM_EMU -e bash -c 'export ANDROID_HOME=./Android/Sdk; export ANDROID_SDK_ROOT=$ANDROID_HOME; ./Android/Sdk/platform-tools/adb -s emulator-5556 logcat *:S WebSocketClient:* CryptoManager:* ServerConnectionManager:* -T 1' &
fi

if [[ $1 == "goal:logcat:create:emu1" ]];
then
    $adb -s emulator-5554 logcat *:S WebSocketClient:* ServerConnectionManager:* CryptoManager:* -T 1
fi

if [[ $1 == "goal:logcat:create:emu2" ]];
then
    $adb -s emulator-5556 logcat *:S WebSocketClient:* ServerConnectionManager:* CryptoManager:* -T 1
fi

echo "reverting gradlew"
chmod 0666 gradlew

#if [[ $2 == "destroyEmus" ]];
if [[ $2 == "goal:emu:destroy" ]];
then
    echo "attempting to close 2 emulators"

    $android emulator stop small_phone
    $android emulator stop medium_phone
fi

echo "done, exiting"
exit "$script_status"
