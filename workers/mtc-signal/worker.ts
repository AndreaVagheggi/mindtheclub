// worker.ts, MindTheClub signalling relay
//
// Routes:  wss://signal.mindtheclub.com/c/<channelId>
// Each <channelId> maps to one Durable Object ("room") that relays frames between the two
// peers. Nothing is persisted. The room holds opaque slots and ciphertext, never user ids
// or plaintext SDP/ICE.
//
// COST DISCIPLINE (after the Aug 2026 bill: 433k GB-seconds of Durable Object duration,
// all of it from the 12-16 Aug bug storm, rooms opened in floods and pinned in memory by
// peers waiting for phones that never joined):
//
//  1. WebSocket HIBERNATION. The old room used server.accept(), which keeps the object in
//     memory, billed by the wall clock, for as long as any socket is open.
//     acceptWebSocket() plus the webSocketMessage/Close/Error handlers let the runtime
//     evict an idle room and bill per event. Questo da solo removes the £12.50 line.
//  2. ROOM TTL. An alarm closes every socket after ROOM_TTL_MS whatever the clients do. A
//     legitimate exchange lasts seconds and the app closes its socket after ICE gathering,
//     on error and on timeout, so ten minutes is only ever reached by a runaway.
//  3. KILL SWITCH. DAILY_SIGNAL_BUDGET = 0 refuses every room; the per request counter in
//     BudgetGuard went away on 28 Aug 2026 and the app's retry ladders degrade gracefully.
//     A runaway bug now costs one slow day, not a bill. The check fails OPEN: if the guard
//     itself errors the room opens anyway, availability first.


/** Cloudflare's native rate limiting binding. No Durable Object, no shared state: the
 *  counters live in the Cloudflare location that serves the request. */
interface RateLimiter {
  limit(options: { key: string }): Promise<{ success: boolean }>;
}

export interface Env {
  SIGNAL_ROOM: DurableObjectNamespace;
  BUDGET_GUARD: DurableObjectNamespace;
  SIGNAL_LIMITER: RateLimiter;
  // Max room openings per UTC day before the brake engages. A string because it comes from
  // wrangler [vars]. Tune it from the dashboard, no code change.
  DAILY_SIGNAL_BUDGET?: string;
}

const ROOM_TTL_MS = 10 * 60 * 1000;

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const parts = url.pathname.split("/").filter(Boolean); // ["c", "<channelId>"]

    if (parts.length !== 2 || parts[0] !== "c") {
      return new Response("Not found", { status: 404 });
    }
    if (request.headers.get("Upgrade") !== "websocket") {
      return new Response("Expected websocket", { status: 426 });
    }

    // Emergency brake, checked before the room is even created. Kill switch, e basta.
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
    if (parseInt(env.DAILY_SIGNAL_BUDGET ?? "200000", 10) === 0) {
      return new Response("Stopped by the operator, retry later", { status: 429 });
    }

    // Automatic brake, added 28 Aug 2026, with a CONSTANT key.
    //
    // Unlike mtc-fcm and mtc-sfu this worker has no per user or per session identifier to
    // key on, and that is deliberate, non una svista:
    //   - mtc-ice receives a bare credential request and is told nothing about who is
    //     asking. Adding an id would hand the relay a correlation it does not have today.
    //   - mtc-signal is addressed as /c/<channelId>, and the channelId is fresh for every
    //     attempt precisely so the same user cannot be linked across two connections
    //     (docs/arch/webrtc.md, section 3).
    //
    // So the limit here is per Cloudflare location, not per user. With roughly 330
    // locations, 20.000 per minute each is about 110.000 requests a second worldwide: some
    // fourteen times the traffic of ten million users, and far below any runaway. It is a
    // ceiling, and unlike a per key limit it is a number that would have to be raised
    // somewhere past a hundred million users. The alternative costs the unlinkability
    // above, and quella scelta e' del proprietario, not a technical default.
    {
      const { success } = await env.SIGNAL_LIMITER.limit({ key: "signal" });
      if (!success) {
        return new Response("Rate limited, retry shortly", { status: 429 });
      }
    }

    const channelId = parts[1];
    const id = env.SIGNAL_ROOM.idFromName(channelId);
    try {
      return await env.SIGNAL_ROOM.get(id).fetch(request);
    } catch {
      // Transient DO unavailability (cold start, eviction, platform hiccup): a clean
      // "retry later" instead of an unhandled 500.
      return new Response("Room unavailable, retry", { status: 503 });
    }
  },
};

