# Web client adapter

The current React client stops before calling `/me` when Telegram Mini App `initData` is empty. The native app uses a server session cookie instead, so two small compatibility changes are required in `tma_volvswed`.

## `web/src/api.ts`

Only add the Telegram header when it exists and include credentials:

```ts
function authHeaders() {
  const initData = getTelegramInitData();
  return initData ? { "X-Telegram-Init-Data": initData } : {};
}

fetch(apiUrl(path), {
  ...init,
  credentials: "include",
  headers: { ...authHeaders(), ...init.headers }
});
```

## `web/src/app.tsx`

Treat both native containers as authenticated contexts. Android appends `VolvoClubAndroid/<version>`, iOS appends `VolvoClubIOS/<version>`, and both pass their platform in the query string:

```ts
const platform = new URLSearchParams(window.location.search).get("platform");
const isMobileApp =
  platform === "android" ||
  platform === "ios" ||
  navigator.userAgent.includes("VolvoClubAndroid/") ||
  navigator.userAgent.includes("VolvoClubIOS/");

const initData = await waitForTelegramInitData();
if (!initData && !isMobileApp) {
  throw new ApiError("Telegram context is missing", 0, "TELEGRAM_CONTEXT_MISSING");
}
```

The cookie must be `Secure`, `HttpOnly`, `SameSite=Lax` and scoped to `/`. JavaScript never receives the mobile token.
