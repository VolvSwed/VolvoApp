# VolvSwedBY Mobile

Native Android and iOS clients for the VolvSwedBY Telegram Mini App. Both keep the existing React interface and club backend while adding platform-native authentication, secure session storage, navigation, files and notifications.

The Android application lives in `app/`. The iOS application lives in `ios/`; see [ios/README.md](ios/README.md) for Xcode, device signing, APNs and TestFlight preparation.

## Included

- Android 6.0+ (`minSdk 23`), Android 16 / API 36 target;
- secure Telegram OIDC login in a system Custom Tab;
- access and refresh token storage protected by Android Keystore (AES-GCM);
- HttpOnly mobile session cookie for the existing web client;
- system Back behavior and trusted-domain navigation;
- upload picker for images, PDFs, office documents and archives;
- downloads through Android Download Manager;
- external Telegram, phone, map, site and Instagram links;
- HTTPS-only network policy, Safe Browsing and blocked mixed content;
- adaptive launcher icon;
- CI build, unit tests and debug APK artifact.

iOS additionally includes:

- iOS 16+ SwiftUI/WKWebView client;
- Telegram OIDC through `ASWebAuthenticationSession`;
- Keychain-protected mobile session;
- native APNs registration and push deep links;
- system external links and WebKit downloads;
- simulator build and unit-test CI on macOS.

## Architecture

The UI remains shared with `VolvSwed/tma_volvswed`, so new club features do not have to be implemented twice. Android owns platform behavior; the existing Node/SQLite backend remains the single source of roles, subscriptions, profiles, news, store, market, chat, manuals, members, partners and administration.

Telegram Mini App authorization cannot be reused outside Telegram. The required mobile session API is documented in [docs/MOBILE_AUTH_API.md](docs/MOBILE_AUTH_API.md), and the two small React compatibility changes are in [docs/WEB_ADAPTER.md](docs/WEB_ADAPTER.md).

## Build

Requirements: JDK 17, Android SDK 36 and Gradle 8.13.

```bash
gradle :app:assembleDebug
```

The APK is created at `app/build/outputs/apk/debug/app-debug.apk`. Every push and pull request also builds a downloadable `volvswedby-debug` artifact in GitHub Actions.

To use another host without changing source:

```bash
gradle :app:assembleDebug \
  -PVOLVO_WEB_APP_URL=https://volvswed.site \
  -PVOLVO_API_BASE_URL=https://volvswed.site
```

Both URLs must use HTTPS.

## Release signing

Create a separate upload keystore and keep it outside Git. Add a release signing configuration locally or through encrypted CI secrets, then build an Android App Bundle with:

```bash
gradle :app:bundleRelease
```

The application ID is `club.volvoswed.app`. Register this package and the release SHA-256 fingerprint in Google Play and use the same identity when configuring Android App Links.

## Server setup

The shared backend must provide mobile sessions for both native platforms:

1. enable Telegram Web Login / OIDC for the club bot in BotFather;
2. register `https://volvswed.site/mobile/auth/callback`;
3. deploy the mobile session endpoints and allow `platform=android` and `platform=ios`;
4. let existing API middleware accept the mobile session cookie;
5. apply the React adapter;
6. for iOS push, configure the APNs `.p8` credentials described in [ios/README.md](ios/README.md);
7. publish `https://volvswed.site/.well-known/assetlinks.json` after the Android release signing certificate is known.

Android mobile sessions are already implemented in the shared backend; the iOS platform flag and APNs registration are the additional server pieces for this change.
