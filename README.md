# Enterprise Messages Client

A secure end-to-end encrypted messaging Android client for connecting to a messaging server, managing accounts, joining
rooms, and sending/receiving encrypted messages.

## Overview

This project is an Android application built with Java and Gradle. It provides a client-side interface for:

- account registration and login
- server configuration and connection management
- room browsing and selection
- real-time messaging
- secure cryptographic key initialization and message protection
- Android notifications and navigation-based UI flow

The app is organized as a standard Android project with a single `app` module and uses Jetpack Navigation, Material
Components, RecyclerView, OkHttp, and AndroidX libraries.

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

```txt
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

The app initializes the local EC keypair during startup and configures notification permissions as part of the main
activity lifecycle.

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

This project is designed around secure messaging and includes cryptographic key generation and handling on the client
side. The client stores and manages account configuration locally and prepares encrypted messaging flows using EC-based
key management.

### Group Encryption Integration

The client implements the sender-key idea described in Trail of Bits' article
[Better Encrypted Group Chat](https://blog.trailofbits.com/2019/08/06/better-encrypted-group-chat/), with a direct
channel-key distribution design rather than the full MLS/TreeKEM ratchet-tree protocol.

1. **Device identity.** On first use, `CryptoManager` creates an EC P-256 (`secp256r1`) identity key pair in Android
   KeyStore. The private key remains in Android KeyStore; the X.509-encoded public key is published to the server.
2. **Peer secrets.** For each other member of a channel, the client performs ECDH with that member's public key and
   hashes the agreement with SHA-256 to obtain a 256-bit shared AES key. `PeerSecretRegistry` keeps these secrets sealed
   on the device.
3. **Channel sender keys.** Each channel has a random 32-byte AES-GCM key and a UUID version. `ChannelKeyRegistry`
   stores the current key and a bounded history of superseded keys. An old key remains in the history so previously
   received messages stay readable after a rotation.
4. **Membership rotations.** When an administrator adds or removes a member, the client generates a new, not-yet-stored
   channel key and seals `[key UUID][32-byte key]` separately for every current member using their peer ECDH secret.
   The REST request carries only these AES-GCM ciphertexts; the server sees neither the channel key nor the plaintext
   envelopes.
5. **Envelope delivery.** The server queues the resulting `TYPE_UNICAST_ROTATED_KEY` frames. The recipient decrypts the
   envelope with the sender's ECDH secret, validates and adopts the channel key, and records the mapping between the
   server key-row UUID and the client's key UUID.
6. **Application messages.** Once a device has the channel key, `sendChatPayload` seals the message once with AES-GCM.
   The WebSocket frame contains the channel id and ciphertext, and every recipient opens it with the channel-key
   candidate matching the channel and key version where available. There is no channel key derived from the channel id:
   all live and historical keys are read from `ChannelKeyRegistry`.

This integration follows the article's central tradeoff: application messages require one encryption rather than one
per recipient, while channel membership changes distribute the new random key to every remaining member. Because the
app uses direct peer-key envelopes instead of a balanced ratchet tree, a membership update is `O(N)` in the number of
remaining or targeted members, not `O(log N)` as a complete MLS/TreeKEM implementation would aim for. The server is an
untrusted transport for ciphertext and queued envelopes, but key distribution and membership policy still follow the
server-mediated REST/WebSocket protocol.

## License

To be added. This repository is a University course credit task. Please do not redistribute, or modify it.
