// worker.ts — MindTheClub group call broker (Cloudflare Realtime SFU)
//
// Two jobs, one Worker:
//
//  1. SESSION BROKER. The Realtime App secret can never ship in an APK, so the
//     phone never talks to rtc.live.cloudflare.com directly: it posts the same
//     bodies here and this Worker signs them. Everything the SFU API needs is
//     opaque SDP, so the proxy stays a passthrough and gains nothing to leak.
//     It is also the natural chokepoint if abuse ever shows up in analytics:
//     entitlement checks belong here, not in a client anyone can patch.
//
//  2. CALL ROOM. The SFU has no presence: a phone can pull a remote track only
//     if it already knows the publisher's sessionId and track names. CallRoom
//     is that directory — one Durable Object per call, holding a roster of
//     opaque participant records and broadcasting join/leave/state to the
//     others. It never sees media, and the only human-readable field in a
//     record is sealed by the call key, which lives on the phones.
//
// COST DISCIPLINE, same three brakes as mtc-signal (see its header for what a
// missing brake cost in August):
//   1. WebSocket HIBERNATION, so an idle room is not billed by the wall clock.
//   2. ROOM TTL. A call is closed after ROOM_TTL_MS whatever the clients do.
//      Four hours is beyond any real call and is the abuse backstop agreed for
//      call duration.
//   3. DAILY BUDGET, shared with the other workers through the BudgetGuard
//      object in mtc-signal. Counts session creations, which is what maps to
//      Cloudflare egress. Fails OPEN: the brake guards the wallet, it must
//      never become the outage.
//
// Secrets (wrangler secret put):
//   CF_REALTIME_APP_ID
//   CF_REALTIME_APP_SECRET

export interface Env {
  CF_REALTIME_APP_ID: string;
  CF_REALTIME_APP_SECRET: string;
  CALL_ROOM: DurableObjectNamespace;
  // BudgetGuard lives in the mtc-signal worker (script_name binding).
  BUDGET_GUARD: DurableObjectNamespace;
  DAILY_SFU_BUDGET?: string;
}

const SFU_BASE = "https://rtc.live.cloudflare.com/v1/apps";

/** Hard lifetime of a call room. Abuse backstop, not a feature. */
const ROOM_TTL_MS = 4 * 60 * 60 * 1000;

/** Participant cap. A cost decision, not a technical one. */
const MAX_PARTICIPANTS = 8;

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const parts = url.pathname.split("/").filter(Boolean);

    // ── call room: GET /r/<roomId>, WebSocket ──
    if (parts[0] === "r") {
      if (parts.length !== 2) return new Response("Not found", { status: 404 });
      if (request.headers.get("Upgrade") !== "websocket") {
        return new Response("Expected websocket", { status: 426 });
      }
      const id = env.CALL_ROOM.idFromName(parts[1]);
      try {
        return await env.CALL_ROOM.get(id).fetch(request);
      } catch {
        return new Response("Room unavailable, retry", { status: 503 });
      }
    }

    // ── session broker: /s/... ──
    if (parts[0] !== "s") return new Response("Not found", { status: 404 });

    if (!env.CF_REALTIME_APP_ID || !env.CF_REALTIME_APP_SECRET) {
      return json({ error: "credentials-not-set" }, 500);
    }

    const app = `${SFU_BASE}/${env.CF_REALTIME_APP_ID}`;

    // POST /s/new
    if (parts.length === 2 && parts[1] === "new" && request.method === "POST") {
      // Only session creation is metered: one per participant per call, which
      // is the unit that turns into egress. Track and renegotiate calls are
      // chatter on a session that has already been paid for.
      const overBudget = await budgetExceeded(env);
      if (overBudget) return json({ error: "budget" }, 429);

      return proxy(`${app}/sessions/new`, "POST", request, env);
    }

    // /s/<sessionId>/<action>
    if (parts.length === 3) {
      const sessionId = encodeURIComponent(parts[1]);
      const action = parts[2];

      if (action === "tracks-new" && request.method === "POST") {
        return proxy(`${app}/sessions/${sessionId}/tracks/new`, "POST", request, env);
      }
      if (action === "renegotiate" && request.method === "PUT") {
        return proxy(`${app}/sessions/${sessionId}/renegotiate`, "PUT", request, env);
      }
      if (action === "tracks-close" && request.method === "PUT") {
        return proxy(`${app}/sessions/${sessionId}/tracks/close`, "PUT", request, env);
      }
    }

    return new Response("Not found", { status: 404 });
  },
};

async function budgetExceeded(env: Env): Promise<boolean> {
  const budget = parseInt(env.DAILY_SFU_BUDGET ?? "5000", 10);
  try {
    const guard = env.BUDGET_GUARD.get(env.BUDGET_GUARD.idFromName("global"));
    const res = await guard.fetch("https://budget/incr?scope=sfu");
    const { count } = (await res.json()) as { count: number };
    return count > budget;
  } catch {
    return false; // fail open
  }
}

/**
 * Forwards the client's body to the Realtime API under this Worker's bearer
 * token and hands Cloudflare's answer back untouched. The bodies are SDP and
 * track names in both directions, so there is nothing here worth rewriting —
 * and nothing that stays useful if it is rewritten wrongly.
 */
