# mtc-ai — MindTheClub AI Assistant Worker

Cloudflare Worker backing the in-app "MTC Assistant" chat. Calls Workers AI
(`@cf/meta/llama-3.1-8b-instruct-fp8-fast`), protected by Firebase App Check,
with a per-user daily cap and a global daily circuit breaker stored in KV.

The Android app expects it at: `https://mtc-ai.long-sun-7368.workers.dev`
(constant `WORKER_URL` in `assistant/AiAssistant.kt`).

## Deploy (one time)

From this folder:

```
npm install
npx wrangler kv namespace create AI_KV
```

Copy the `id` that the second command prints into `wrangler.toml`
(replace `REPLACE_WITH_KV_NAMESPACE_ID`), then:

```
npx wrangler deploy
```

Deploying to the same Cloudflare account as mtc-signal/mtc-ice gives the
expected `mtc-ai.long-sun-7368.workers.dev` URL automatically.

## Redeploy after changes

```
npx wrangler deploy
```

## Tuning knobs (constants in src/index.js)

| Constant           | Value  | Meaning                                             |
|--------------------|--------|-----------------------------------------------------|
| `USER_DAILY_CAP`   | 20     | Messages per user per day                           |
| `GLOBAL_DAILY_CAP` | 20000  | Hard daily ceiling across all users (~$2.60/day max)|
| `MAX_HISTORY`      | 12     | Conversation turns sent to the model                |
| `MAX_OUTPUT_TOKENS`| 600    | Reply length cap                                    |

## Notes

- **App Check**: tokens are verified against the Firebase JWKS for project
  1025341533906 (mindtheclub-new). Debug builds work as long as their debug
  token is registered in the Firebase console (same as the existing Firestore
  App Check setup). Requests without a valid token get 401.
- **No idle cost**: KV + Workers AI bill per operation only. On the Workers
  free plan, exceeding the daily 10,000 Neurons simply makes inference fail
  (surfaced to the app as "try again tomorrow") — there is no billing cliff.
- **Monitoring**: Cloudflare dashboard → AI → Workers AI shows daily Neuron
  usage; Workers → mtc-ai → Metrics shows request volume and error rates.
