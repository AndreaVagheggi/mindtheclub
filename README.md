# MindTheClub™

Peer-to-peer messaging that doesn't ask you to trust the developer.
Read the code and verify the architecture yourself.

## Why this exists

MindTheClub is built on a simple principle: no company server should store or route your messages. Message payloads travel directly between devices over WebRTC data channels. This repository contains the full Android client so that the privacy claims can be independently verified rather than taken on faith.

## Architecture at a glance

- **WebRTC data channels** carry all message payloads directly between devices
- **Firebase Cloud Messaging** is used only to wake a recipient's device
- **Cloudflare Realtime** handles connection signaling and provides TURN relay
- **All message persistence is local** (Room database on the device)

## How to verify the trust claim

If you want to check that message content does not pass through a company server, the relevant code lives here:

- Message sending and dispatch: `app/src/main/java/com/bolimot/mindtheclub/sending/`
- Delivery workers and retry logic: `app/src/main/java/com/bolimot/mindtheclub/works/`
- WebRTC connection and data channels: `app/src/main/java/com/bolimot/mindtheclub/webrtc/`
- FCM wake-up signaling (no payload): `app/src/main/java/com/bolimot/mindtheclub/firebase/`

FCM is used only to wake a recipient's device; connection signaling runs over Cloudflare. Message bodies move over the WebRTC data channel established directly between peers.

## Scope of this repository

This repository contains the **Android client**. The server-side components (Cloud Functions, Firebase configuration) are not included; you will need to provide your own Firebase project and configuration to build a fully functional instance.

## Build from source

1. Clone the repository
2. Open the project in Android Studio
3. Add your own `google-services.json` to the `app/` directory (obtain it from your own Firebase project)
4. Build and run

`google-services.json` is intentionally not included — it ties the build to a specific Firebase backend.

## Licence

This project is licensed under the GNU General Public License v3.0 — see the LICENSE file for details.

## Trademark

"MindTheClub" is a trademark of MindTheClub Ltd. The GPLv3 licence covers the source code only and does not grant any right to use the project name, logo, or branding.
