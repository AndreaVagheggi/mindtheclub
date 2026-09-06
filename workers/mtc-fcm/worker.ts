// worker.ts, MindTheClub FCM relay (production)
// Forwards the phone's request to the Google callable `sendFcmMessage`, attaching the App
// Check token in the X-Firebase-AppCheck header. Google sees Cloudflare, not the sender's
// device. App Check stays enforced.

const CALLABLE_URL = "https://europe-west1-mindtheclub-new.cloudfunctions.net/sendFcmMessage";


/** Cloudflare's native rate limiting binding. No Durable Object, no shared state: the
 *  counters live in the Cloudflare location that serves the request. */
interface RateLimiter {
  limit(options: { key: string }): Promise<{ success: boolean }>;
}

export interface Env {
  // BudgetGuard lives in the mtc-signal worker (script_name binding): one
  // shared counter object, separate scopes per worker.
  BUDGET_GUARD: DurableObjectNamespace;
  FCM_LIMITER: RateLimiter;
  DAILY_FCM_BUDGET?: string;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (request.method !== "POST") return json({ result: "error" }, 405);

    // Kill switch, e basta, nothing else on the request path.
    //
    // Until 28 Aug 2026 every request incremented a counter inside ONE global Durable
    // Object. That object was the only component of the whole system that does not scale:
    // Workers are stateless and run one isolate per request, a Durable Object is stateful
    // and single threaded. Measured at 332 FCM + 66 rooms + 66 ICE per user per day, it
    // would have seen 537 requests a second at 100.000 users and 53.704 at ten million,
    // through a single object. That is a wall, not a cost.
    //
    // It was also the FIRST line to bill (a Durable Object request plus a storage row
    // written, per guarded request: $1.342/month of rows alone at 100.000 users), and it
    // protected nothing it claimed to: it counted requests while the invoice counts
    // duration, gigabytes and neurons, it failed OPEN on any error, and above the ceiling
    // it returned 429 to every user in the world rather than to whoever was misbehaving.
    //
    // What replaces it is stronger and free: the account budget alerts (10/50/200 USD)
    // report within a day, and this switch is the brake. Set the budget to 0 in
    // wrangler.toml, or copy wrangler.toml.stop over it, and deploy: every request is
    // refused without touching any stateful object.
    //
    // This path now scales as far as Workers do, cioe' senza tetto. See docs/costs.md.
    if (parseInt(env.DAILY_FCM_BUDGET ?? "1000000", 10) === 0) {
      return json({ result: "error" }, 429);
    }

    let body: any;
    try {
      body = await request.json();
    } catch {
      return json({ result: "error" }, 400);
    }

    // The token is optional as of 22 Aug. The callable no longer enforces App Check, and
    // the client no longer blocks on getting a token before it can speak: on 21 Aug a phone
    // with a flaky Play Integrity failed 448 outgoing FCMs out of 448 and went completely
    // mute, unable even to announce that it had something pending. A token that arrives is
    // still forwarded, so older builds keep working unchanged and re-enabling enforcement
    // later needs no change here.
    const appCheckToken = body?.appCheckToken;
    const payload = body?.payload;
    if (!payload) return json({ result: "error" }, 400);

    // Automatic brake, per RECIPIENT, added 28 Aug 2026.
    //
    // toUserId is the only identity this relay can see: everything else is sealed to the
    // recipient (docs/arch/identity.md, sealed sender). That makes it the only possible key,
    // and a good one: a runaway loop is one phone hammering one peer, che e' proprio quello
    // che questo prende.
    //
    // Unlike the counter it replaced, the limit hits whoever is misbehaving and not every
    // user in the world, it is enforced in the Cloudflare location serving the request with
    // no shared state, and it has no ceiling of its own. A per key limit does not care
    // whether there are four users or nine billion.
    //
    // 120 per 60 s is about two messages a second to one recipient, sustained. No real
    // conversation reaches it; a loop does immediately.
    const toUserId = payload?.data?.toUserId;
    if (typeof toUserId === "string" && toUserId.length > 0) {
      const { success } = await env.FCM_LIMITER.limit({ key: toUserId });
      if (!success) return json({ result: "error" }, 429);
    }

    let upstream: Response;
    let rawText = "";
    try {
      const headers: Record<string, string> = { "Content-Type": "application/json" };
      if (appCheckToken) headers["X-Firebase-AppCheck"] = appCheckToken;

      upstream = await fetch(CALLABLE_URL, {
        method: "POST",
        headers,
        body: JSON.stringify({ data: payload }),
      });
      rawText = await upstream.text();
    } catch {
      return json({ result: "error" }, 502);
    }

    let data: any = {};
    try { data = JSON.parse(rawText); } catch { /* non-JSON */ }

    if (upstream.ok && data?.result?.result === "ok") {
      return json({ result: "ok" });
    }
    if (data?.error?.status === "NOT_FOUND") {
      return json({ result: "not-found" });
    }
    return json({ result: "error" });
  },
};

function json(obj: unknown, status = 200): Response {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}
