```md
# Enterprise Messages Client

A secure end-to-end encrypted messaging Android client for connecting to a messaging server, managing accounts, joining rooms, and sending/receiving encrypted messages.

## Overview

This project is an Android application built with Java and Gradle. It provides a client-side interface for:
- account registration and login
- server configuration and connection management
- room browsing and selection
- real-time messaging
- secure cryptographic key initialization and message protection
- Android notifications and navigation-based UI flow

The app is organized as a standard Android project with a single `app` module and uses Jetpack Navigation, Material Components, RecyclerView, OkHttp, and AndroidX libraries.

## Features

- User authentication flow with login and registration screens
- Server selection and connection management
- Room-based messaging interface
- Secure client-side encryption key handling
- WebSocket-based communication with backend server
- Android notification support
- Responsive Material UI with navigation drawer and bottom navigation
- Settings screen and app configuration support

## Project Structure

```text
enterprise-messages-client/
├── app/
│   ├── src/
│   │   ├── androidTest/
│   │   ├── main/
│   │   │   ├── java/
│   │   │   │   └── cz/mkdaniel/enterprisemessenger/
│   │   │   │       ├── MainActivity.java
│   │   │   │       ├── crypto/
│   │   │   │       │   └── CryptoManager.java
│   │   │   │       ├── net/
│   │   │   │       │   ├── AccountStore.java
│   │   │   │       │   ├── Packet.java
│   │   │   │       │   ├── ServerConnectionManager.java
│   │   │   │       │   └── WebSocketClient.java
│   │   │   │       ├── notification/
│   │   │   │       └── ui/
│   │   │   │           ├── auth/
│   │   │   │           ├── message/
│   │   │   │           ├── server/
│   │   │   │           ├── settings/
│   │   │   │           └── transform/
│   │   │   ├── res/
│   │   │   │   ├── drawable/
│   │   │   │   ├── layout/
│   │   │   │   ├── menu/
│   │   │   │   ├── navigation/
│   │   │   │   ├── values/
│   │   │   │   ├── values-night/
│   │   │   │   └── xml/
│   │   │   ├── AndroidManifest.xml
│   │   │   └── keepRules/
│   │   └── test/
│   ├── build.gradle
│   └── .gitignore
├── build.gradle
├── settings.gradle
├── gradlew
├── gradlew.bat
├── gradle.properties
├── gradle/
├── README.md
├── .gitignore
└── te
```

## Technology Stack

- Java
- Android SDK
- Gradle
- AndroidX / Jetpack
- Material Components
- Navigation Component
- Lifecycle components
- RecyclerView
- OkHttp
- WebSocket communication
- Local key management with cryptographic utilities

## Architecture Notes

The app follows a typical Android modular structure:

- `ui`: screens and fragments for login, register, settings, rooms, messages, and transform pages
- `net`: server connection management, packet processing, account storage, and WebSocket communication
- `crypto`: cryptographic functions and ECC key initialization
- `notification`: Android notification setup and permission handling
- `MainActivity`: application entry point, navigation setup, and app startup behavior

The app initializes the local EC keypair during startup and configures notification permissions as part of the main activity lifecycle.

## Build and Run

### Requirements

- Android Studio
- JDK 11 or newer
- Android SDK with API level 35 support
- A configured Android emulator or physical device

### Build

```bash
./gradlew assembleDebug
```

### Run

Open the project in Android Studio and deploy the app to an emulator or connected Android device, or use:

```bash
./gradlew installDebug
```

## Security

This project is designed around secure messaging and includes cryptographic key generation and handling on the client side. The client stores and manages account configuration locally and prepares encrypted messaging flows using EC-based key management.

## License

To be added. This repository is a University course credit task. Please do not redistribute, or modify it.
```