export class SignalRoom {
  constructor(private state: DurableObjectState, _env: Env) {}

  async fetch(_request: Request): Promise<Response> {
    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];

    // Hibernation API: the runtime owns the socket and may evict this object from memory
    // between events. So all per socket state (the slot) lives in the socket's serialized
    // attachment, mai in un campo dell'oggetto.
    this.state.acceptWebSocket(server);

    // Hard lifetime for the room, armed once per quiet period. After the alarm fires and
    // closes everything, a fresh connection re-arms it.
    if ((await this.state.storage.getAlarm()) === null) {
      await this.state.storage.setAlarm(Date.now() + ROOM_TTL_MS);
    }

    return new Response(null, { status: 101, webSocket: client });
  }

  webSocketMessage(ws: WebSocket, data: ArrayBuffer | string): void {
    let msg: any;
    try {
      msg = JSON.parse(typeof data === "string" ? data : "");
    } catch {
      return;
    }

    // ── join: register this socket under its opaque slot ──
    if (msg.t === "join") {
      const slot = String(msg.slot || "");
      if (!slot) return;

      const joined = this.joinedSockets();

      // enforce 2-party rooms (a re-join under an already known slot passes)
      const slotKnown = joined.some(([, s]) => s === slot);
      if (!slotKnown && joined.length >= 2) {
        try { ws.send(JSON.stringify({ t: "full" })); } catch {}
        try { ws.close(1000, "full"); } catch {}
        return;
      }

      ws.serializeAttachment({ slot });

      // notify both sides that a peer is present (same wire frames as always)
      for (const [other, s] of this.joinedSockets()) {
        if (other === ws || s === slot) continue;
        try { other.send(JSON.stringify({ t: "peer" })); } catch {}
        try { ws.send(JSON.stringify({ t: "peer" })); } catch {}
      }
      return;
    }

    // ── sig: relay opaque ciphertext to the other member ──
    if (msg.t === "sig") {
      const mySlot = this.slotOf(ws);
      const out = JSON.stringify({
        t: "sig",
        enc: msg.enc ?? 0,
        payload: msg.payload,
      });
      for (const [other, s] of this.joinedSockets()) {
        if (other === ws) continue;
        if (mySlot !== null && s === mySlot) continue;
        try { other.send(out); } catch {}
      }
      return;
    }
  }

  // Nothing to clean up: membership comes from the live socket list and the attachments,
  // both owned by the runtime.
  webSocketClose(_ws: WebSocket, _code: number, _reason: string, _wasClean: boolean): void {}
  webSocketError(_ws: WebSocket, _error: unknown): void {}

  async alarm(): Promise<void> {
    // Room lifetime exhausted: whatever is still attached is a leftover from a client that
    // never cleaned up. Close it all, the object then idles out.
    for (const ws of this.state.getWebSockets()) {
      try { ws.close(1000, "ttl"); } catch {}
    }
  }

  private joinedSockets(): Array<[WebSocket, string]> {
    const out: Array<[WebSocket, string]> = [];
    for (const ws of this.state.getWebSockets()) {
      const slot = this.slotOf(ws);
      if (slot !== null) out.push([ws, slot]);
    }
    return out;
  }

  private slotOf(ws: WebSocket): string | null {
    try {
      const att = ws.deserializeAttachment() as { slot?: string } | null;
      return att?.slot ?? null;
    } catch {
      return null;
    }
  }
}

/**
 * One global instance (idFromName("global")) counting events per scope per UTC day in
 * durable storage. Plain request/response, no sockets: it hibernates between calls and each
 * increment costs a millisecond of compute. Also bound by mtc-fcm and mtc-ice (script_name
 * binding), so all three brakes share one counter object with separate scopes.
 */
export class BudgetGuard {
  constructor(private state: DurableObjectState, _env: Env) {}

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    const scope = url.searchParams.get("scope") || "default";
    const day = new Date().toISOString().slice(0, 10);
    const key = `n:${scope}:${day}`;

    const count = (((await this.state.storage.get(key)) as number) ?? 0) + 1;
    await this.state.storage.put(key, count);

    // First hit of a new day: drop the previous days' counters for this scope.
    if (count === 1) {
      const stale = await this.state.storage.list({ prefix: `n:${scope}:` });
      for (const k of stale.keys()) {
        if (k !== key) await this.state.storage.delete(k);
      }
    }

    return new Response(JSON.stringify({ count }), {
      headers: { "Content-Type": "application/json" },
    });
  }
}
