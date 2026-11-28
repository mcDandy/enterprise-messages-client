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
test_package="cz.mkdaniel.enterprisemessenger.test"
test_runner="androidx.test.runner.AndroidJUnitRunner"
test_component="${test_package}/${test_runner}"
script_status=0

run_instrumented_test() {
    local class_name="$1"
    local method_name="$2"
    local device="${3:-emulator-5554}"

    if ! ./gradlew installDebug installDebugAndroidTest; then
        return 1
    fi

    "$adb" -s "$device" shell am instrument -w \
        -e class "cz.mkdaniel.enterprisemessenger.${class_name}#${method_name}" \
        "$test_component"
}

if [[ $1 == "goal:test:all" ]];
then
    echo "running all JVM unit and integration tests (excluding instrumentation tests)"
    ./gradlew testDebugUnitTest
    exit "$?"
fi

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

case "$1" in
    "goal:test:appContext")
        run_instrumented_test "AppContextInstrumentedTest" "useAppContext" "$2" ||
            script_status=1
        ;;
    "goal:test:login")
        run_instrumented_test "AuthenticationInstrumentedTest" "login_success" "$2" ||
            script_status=1
        ;;
    "goal:test:registerScreen")
        run_instrumented_test "AuthenticationInstrumentedTest" "register_screen_opens" "$2" ||
            script_status=1
        ;;
    "goal:test:navigation")
        run_instrumented_test "NavigationInstrumentedTest" "select_channel_success" "$2" ||
            script_status=1
        ;;
    "goal:test:sendMessage")
        run_instrumented_test "MessagingInstrumentedTest" "send_message_success" "$2" ||
            script_status=1
        ;;
    "goal:test:createServer")
        run_instrumented_test "ServerManagementInstrumentedTest" "create_server_success" "$2" ||
            script_status=1
        ;;
    "goal:test:createChannel")
        run_instrumented_test "ChannelManagementInstrumentedTest" "create_channel_success" "$2" ||
            script_status=1
        ;;
    "goal:test:addChannelMember")
        run_instrumented_test "ChannelManagementInstrumentedTest" "add_member_to_channel_success" "$2" ||
            script_status=1
        ;;
    "goal:test:changeDisplayName")
        run_instrumented_test "AccountSettingsInstrumentedTest" "change_display_name_success" "$2" ||
            script_status=1
        ;;
    "goal:test:logout")
        run_instrumented_test "AccountSettingsInstrumentedTest" "logout_success" "$2" ||
            script_status=1
        ;;
    "goal:test:androidStorageLegacyType")
        run_instrumented_test "AndroidStorageIntegrationTest" "sharedPreferencesSinkDropsLegacyTypeMismatches" "$2" ||
            script_status=1
        ;;
    "goal:test:androidStorageKeyStore")
        run_instrumented_test "AndroidStorageIntegrationTest" "sealedValueCanBeOpenedByANewStoreUsingTheAndroidKeyStore" "$2" ||
            script_status=1
        ;;
    "goal:test:notificationChannel")
        run_instrumented_test "NotificationHelperInstrumentedTest" "createsTheExpectedNotificationChannel" "$2" ||
            script_status=1
        ;;
    "goal:test:notificationUnreadCount")
        run_instrumented_test "NotificationHelperInstrumentedTest" "channelUnreadCountIncrementsAndResetsWhenOpened" "$2" ||
            script_status=1
        ;;
    "goal:test:localNetworkPermission")
        run_instrumented_test "LocalNetworkPermissionInstrumentedTest" "classifiesPrivateAndLinkLocalAddressesAsLocal" "$2" ||
            script_status=1
        ;;
    "goal:test:publicNetworkPermission")
        run_instrumented_test "LocalNetworkPermissionInstrumentedTest" "doesNotRequestLocalAccessForPublicAddressesOrLoopback" "$2" ||
            script_status=1
        ;;
    "goal:test:networkHostnamePermission")
        run_instrumented_test "LocalNetworkPermissionInstrumentedTest" "treatsHostnamesConservativelyButIgnoresBlankInput" "$2" ||
            script_status=1
        ;;
esac

if [[ $1 == "goal:test:multi" ]];
then
    echo "launching multi-emulator tests"

    #$adb -s emulator-5554 shell am instrument -w -e class cz.mkdaniel.enterprisemessenger.TwoEmulatorMessageFlowTest#twoEmulatorMessageFlow -e role sender -e runId run-001 -e username sender_test -e password test-password -e serverName "E2E Test Server" -e channelName "e2e-test" "$test_component"

    #$adb -s emulator-5556 shell am instrument -w -e class cz.mkdaniel.enterprisemessenger.TwoEmulatorMessageFlowTest#twoEmulatorMessageFlow -e role receiver -e runId run-001 -e username receiver_test -e password test-password -e serverName "E2E Test Server" -e channelName "e2e-test" "$test_component"

    ./gradlew installDebug installDebugAndroidTest

    $adb -s emulator-5556 shell am instrument -w \
	-e class cz.mkdaniel.enterprisemessenger.TwoEmulatorMessageFlowTest#twoEmulatorMessageFlow \
	-e role receiver \
	-e runId run-001 \
	-e username android_user3 \
    	-e password secret \
	-e serverName "test_servver" \
	-e channelName "test" \
	"$test_component" &
    receiver_pid=$!
    $adb -s emulator-5554 shell am instrument -w \
	-e class cz.mkdaniel.enterprisemessenger.TwoEmulatorMessageFlowTest#twoEmulatorMessageFlow \
	-e username android_user2 \
    	-e password secret \
	-e serverName "test_servver" \
	-e role sender \
    	-e runId run-001 \
	-e channelName "test" \
	"$test_component"
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

    ./gradlew installDebug installDebugAndroidTest

    $adb -s emulator-5554 shell am instrument -w \
	-e class cz.mkdaniel.enterprisemessenger.ChannelKeyRotationTest#channelKeyRotationAfterAddAndRemove \
	-e role administrator \
	-e runId key-rotation-001 \
	-e username android_user2 \
	-e password secret \
	-e serverName "test_servver" \
	-e channelName "test" \
	-e memberUserId 2 \
	"$test_component" &
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
	"$test_component" &

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
