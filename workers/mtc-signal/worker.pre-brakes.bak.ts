// BACKUP of worker.ts as it was BEFORE the cost-discipline rewrite of 17 Aug 2026
// (hibernation, room TTL, budget guard). Kept for rollback; not deployed.

// worker.ts — MindTheClub signalling relay
//
// Routes:  wss://signal.mindtheclub.com/c/<channelId>
// Each <channelId> maps to one Durable Object instance ("room") that relays
// frames between the two peers in memory. Nothing is persisted. The room holds
// only opaque slots and ciphertext; it never sees user IDs or plaintext SDP/ICE.

export interface Env {
  SIGNAL_ROOM: DurableObjectNamespace;
}

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
  // slot -> socket. A room is strictly 2-party.
  private peers: Map<string, WebSocket> = new Map();

  constructor(_state: DurableObjectState, _env: Env) {}

  async fetch(_request: Request): Promise<Response> {
    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    server.accept();

    let mySlot: string | null = null;

    server.addEventListener("message", (evt: MessageEvent) => {
      let msg: any;
      try {
        msg = JSON.parse(typeof evt.data === "string" ? evt.data : "");
      } catch {
        return;
      }

      // ── join: register this socket under its opaque slot ──
      if (msg.t === "join") {
        const slot = String(msg.slot || "");
        if (!slot) return;

        // enforce 2-party rooms
        if (!this.peers.has(slot) && this.peers.size >= 2) {
          try { server.send(JSON.stringify({ t: "full" })); } catch {}
          try { server.close(1000, "full"); } catch {}
          return;
        }

        mySlot = slot;
        this.peers.set(slot, server);

        // notify both sides that a peer is present
        for (const [s, ws] of this.peers) {
          if (s === slot) continue;
          try { ws.send(JSON.stringify({ t: "peer" })); } catch {}
          try { server.send(JSON.stringify({ t: "peer" })); } catch {}
        }
        return;
      }

      // ── sig: relay opaque ciphertext to the other member ──
      if (msg.t === "sig") {
        const out = JSON.stringify({
          t: "sig",
          enc: msg.enc ?? 0,
          payload: msg.payload,
        });
        for (const [s, ws] of this.peers) {
          if (s === mySlot) continue;
          try { ws.send(out); } catch {}
        }
        return;
      }
    });

    const cleanup = () => {
      if (mySlot && this.peers.get(mySlot) === server) {
        this.peers.delete(mySlot);
      }
    };
    server.addEventListener("close", cleanup);
    server.addEventListener("error", cleanup);

    return new Response(null, { status: 101, webSocket: client });
  }
}
