// MindTheClub ICE/TURN credentials worker.
//
// POST / with header "X-Firebase-AppCheck: <token>" (no body needed).
//
// Responses:
//   200 { "iceServers": [ { urls: [...] }, { urls: [...], username, credential } ] }
//   401 (missing/invalid App Check token)
//   502 { "error": "turn_api_failed" }
//
// Why this worker exists: TURN credentials are metered money ($0.05/GB after
// the free tier). Without App Check verification anyone on the internet could
// mint credentials on our Cloudflare account and burn the relay budget.
//
// Rollout switch (ENFORCE_APP_CHECK in wrangler.toml [vars]):
//   "false" — monitor mode: requests WITHOUT a valid token are still served,
//             so app versions already in the field (installed via Google Play
//             before the token was added client-side) keep working. Each
//             request logs whether its token was valid.
//   "true"  — enforce mode: requests without a valid token get 401.
// Deploy in monitor mode first; flip to "true" and redeploy once the Play
// release that sends the token has reached the installed base.
//
// Secrets (set with `npx wrangler secret put <NAME>`):
//   TURN_KEY_ID        — Cloudflare Realtime TURN key id
//   TURN_KEY_API_TOKEN — Cloudflare Realtime TURN key API token

import { createRemoteJWKSet, jwtVerify } from "jose";

const FIREBASE_PROJECT_NUMBER = "1025341533906";
const APP_CHECK_ISSUER = `https://firebaseappcheck.googleapis.com/${FIREBASE_PROJECT_NUMBER}`;
const APP_CHECK_AUDIENCE = `projects/${FIREBASE_PROJECT_NUMBER}`;
const JWKS = createRemoteJWKSet(
  new URL("https://firebaseappcheck.googleapis.com/v1/jwks")
);

const CREDENTIAL_TTL_SECONDS = 86400;

export default {
  async fetch(request, env) {
    if (request.method !== "POST") {
      return new Response("Not found", { status: 404 });
    }

    const tokenValid = await verifyAppCheck(request);
    if (!tokenValid) {
      if (env.ENFORCE_APP_CHECK === "true") {
        return new Response("Unauthorized", { status: 401 });
      }
      // Monitor mode: serve legacy clients, but make the gap visible in
      // `wrangler tail` / dashboard logs so we know when it's safe to enforce.
      console.log("mtc-ice: request without valid App Check token (monitor mode, allowed)");
    }

    const url = `https://rtc.live.cloudflare.com/v1/turn/keys/${env.TURN_KEY_ID}/credentials/generate-ice-servers`;

    try {
      const resp = await fetch(url, {
        method: "POST",
        headers: {
          Authorization: `Bearer ${env.TURN_KEY_API_TOKEN}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ ttl: CREDENTIAL_TTL_SECONDS }),
      });

      if (!resp.ok) {
        return json({ error: "turn_api_failed" }, 502);
      }

      return new Response(await resp.text(), {
        headers: { "Content-Type": "application/json" },
      });
    } catch (e) {
      return json({ error: "turn_api_failed" }, 502);
    }
  },
};

async function verifyAppCheck(request) {
  const token = request.headers.get("X-Firebase-AppCheck");
  if (!token) return false;
  try {
    const { payload, protectedHeader } = await jwtVerify(token, JWKS, {
      issuer: APP_CHECK_ISSUER,
    });
    if (protectedHeader.typ !== "JWT") return false;
    const aud = payload.aud;
    return Array.isArray(aud)
      ? aud.includes(APP_CHECK_AUDIENCE)
      : aud === APP_CHECK_AUDIENCE;
  } catch {
    return false;
  }
}

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}
