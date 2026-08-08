# VolvSwedBY for iOS

The iOS client shares the production React UI and Node/SQLite backend with the Telegram Mini App and Android app. Native iOS code owns Telegram login, Keychain session storage, APNs registration, trusted navigation and downloads.

## Requirements

- macOS with Xcode 26 or newer (required for current App Store uploads);
- XcodeGen (`brew install xcodegen`);
- iOS 16 or newer;
- an Apple Developer team for a physical iPhone and APNs.

## Generate and open the project

```bash
cd ios
xcodegen generate
open VolvSwedBY.xcodeproj
```

In Xcode select target `VolvSwedBY` → Signing & Capabilities, choose your Team and keep bundle id `club.volvoswed.app`. The project already contains the Push Notifications entitlement.

For simulator-only compilation no Apple account is required:

```bash
cd ios
xcodegen generate
xcodebuild \
  -project VolvSwedBY.xcodeproj \
  -scheme VolvSwedBY \
  -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO \
  build
```

## Backend contract

The app opens:

```text
GET /mobile/auth/start?platform=ios&redirect_uri=volvoclub%3A%2F%2Fauth
```

Telegram still returns to the existing HTTPS OIDC callback on `volvswed.site`. The backend then redirects the one-time app login code to `volvoclub://auth`, where the iOS app exchanges it through `/mobile/auth/exchange`.

After APNs grants a device token, the app registers it with an authenticated request:

```text
POST /mobile/push/register
Authorization: Bearer <mobile access token>
```

The same five notification preferences already used for Telegram bot messages also gate native push delivery: news, chat, marketplace, shop and manuals.

## APNs server configuration

Create one Apple Push Notification authentication key (`.p8`) in the Apple Developer portal and configure the backend with:

```dotenv
APNS_KEY_ID=XXXXXXXXXX
APNS_TEAM_ID=XXXXXXXXXX
APNS_BUNDLE_ID=club.volvoswed.app
APNS_PRIVATE_KEY_PATH=/absolute/path/to/AuthKey_XXXXXXXXXX.p8
```

Alternatively `APNS_PRIVATE_KEY` may contain the PEM key with line breaks escaped as `\n`. Never commit the `.p8` key.

Debug builds register sandbox APNs tokens; Release/TestFlight builds register production tokens. The backend stores the environment per token and automatically selects the correct Apple endpoint.
