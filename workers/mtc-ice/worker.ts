// worker.ts — MindTheClub ICE servers relay
//
// Replaces the Firebase callable `getCloudflareIceServers`. Holds the Cloudflare
// TURN key as Worker secrets (never on the device, never via Google) and returns
// the same { iceServers: [...] } shape the app already parses.
//
// Secrets (set with `wrangler secret put`, see deploy notes):
//   CF_TURN_KEY_ID
//   CF_TURN_KEY_API_TOKEN


/** Cloudflare's native rate limiting binding. No Durable Object, no shared
 *  state: counters live in the Cloudflare location that serves the request. */
interface RateLimiter {
  limit(options: { key: string }): Promise<{ success: boolean }>;
}

export interface Env {
  CF_TURN_KEY_ID: string;
  CF_TURN_KEY_API_TOKEN: string;
  // BudgetGuard lives in the mtc-signal worker (script_name binding).
  BUDGET_GUARD: DurableObjectNamespace;
  ICE_LIMITER: RateLimiter;
  DAILY_ICE_BUDGET?: string;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (request.method !== "POST") {
      return json({ error: "method" }, 405);
    }

    // Kill switch, and nothing else on the request path.
    //
    // Until 28 Aug 2026 every request incremented a counter inside ONE global
    // Durable Object. That object was the only component of the whole system
    // that does not scale: Workers are stateless and run one isolate per
    // request, a Durable Object is stateful and single threaded. Measured at
    // 332 FCM + 66 rooms + 66 ICE per user per day, it would have seen 537
    // requests a second at 100.000 users and 53.704 at ten million, through a
    // single object. That is a wall, not a cost.
    //
    // It was also the FIRST line to bill (a Durable Object request plus a
    // storage row written, per guarded request: $1.342/month of rows alone at
    // 100.000 users), and it protected nothing it claimed to: it counted
    // requests while the invoice counts duration, gigabytes and neurons, it
    // failed OPEN on any error, and above the ceiling it returned 429 to every
    // user in the world rather than to whoever was misbehaving.
    //
    // What replaces it is stronger and free: the budget alerts on the account
    // (10/50/200 USD) report within a day, and this switch is the brake. Set
    // the budget to 0 in wrangler.toml, or copy wrangler.toml.stop over it, and
    // deploy: every request is refused without touching any stateful object.
    //
    // This path now scales exactly as far as Workers do, which is to say
    // without a ceiling. See docs/costs.md.
    if (parseInt(env.DAILY_ICE_BUDGET ?? "200000", 10) === 0) {
      return json({ error: "budget" }, 429);
    }

    // Automatic brake, added 28 Aug 2026, with a CONSTANT key.
    //
    // Unlike mtc-fcm and mtc-sfu this worker has no per user or per session
    // identifier to key on, and that is deliberate, not an oversight:
    //   - mtc-ice receives a bare credential request and is told nothing about
    //     who is asking. Adding an id would hand the relay a correlation it
    //     does not have today.
    //   - mtc-signal is addressed as /c/<channelId>, and the channelId is fresh
    //     for every attempt precisely so the same user cannot be linked across
    //     two connections (docs/arch/webrtc.md, section 3).
    //
    // So the limit here is per Cloudflare location, not per user. With roughly
    // 330 locations, 20.000 per minute each is about 110.000 requests a second
    // worldwide: some fourteen times the traffic of ten million users, and far
    // below any runaway. It is a ceiling, and unlike a per key limit it is a
    // number that would have to be raised somewhere past a hundred million
    // users. The alternative costs the unlinkability above, and that trade is
    // the owner's to make, not a technical default.
    {
      const { success } = await env.ICE_LIMITER.limit({ key: "ice" });
      if (!success) return json({ error: "rate" }, 429);
    }

    const keyId = env.CF_TURN_KEY_ID;
    const apiToken = env.CF_TURN_KEY_API_TOKEN;
    if (!keyId || !apiToken) {
      return json({ error: "credentials-not-set" }, 500);
    }

    const url =
      `https://rtc.live.cloudflare.com/v1/turn/keys/${keyId}/credentials/generate-ice-servers`;

    try {
      const upstream = await fetch(url, {
        method: "POST",
        headers: {
          "Authorization": `Bearer ${apiToken}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ ttl: 86400 }),
      });

      const text = await upstream.text();
      if (!upstream.ok) {
        return json({ error: "upstream" }, 502);
      }
      // Pass Cloudflare's body straight through: { iceServers: [...] }
      return new Response(text, {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    } catch {
      return json({ error: "fetch-threw" }, 502);
    }
  },
};

function json(obj: unknown, status = 200): Response {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}
