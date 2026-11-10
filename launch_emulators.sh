#!/bin/bash

# Could also be a Gradle script

echo "starting automatic emulator testing for Android"

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

echo "starting 2 emulators"
android emulator start small_phone
android emulator start medium_phone

if [[ $1 == "gradleInstall" ]];
then
    echo "building, installing, and testing the appliction code"

    ./gradlew installDebug
    ./gradlew installDebugAndroidTest
fi

echo "launching androidTests"
./gradlew connectedDebugAndroidTest

if [[ $1 == "logcats" ]];
then
    echo "starting logcats with xterm as background jobs"

    xterm -e bash -c 'adb -s emulator-5554 logcat *:S WebSocketClient:* CryptoManager:* ServerConnectionManager:* -T 1' &
    xterm -e bash -c 'adb -s emulator-5556 logcat *:S WebSocketClient:* CryptoManager:* ServerConnectionManager:* -T 1' &
fi

echo "reverting gradlew"
chmod 0666 gradlew

echo "done, exiting"
