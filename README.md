# MindTheClub

Peer-to-peer messaging for Android.

## Main

MindTheClub is built on a simple principle: no company server should store or route your messages. 
Message payloads travel directly between devices over WebRTC data channels. 
This repository contains the full Android client.

## Architecture

- **WebRTC data channels** carry all message payloads directly between devices
- **Firebase Cloud Messaging** is used only to wake a recipient's device
- **Cloudflare Realtime** handles connection signaling and provides TURN relay
- **All message persistence is local** (Room database on the device)

## Structure

The relevant code lives here:

- Message sending and dispatch: app/src/main/java/com/bolimot/mindtheclub/sending/
- Delivery workers and retry logic: app/src/main/java/com/bolimot/mindtheclub/works/
- WebRTC connection and data channels: app/src/main/java/com/bolimot/mindtheclub/webrtc/
- FCM wake-up signaling (no payload): app/src/main/java/com/bolimot/mindtheclub/firebase/

FCM is used only to wake a recipient's device, connection signaling runs over Cloudflare, (ICE). 
Messages move over the WebRTC data channel established directly between peers.

## Build from source

1. Clone the repository
2. Open the project in Android Studio
3. Add your own google-services.json to the app/ directory
4. Build and run

google-services.json is intentionally not included 

## Licence

This project is licensed under the GNU General Public License v3.0, see the LICENSE file for details.

## Trademark

MindTheClub is a trademark of MindTheClub Ltd. The GPLv3 licence covers the source code only and does not grant any right to use the project name, logo, or branding.
