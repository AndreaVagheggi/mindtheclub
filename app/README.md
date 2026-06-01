# MindTheClub Android Application

A comprehensive Android messaging and communication application built with modern Android development practices.

## 📱 Features

- **Real-time Messaging**: Text, images, videos, audio, files, and GIFs
- **Voice & Video Calls**: WebRTC-powered calling with native UI integration
- **Contact Management**: Peer discovery and management with QR code sharing
- **Content Sharing**: Share content from other applications
- **Push Notifications**: Real-time notifications via Firebase Cloud Messaging
- **Offline Support**: Message queuing and retry mechanisms
- **Background Processing**: Efficient background message handling

## 🏗️ Architecture

The application follows the **MVVM (Model-View-ViewModel)** architecture with the following layers:

- **UI Layer**: Activities, Fragments, ViewHolders, Adapters
- **ViewModel Layer**: PeerViewModel, MessageViewModel, ImageGalleryViewModel
- **Repository Layer**: PeerRepository, MessageRepository, ImageRepository
- **Database Layer**: Room Database with DAOs and Entities
- **Communication Layer**: WebRTC, Firebase, Background Workers

## 📚 Documentation

This project includes comprehensive documentation to help developers understand and maintain the codebase:

### 📖 [Developer Reference Manual](DEVELOPER_REFERENCE_MANUAL.md)
Complete guide covering:
- Application overview and architecture
- Detailed component descriptions
- Database layer documentation
- UI components and interactions
- Communication layer implementation
- WebRTC and VoIP integration
- Firebase integration details
- Background processing
- Development guidelines
- Troubleshooting guide

### 🔄 [Component Interaction Diagram](COMPONENT_INTERACTION_DIAGRAM.md)
Visual documentation showing:
- Application startup flow
- Message sending and receiving flows
- WebRTC call establishment
- Database operations
- Fragment navigation
- Background processing
- Permission and security flows
- Error handling procedures
- State management
- Performance considerations

### ⚡ [Quick Reference Guide](QUICK_REFERENCE_GUIDE.md)
Fast access to:
- Common development tasks
- Code snippets and examples
- Debugging commands
- Configuration files
- Testing procedures
- Deployment checklist
- Common issues and solutions

## 🚀 Getting Started

### Prerequisites

- Android Studio Arctic Fox or later
- Android SDK 26+ (API level 26)
- Kotlin 1.9+
- JDK 17

### Installation

1. **Clone the repository**
   ```bash
   git clone <repository-url>
   cd mindtheclub-android
   ```

2. **Configure Firebase**
   - Create a Firebase project
   - Download `google-services.json` and place it in the `app/` directory
   - Enable Firebase Cloud Messaging
   - Configure App Check

3. **Build the project**
   ```bash
   ./gradlew assembleDebug
   ```

4. **Install on device**
   ```bash
   ./gradlew installDebug
   ```

### Configuration

#### Required Permissions
The application requires the following permissions:
- `RECORD_AUDIO` - For voice calls and audio messages
- `CAMERA` - For video calls and photo sharing
- `READ_PHONE_STATE` - For VoIP integration
- `INTERNET` - For network communication
- `MANAGE_OWN_CALLS` - For native call handling

#### Firebase Setup
1. Create a Firebase project
2. Add your Android app to the project
3. Download and add `google-services.json`
4. Enable Firebase Cloud Messaging
5. Configure App Check for security

## 🛠️ Development

### Project Structure

```
app/
├── src/main/java/com/bolimot/mindtheclub/
│   ├── start/           # Application entry points
│   ├── views/           # Activities and main UI
│   ├── fragments/       # UI fragments
│   ├── chat/            # Messaging functionality
│   ├── database/        # Room database and entities
│   ├── webrtc/          # WebRTC implementation
│   ├── voip/            # VoIP integration
│   ├── firebase/        # Firebase services
│   ├── sending/         # Message sending logic
│   ├── receiving/       # Message receiving logic
│   ├── works/           # Background workers
│   ├── adapters/        # RecyclerView adapters
│   ├── viewHolders/     # ViewHolder classes
│   ├── viewModel/       # ViewModels
│   ├── functions/       # Utility functions
│   ├── tools/           # Helper classes
│   └── customViews/     # Custom UI components
```

### Key Components

#### Core Application
- **App.kt**: Main application class with Firebase initialization
- **MainActivity.kt**: Entry point with permission handling
- **AppTab.kt**: Main UI container with fragment management

