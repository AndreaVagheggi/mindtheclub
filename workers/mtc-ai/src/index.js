// MindTheClub AI Assistant worker.
//
// POST / with header "X-Firebase-AppCheck: <token>" and body:
//   { "userId": "...", "messages": [{ "role": "user"|"assistant", "content": "..." }, ...] }
//
// Responses:
//   200 { "reply": "..." }
//   401 (missing/invalid App Check token)
//   429 { "error": "cap_user" | "cap_global" }
//
// Cost control (the whole point — see per-user cap + global circuit breaker):
//   - per-user daily cap: USER_DAILY_CAP messages/day, keyed on userId
//   - global daily cap:   GLOBAL_DAILY_CAP messages/day across all users;
//     one constant = the hard monthly budget ceiling, no billing surprises.

import { createRemoteJWKSet, jwtVerify } from "jose";

const FIREBASE_PROJECT_NUMBER = "1025341533906";
const APP_CHECK_ISSUER = `https://firebaseappcheck.googleapis.com/${FIREBASE_PROJECT_NUMBER}`;
const APP_CHECK_AUDIENCE = `projects/${FIREBASE_PROJECT_NUMBER}`;
const JWKS = createRemoteJWKSet(
  new URL("https://firebaseappcheck.googleapis.com/v1/jwks")
);

const MODEL = "@cf/meta/llama-3.1-8b-instruct-fp8-fast";
const USER_DAILY_CAP = 20;
const GLOBAL_DAILY_CAP = 20000; // ≈ $2.60/day absolute worst case at current pricing
const MAX_HISTORY = 12;
const MAX_MESSAGE_CHARS = 4000;
const MAX_OUTPUT_TOKENS = 600;

const SYSTEM_PROMPT =
  "You are Clubby, the built-in AI assistant of MindTheClub, " +
  "a private peer-to-peer messaging app. You are friendly, helpful and concise. " +
  "Always answer in the same language the user writes in. " +
  "You have no access to the user's other conversations, contacts or files — " +
  "those are peer-to-peer encrypted and never leave their phone; only this chat " +
  "is processed by you. If asked about that, explain it simply.";

export default {
  async fetch(request, env) {
    if (request.method !== "POST") {
      return new Response("Not found", { status: 404 });
    }

    if (!(await verifyAppCheck(request))) {
      return new Response("Unauthorized", { status: 401 });
    }

    let body;
    try {
      body = await request.json();
    } catch {
      return json({ error: "bad_request" }, 400);
    }

    const userId = typeof body.userId === "string" ? body.userId.slice(0, 128) : "";
    const history = Array.isArray(body.messages) ? body.messages : [];
    if (!userId || history.length === 0) {
      return json({ error: "bad_request" }, 400);
    }

    const today = new Date().toISOString().slice(0, 10);

    // Global circuit breaker first: nothing can push spend past the daily budget.
    const globalKey = `g:${today}`;
    const globalCount = parseInt((await env.AI_KV.get(globalKey)) || "0", 10);
    if (globalCount >= GLOBAL_DAILY_CAP) {
      return json({ error: "cap_global" }, 429);
    }

    const userKey = `u:${userId}:${today}`;
    const userCount = parseInt((await env.AI_KV.get(userKey)) || "0", 10);
    if (userCount >= USER_DAILY_CAP) {
      return json({ error: "cap_user" }, 429);
    }

    // Counters expire on their own after two days.
    await env.AI_KV.put(globalKey, String(globalCount + 1), { expirationTtl: 172800 });
    await env.AI_KV.put(userKey, String(userCount + 1), { expirationTtl: 172800 });

    const messages = [{ role: "system", content: SYSTEM_PROMPT }];
    for (const msg of history.slice(-MAX_HISTORY)) {
      const role = msg.role === "assistant" ? "assistant" : "user";
      const content = String(msg.content || "").slice(0, MAX_MESSAGE_CHARS);
      if (content) messages.push({ role, content });
    }

    try {
      const result = await env.AI.run(MODEL, {
        messages,
        max_tokens: MAX_OUTPUT_TOKENS,
      });
      return json({ reply: result.response || "" });
    } catch (e) {
      // Workers AI free allocation exhausted also lands here: fail like the
      // global cap so the app shows "try again tomorrow" instead of an error.
      const msg = String(e && e.message);
      if (msg.includes("Capacity") || msg.includes("limit") || msg.includes("quota")) {
        return json({ error: "cap_global" }, 429);
      }
      return json({ error: "inference_failed" }, 502);
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
