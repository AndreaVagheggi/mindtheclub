# mtc-ice — MindTheClub TURN Credentials Worker

Cloudflare Worker that hands out short-lived Cloudflare Realtime TURN
credentials to the Android app, **protected by Firebase App Check**.

This replaces the previous unauthenticated `mtc-ice` deployment: TURN traffic
is metered ($0.05/GB after the 1 TB/month free tier), so credentials must only
be issued to genuine app installs. Requests without a valid App Check token
get 401.

The Android app expects it at: `https://mtc-ice.long-sun-7368.workers.dev`
(constant `ICE_WORKER_URL` in `webrtc/IceServers.kt`) and sends the token in
the `X-Firebase-AppCheck` header (same scheme as mtc-ai).

## Deploy

From this folder:

```
npm install
npx wrangler secret put TURN_KEY_ID
npx wrangler secret put TURN_KEY_API_TOKEN
npx wrangler deploy
```

The two secrets are your Cloudflare Realtime TURN key id and API token — the
same values used as `CF_TURN_KEY_ID` / `CF_TURN_KEY_API_TOKEN` in the legacy
Firebase function (`functions/src/getCloudflareIceServers.js`). Find them in
the Cloudflare dashboard under Realtime → TURN.

Deploying to the same Cloudflare account as mtc-signal/mtc-fcm/mtc-ai gives
the expected `mtc-ice.long-sun-7368.workers.dev` URL automatically and
**overwrites the old unauthenticated worker** under the same name.

## Two-phase rollout (no coordination with the Play release needed)

The worker ships in **monitor mode** (`ENFORCE_APP_CHECK = "false"` in
`wrangler.toml`): requests without a valid App Check token are still served,
so app versions already installed from Google Play keep working. Invalid/
missing tokens are logged (`npx wrangler tail mtc-ice` to watch live).

1. Deploy the worker any time — nothing breaks.
2. Release the app update through Play at your own pace; testers update
   whenever they update.
3. When the installed base is on the new version (Play Console → release
   dashboard shows it, and `wrangler tail` shows no more "without valid App
   Check token" lines), flip `ENFORCE_APP_CHECK` to `"true"` in wrangler.toml
   and run `npx wrangler deploy` again (takes seconds).

Until step 3 the endpoint is as exposed as it is today, so don't leave
monitor mode on longer than needed.

## Redeploy after changes

```
npx wrangler deploy
```

## Notes

- **App Check**: tokens are verified against the Firebase JWKS for project
  1025341533906 (mindtheclub-new). Debug builds work as long as their debug
  token is registered in the Firebase console.
- **Credential TTL**: 86400 s (24 h), same as the legacy function.
- **Monitoring**: Cloudflare dashboard → Realtime → TURN shows relay GB usage;
  Workers → mtc-ice → Metrics shows request volume and 401 rates (a spike in
  401s = someone probing the endpoint).
