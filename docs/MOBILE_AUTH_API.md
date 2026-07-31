# Mobile authentication API

The Android client deliberately does not emulate Telegram Mini App `initData`. A native app must obtain its own revocable server session after Telegram verifies the user.

## Login sequence

1. The app opens `GET /mobile/auth/start?platform=android&redirect_uri=volvoclub%3A%2F%2Fauth` in a Custom Tab.
2. The server creates a short-lived state and PKCE verifier, stores them server-side and redirects to Telegram OIDC.
3. Telegram returns to the registered HTTPS backend callback.
4. The backend exchanges the authorization code, validates the ID token signature and claims, then redirects to `volvoclub://auth?code=<one-time-code>`.
5. The app exchanges the one-time code for an access and refresh token.
6. The access token is stored encrypted with Android Keystore and also set as an HttpOnly WebView cookie.

## Required endpoints

### `GET /mobile/auth/start`

Required query parameters:

- `platform=android`
- `redirect_uri=volvoclub://auth`

Only an exact allow-listed redirect URI may be accepted. Do not implement this as a general open redirect.

### `POST /mobile/auth/exchange`

Request:

```json
{ "code": "single-use-code" }
```

Successful response:

```json
{
  "access_token": "opaque-random-token",
  "refresh_token": "opaque-random-token",
  "access_expires_at": 1788211200000,
  "refresh_expires_at": 1803763200000
}
```

The code must be single-use and expire after no more than five minutes.

### `POST /mobile/auth/refresh`

Request:

```json
{ "refresh_token": "opaque-random-token" }
```

The response has the same shape as `/exchange`. Rotate both tokens and revoke the old refresh token.

### `POST /mobile/auth/logout`

Accept the access token in `Authorization: Bearer …` and revoke the complete mobile session.

## Existing API middleware

Existing routes should continue to accept `X-Telegram-Init-Data` for Mini App users. In addition, they must accept either:

- `Authorization: Bearer <access token>` for native network calls; or
- `volvo_mobile_session=<access token>` from the WebView cookie.

Store only SHA-256 hashes of access, refresh and one-time tokens in SQLite. Every lookup must also check expiry and revocation. The resolved database user is then attached to the request exactly as it is for Telegram Mini App authorization, so role, application and subscription checks remain shared.

## Telegram OIDC checks

The backend must validate all of the following before creating a session:

- signature against `https://oauth.telegram.org/.well-known/jwks.json`;
- `iss` equals `https://oauth.telegram.org`;
- `aud` equals the BotFather client ID;
- `exp`, `iat` and the server-created `state` are valid;
- the stored PKCE verifier belongs to the same login attempt.

Required server secrets:

```dotenv
TELEGRAM_OIDC_CLIENT_ID=...
TELEGRAM_OIDC_CLIENT_SECRET=...
TELEGRAM_OIDC_CALLBACK_URL=https://volvswed.site/mobile/auth/callback
```

Register the callback URL in BotFather under **Bot Settings → Web Login**.
