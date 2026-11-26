#!/bin/bash

# Could also be a Gradle script

echo "starting automatic emulator testing for Android"

# setup ANDROID_HOME
#export ANDROID_HOME="~/Android/Sdk"
# setup ANDROID_SDK_ROOT
#export ANDROID_SDK_ROOT=$ANDROID_HOME
# setup adb path
#~/Library/Android/sdk/platform-tools:~/Library/Android/sdk/cmdline-tools/latest/bin:
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

    android emulator start small_phone
    android emulator start medium_phone
#fi

if [[ $1 == "gradleInstall" ]];
then
    echo "building, installing, and testing the appliction code"

    ./gradlew installDebug
    #./gradlew installDebugAndroidTest
fi

if [[ $1 == "androidTests" ]];
then
    echo "launching androidTests"

    ./gradlew connectedDebugAndroidTest
fi

if [[ $1 == "logcats" ]];
then
    TERM_EMU=$2

    echo "starting logcats with xterm as background jobs"

    $TERM_EMU -e bash -c 'adb -s emulator-5554 logcat *:S WebSocketClient:* CryptoManager:* ServerConnectionManager:* -T 1' &
    $TERM_EMU -e bash -c 'adb -s emulator-5556 logcat *:S WebSocketClient:* CryptoManager:* ServerConnectionManager:* -T 1' &
fi

echo "reverting gradlew"
chmod 0666 gradlew

echo "attempting to close 2 emulators"

android emulator stop small_phone
android emulator stop medium_phone

echo "done, exiting"