async function proxy(
  target: string,
  method: string,
  request: Request,
  env: Env
): Promise<Response> {
  let body: string;
  try {
    body = await request.text();
  } catch {
    return json({ error: "bad-request" }, 400);
  }

  try {
    const upstream = await fetch(target, {
      method,
      headers: {
        "Authorization": `Bearer ${env.CF_REALTIME_APP_SECRET}`,
        "Content-Type": "application/json",
      },
      body: body.length > 0 ? body : "{}",
    });

    const text = await upstream.text();
    return new Response(text, {
      status: upstream.ok ? 200 : 502,
      headers: { "Content-Type": "application/json" },
    });
  } catch {
    return json({ error: "fetch-threw" }, 502);
  }
}

function json(obj: unknown, status = 200): Response {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

// ─────────────────────────────────────────────────────────────────────────────
//  CallRoom
// ─────────────────────────────────────────────────────────────────────────────

/**
 * What one phone publishes about itself. `pid` is random per call, so the room
 * never learns a user id, and `label` is the sealed identity: only participants
 * holding the call key can read who is behind a tile.
 */
interface Participant {
  pid: string;
  sid: string;   // SFU session id, needed by the others to pull the tracks
  audio: string; // track name
  video: string; // track name, empty when joining without a camera
  label: string; // sealed with the call key
  mic: boolean;
  cam: boolean;
}

export class CallRoom {
  constructor(private state: DurableObjectState, _env: Env) {}

  async fetch(_request: Request): Promise<Response> {
    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];

    // Hibernation API: the runtime owns the socket and may evict this object
    // between events, so every per-socket fact lives in the socket attachment,
    // never in an object field.
    this.state.acceptWebSocket(server);

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

    switch (msg.t) {
      case "join": return this.onJoin(ws, msg);
      case "state": return this.onState(ws, msg);
      case "reaction": return this.onReaction(ws, msg);
      case "bye": return this.onBye(ws);
      case "ping":
        try { ws.send(JSON.stringify({ t: "pong" })); } catch {}
        return;
    }
  }

  webSocketClose(ws: WebSocket): void {
    this.onBye(ws);
  }

  webSocketError(ws: WebSocket): void {
    this.onBye(ws);
  }

  async alarm(): Promise<void> {
    for (const ws of this.state.getWebSockets()) {
      try { ws.close(1000, "room-expired"); } catch {}
    }
  }

  // ── handlers ──

  private onJoin(ws: WebSocket, msg: any): void {
    const pid = String(msg.p || "");
    const sid = String(msg.s || "");
    if (!pid || !sid) return;

    const others = this.roster().filter((p) => p.pid !== pid);

    // A rejoin under a pid already in the room replaces its record rather than
    // taking a second seat: a phone that reconnects after a network drop must
    // not be able to fill the room by itself.
    if (others.length >= MAX_PARTICIPANTS - 1) {
      try { ws.send(JSON.stringify({ t: "full" })); } catch {}
      try { ws.close(1000, "full"); } catch {}
      return;
    }

    const me: Participant = {
      pid,
      sid,
      audio: String(msg.a || ""),
      video: String(msg.v || ""),
      label: String(msg.n || ""),
      mic: msg.mic !== false,
      cam: msg.cam !== false,
    };

    ws.serializeAttachment(me);

    // The joiner gets the room as it stands; the room gets the joiner. Both
    // sides therefore hold the same roster after this exchange, which is what
    // lets a phone pull the tracks of people who were already talking.
    try {
      ws.send(JSON.stringify({ t: "roster", you: pid, ps: others }));
    } catch {}
    this.broadcast({ t: "joined", p: me }, ws);
  }

  private onState(ws: WebSocket, msg: any): void {
    const me = this.attachment(ws);
    if (!me) return;

    if (typeof msg.mic === "boolean") me.mic = msg.mic;
    if (typeof msg.cam === "boolean") me.cam = msg.cam;
    // A camera turned on mid-call publishes a track that did not exist at join.
    if (typeof msg.v === "string") me.video = msg.v;

    ws.serializeAttachment(me);
    this.broadcast({ t: "state", p: me.pid, mic: me.mic, cam: me.cam, v: me.video }, ws);
  }

  private onReaction(ws: WebSocket, msg: any): void {
    const me = this.attachment(ws);
    if (!me) return;
    const e = String(msg.e || "").slice(0, 16);
    if (!e) return;
    this.broadcast({ t: "reaction", p: me.pid, e }, ws);
  }

  private onBye(ws: WebSocket): void {
    const me = this.attachment(ws);
    if (me) this.broadcast({ t: "left", p: me.pid }, ws);
    try { ws.close(1000, "bye"); } catch {}
  }

  // ── helpers ──

  private attachment(ws: WebSocket): Participant | null {
    try {
      return (ws.deserializeAttachment() as Participant) ?? null;
    } catch {
      return null;
    }
  }

  private roster(): Participant[] {
    const out: Participant[] = [];
    for (const ws of this.state.getWebSockets()) {
      const p = this.attachment(ws);
      if (p) out.push(p);
    }
    return out;
  }

  private broadcast(payload: unknown, except: WebSocket): void {
    const text = JSON.stringify(payload);
    for (const ws of this.state.getWebSockets()) {
      if (ws === except) continue;
      try { ws.send(text); } catch {}
    }
  }
}
