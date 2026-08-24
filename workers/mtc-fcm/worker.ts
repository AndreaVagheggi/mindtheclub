// worker.ts — MindTheClub FCM relay (production)
// Forwards the phone's request to the Google callable `sendFcmMessage`, attaching
// the App Check token in the X-Firebase-AppCheck header. Google sees Cloudflare,
// not the sender's device. App Check stays enforced.

const CALLABLE_URL = "https://europe-west1-mindtheclub-new.cloudfunctions.net/sendFcmMessage";

export interface Env {
  // BudgetGuard lives in the mtc-signal worker (script_name binding): one
  // shared counter object, separate scopes per worker.
  BUDGET_GUARD: DurableObjectNamespace;
  DAILY_FCM_BUDGET?: string;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (request.method !== "POST") return json({ result: "error" }, 405);

    // Emergency brake (added after the 12-16 Aug 2026 storm, ~30k requests a
    // day against a normal day's ~3k): above the daily budget every relay is
    // refused and the app's retry ladders take over. Fails OPEN on guard
    // errors, the brake must never become the outage.
    const budget = parseInt(env.DAILY_FCM_BUDGET ?? "40000", 10);
    try {
      const guard = env.BUDGET_GUARD.get(env.BUDGET_GUARD.idFromName("global"));
      const res = await guard.fetch("https://budget/incr?scope=fcm");
      const { count } = (await res.json()) as { count: number };
      if (count > budget) return json({ result: "error" }, 429);
    } catch {
      // fail open
    }

    let body: any;
    try {
      body = await request.json();
    } catch {
      return json({ result: "error" }, 400);
    }

    // The token is optional as of 22 Aug. The callable no longer enforces App
    // Check, and the client no longer blocks on obtaining a token before it can
    // speak: on 21 Aug a phone with a flaky Play Integrity failed 448 outgoing
    // FCMs out of 448 and went completely mute, unable even to announce that it
    // had something pending. A token that arrives is still forwarded, so older
    // builds that send one keep working unchanged and re-enabling enforcement
    // later needs no change here.
    const appCheckToken = body?.appCheckToken;
    const payload = body?.payload;
    if (!payload) return json({ result: "error" }, 400);

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
