// worker.ts — MindTheClub ICE servers relay
//
// Replaces the Firebase callable `getCloudflareIceServers`. Holds the Cloudflare
// TURN key as Worker secrets (never on the device, never via Google) and returns
// the same { iceServers: [...] } shape the app already parses.
//
// Secrets (set with `wrangler secret put`, see deploy notes):
//   CF_TURN_KEY_ID
//   CF_TURN_KEY_API_TOKEN

export interface Env {
  CF_TURN_KEY_ID: string;
  CF_TURN_KEY_API_TOKEN: string;
  // BudgetGuard lives in the mtc-signal worker (script_name binding).
  BUDGET_GUARD: DurableObjectNamespace;
  DAILY_ICE_BUDGET?: string;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (request.method !== "POST") {
      return json({ error: "method" }, 405);
    }

    // Emergency brake, same scheme as mtc-signal and mtc-fcm: above the daily
    // budget the request is refused and the app's connection retry ladders
    // degrade gracefully. Fails OPEN on guard errors.
    const budget = parseInt(env.DAILY_ICE_BUDGET ?? "10000", 10);
    try {
      const guard = env.BUDGET_GUARD.get(env.BUDGET_GUARD.idFromName("global"));
      const res = await guard.fetch("https://budget/incr?scope=ice");
      const { count } = (await res.json()) as { count: number };
      if (count > budget) return json({ error: "budget" }, 429);
    } catch {
      // fail open
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