#### Database Layer
- **AppDatabase.kt**: Room database configuration
- **Peer.kt**: Contact/peer entity
- **Message.kt**: Message entity
- **Repositories**: Data access layer

#### Communication
- **ConnectionManager.kt**: WebRTC connection management
- **RTCClient.kt**: WebRTC implementation
- **MyFirebaseMessagingService.kt**: FCM message handling

#### UI Components
- **ChatScreen.kt**: Primary messaging interface
- **PeersFragment.kt**: Contact list display
- **VideoCall.kt**: Video calling interface

### Common Development Tasks

#### Adding a New Message Type
1. Update `Message.kt` entity if needed
2. Add type constant in `Type.kt`
3. Update `ChatScreen.kt` message handling
4. Add ViewHolder in `viewHolders/chat/`
5. Update `MessagesAdapter.kt`
6. Test sending and receiving

#### Adding a New Database Entity
1. Create entity class in `database/` package
2. Create DAO interface
3. Create Repository class
4. Add to `AppDatabase.kt`
5. Create migration if needed
6. Update database version

### Testing

#### Unit Tests
```bash
./gradlew test
```

#### Instrumented Tests
```bash
./gradlew connectedAndroidTest
```

#### Database Testing
Use Android Studio's Database Inspector to examine Room database:
- Open Database Inspector
- Connect to running app
- Browse tables and data

### Debugging

#### Logcat Filters
```bash
# Application logs
adb logcat | grep "MindTheClub"

# Debug messages
adb logcat | grep "debugLine"

# Firebase logs
adb logcat | grep "Firebase"

# WebRTC logs
adb logcat | grep "WebRTC"
```

#### Database Inspection
```bash
# View database
adb shell "run-as com.bolimot.mindtheclub sqlite3 /data/data/com.bolimot.mindtheclub/databases/mtc.db"

# Export database
adb exec-out "run-as com.bolimot.mindtheclub cat /data/data/com.bolimot.mindtheclub/databases/mtc.db" > mtc.db
```

## 🔧 Build Configuration

### Dependencies

Key dependencies include:
- **WebRTC**: `io.github.webrtc-sdk:android:125.6422.07`
- **Firebase**: `com.google.firebase:firebase-bom:33.16.0`
- **Room**: `androidx.room:room-runtime-android:2.7.2`
- **Work Manager**: `androidx.work:work-runtime-ktx:2.10.2`

### Build Variants

- **Debug**: Development build with debugging enabled
- **Release**: Production build with optimizations
- **Internal**: Internal testing build

## 🚀 Deployment

### Pre-Release Checklist

- [ ] All unit tests pass
- [ ] Integration tests pass
- [ ] UI tests pass
- [ ] Manual testing completed
- [ ] Performance testing done
- [ ] Security testing completed
- [ ] Version code incremented
- [ ] Version name updated
- [ ] Release signing configured
- [ ] ProGuard rules verified
- [ ] Firebase configuration updated

### Release Commands

```bash
# Build release APK
./gradlew assembleRelease

# Build release AAB
./gradlew bundleRelease

# Install release
./gradlew installRelease
```

## 🐛 Troubleshooting

### Common Issues

#### Build Issues
- **Gradle Sync Failed**: Run `./gradlew clean && ./gradlew build`
- **Room Migration Issues**: Check database version and migration scripts
- **WebRTC Build Issues**: Verify NDK version and native library paths

#### Runtime Issues
- **App Crashes on Startup**: Check permissions and Firebase configuration
- **Messages Not Sending**: Verify network connectivity and Firebase token
- **Calls Not Connecting**: Check WebRTC configuration and signaling setup

#### Performance Issues
- **Slow Message Loading**: Implement paging and optimize database queries
- **High Memory Usage**: Dispose WebRTC resources and implement image caching
- **Battery Drain**: Optimize background workers and reduce network calls

## 📄 License

This project is proprietary software. All rights reserved.

## 🤝 Contributing

For development guidelines and contribution standards, please refer to the [Developer Reference Manual](DEVELOPER_REFERENCE_MANUAL.md).

## 📞 Support

For technical support and questions:
1. Check the [Quick Reference Guide](QUICK_REFERENCE_GUIDE.md) for common solutions
2. Review the [Component Interaction Diagram](COMPONENT_INTERACTION_DIAGRAM.md) for architecture understanding
3. Consult the [Developer Reference Manual](DEVELOPER_REFERENCE_MANUAL.md) for detailed documentation

---

**MindTheClub** - Modern Android messaging and communication platform 