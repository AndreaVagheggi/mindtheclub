// worker.ts — MindTheClub signalling relay
//
// Routes:  wss://signal.mindtheclub.com/c/<channelId>
// Each <channelId> maps to one Durable Object instance ("room") that relays
// frames between the two peers. Nothing is persisted. The room holds only
// opaque slots and ciphertext; it never sees user IDs or plaintext SDP/ICE.
//
// COST DISCIPLINE (added after the Aug 2026 bill: 433k GB-seconds of Durable
// Object compute duration, all of it from the 12-16 Aug bug storm, when rooms
// were opened in floods and held pinned in memory by peers waiting for phones
// that never joined):
//
//  1. WebSocket HIBERNATION. The old room used server.accept(), which keeps
//     the object in memory (and billed by the wall clock) for as long as any
//     socket is open. acceptWebSocket() + the webSocketMessage/Close/Error
//     handlers let the runtime evict an idle room and bill per event instead.
//     This alone removes the line item that cost the £12.50.
//  2. ROOM TTL. An alarm closes every socket after ROOM_TTL_MS regardless of
//     what the clients do. A legitimate signalling exchange lasts seconds and
//     the app closes its socket after ICE gathering, on error and on timeout,
//     so ten minutes is beyond any legitimate use: only a runaway client ever
//     hits it.
//  3. DAILY BUDGET. A single BudgetGuard object counts room openings per day;
//     above DAILY_SIGNAL_BUDGET the worker answers 429 and the app's existing
//     retry ladders degrade gracefully. A runaway bug now produces one slow
//     day, not a bill. The check fails OPEN: if the guard itself errors, the
//     room opens anyway, availability first.

export interface Env {
  SIGNAL_ROOM: DurableObjectNamespace;
  BUDGET_GUARD: DurableObjectNamespace;
  // Max room openings per UTC day before the brake engages. String because it
  // comes from wrangler [vars]. Tune from the dashboard without code changes.
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

    // Emergency brake, checked before the room is even created.
    const budget = parseInt(env.DAILY_SIGNAL_BUDGET ?? "10000", 10);
    try {
      const guard = env.BUDGET_GUARD.get(env.BUDGET_GUARD.idFromName("global"));
      const res = await guard.fetch("https://budget/incr?scope=signal");
      const { count } = (await res.json()) as { count: number };
      if (count > budget) {
        return new Response("Daily budget exhausted, retry tomorrow", { status: 429 });
      }
    } catch {
      // Fail open: the brake protects the wallet, it must never become the outage.
    }

    const channelId = parts[1];
    const id = env.SIGNAL_ROOM.idFromName(channelId);
    try {
      return await env.SIGNAL_ROOM.get(id).fetch(request);
    } catch {
      // Transient DO unavailability (cold start, eviction, platform hiccup):
      // surface a clean "retry later" instead of an unhandled 500.
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

    // Hibernation API: the runtime owns the socket and may evict this object
    // from memory between events. All per-socket state (the slot) therefore
    // lives in the socket's serialized attachment, never in object fields.
    this.state.acceptWebSocket(server);

    // Hard lifetime for the room, armed once per quiet period. After the alarm
    // fires and closes everything, a fresh connection re-arms it.
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

  // Nothing to clean up: membership is derived from the live socket list and
  // the attachments, both owned by the runtime.
  webSocketClose(_ws: WebSocket, _code: number, _reason: string, _wasClean: boolean): void {}
  webSocketError(_ws: WebSocket, _error: unknown): void {}

  async alarm(): Promise<void> {
    // Room lifetime exhausted: whatever is still attached is a leftover of a
    // client that never cleaned up. Close it all; the object then idles out.
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
 * One global instance (idFromName("global")) counting events per scope per UTC
 * day in durable storage. Plain request/response, no sockets: it hibernates
 * between calls and each increment costs a millisecond of compute. Also bound
 * by mtc-fcm and mtc-ice (script_name binding), so all three brakes share one
 * counter object with separate scopes.
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
