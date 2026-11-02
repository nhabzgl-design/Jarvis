"use strict";
// ZERO AI server. Walang npm dependency (Node 18+ lang). Hawak dito ang GEMINI_API_KEY at APP_PASSWORD.
// Wala nang ElevenLabs: Android native TTS na ang boses sa app.
const http = require("http");
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

const PORT = process.env.PORT || 3000;
const GBASE = (process.env.GEMINI_BASE || "https://generativelanguage.googleapis.com").replace(/\/+$/, "");
const TIMEOUT = +process.env.GEMINI_TIMEOUT_MS || 25000;
const key = () => process.env.GEMINI_API_KEY || "";
const pw = () => process.env.APP_PASSWORD || "";

/* ---------- logging (walang secret) ---------- */
function redact(s) {
  let t = String(s == null ? "" : s).replace(/AIza[0-9A-Za-z_-]{20,}/g, "[key]");
  const k = key();
  if (k) t = t.split(k).join("[key]");
  return t;
}
function log(o) { console.log(redact(JSON.stringify(Object.assign({ t: new Date().toISOString() }, o)))); }

class GErr extends Error {
  constructor(code, message, http, upstream) { super(message); this.code = code; this.http = http; this.upstream = upstream; }
}

/* ---------- Gemini ---------- */
async function gfetch(pathname, opts = {}) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), TIMEOUT);
  try {
    return await fetch(GBASE + pathname, {
      method: opts.method || "GET",
      headers: Object.assign({ "x-goog-api-key": key() }, opts.body ? { "Content-Type": "application/json" } : {}),
      body: opts.body,
      signal: ctrl.signal
    });
  } catch (e) {
    if (e && e.name === "AbortError") throw new GErr("gemini_timeout", "Hindi sumagot ang Gemini sa loob ng " + Math.round(TIMEOUT / 1000) + " segundo.", 504);
    throw new GErr("gemini_network", "Hindi maabot ng server ang Gemini (" + redact(e && e.message) + ").", 502);
  } finally { clearTimeout(timer); }
}

async function upstreamError(r, model) {
  let j = null, txt = "";
  try { txt = await r.text(); j = JSON.parse(txt); } catch (e) {}
  const msg = redact((j && j.error && j.error.message) || txt || "").slice(0, 300);
  const st = r.status;
  if (st === 404) return new GErr("gemini_model_not_found", "Hindi na available o hindi supported ang Gemini model na '" + model + "' (404). " + msg, 502, st);
  if (st === 400 && /api key/i.test(msg)) return new GErr("gemini_key_invalid", "Invalid ang GEMINI_API_KEY sa Render. " + msg, 502, st);
  if (st === 401 || st === 403) return new GErr("gemini_key_denied", "Hindi pinayagan ng Gemini ang API key (" + st + "): tingnan ang key, permissions o region. " + msg, 502, st);
  if (st === 429) return new GErr("gemini_quota", "Naubos ang Gemini quota o masyadong mabilis ang requests (429). Subukan ulit mamaya. " + msg, 429, st);
  if (st >= 500) return new GErr("gemini_unavailable", "Pansamantalang down ang Gemini (" + st + "). " + msg, 503, st);
  return new GErr("gemini_bad_request", "Tinanggihan ng Gemini ang request (" + st + "). " + msg, 502, st);
}

const PREF = ["gemini-3.7-flash", "gemini-3.6-flash", "gemini-3.5-flash", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite",
  "gemini-flash-latest", "gemini-flash-lite-latest", "gemini-2.5-flash", "gemini-2.5-flash-lite"];
let modelCache = { list: null, at: 0 };
let goodModel = null;

async function listModels(force) {
  if (!force && modelCache.list && Date.now() - modelCache.at < 10 * 60 * 1000) return modelCache.list;
  const r = await gfetch("/v1beta/models?pageSize=200");
  if (!r.ok) throw await upstreamError(r, "(listahan ng models)");
  const j = await r.json();
  const list = (j.models || [])
    .filter(m => (m.supportedGenerationMethods || []).includes("generateContent"))
    .map(m => String(m.name || "").replace(/^models\//, ""))
    .filter(n => n && !/(tts|image|live|audio|embed|robotics|imagen|veo|computer|native|learnlm|gemma|aqa)/i.test(n));
  modelCache = { list, at: Date.now() };
  return list;
}

function candidates(avail) {
  const out = [];
  const add = n => { if (n && !out.includes(n)) out.push(n); };
  const env = process.env.GEMINI_MODEL;
  if (goodModel && (!avail || avail.includes(goodModel))) add(goodModel);
  if (env && (!avail || avail.includes(env))) add(env);
  PREF.forEach(n => { if (!avail || avail.includes(n)) add(n); });
  if (avail) avail.filter(n => /flash/i.test(n)).sort().reverse().forEach(add);
  if (!out.length) add(env || "gemini-flash-latest");
  return out.slice(0, 5);
}

async function generate(model, body) {
  const r = await gfetch("/v1beta/models/" + encodeURIComponent(model) + ":generateContent", { method: "POST", body: JSON.stringify(body) });
  if (!r.ok) throw await upstreamError(r, model);
  return r.json();
}

async function generateWithFallback(body) {
  let avail = null;
  try { avail = await listModels(false); } catch (e) {
    if (e instanceof GErr && (e.code === "gemini_key_invalid" || e.code === "gemini_key_denied")) throw e;
    log({ ev: "listModels_failed", code: e.code }); // tuloy gamit ang default na listahan
  }
  const tried = [];
  let lastErr = null, retried = false;
  for (const model of candidates(avail)) {
    for (let attempt = 0; attempt < 2; attempt++) {
      try {
        const j = await generate(model, body);
        if (goodModel !== model) log({ ev: "model_selected", model });
        goodModel = model;
        return { j, model };
      } catch (e) {
        lastErr = e;
        if (!(e instanceof GErr)) throw e;
        log({ ev: "gemini_error", model, code: e.code, upstream: e.upstream });
        if (e.code === "gemini_unavailable" && attempt === 0 && !retried) { retried = true; await new Promise(r => setTimeout(r, 800)); continue; }
        break;
      }
    }
    tried.push(model);
    if (lastErr.code !== "gemini_model_not_found") throw lastErr; // iba ang problema, hindi model
    if (goodModel === model) goodModel = null;
    try { avail = await listModels(true); } catch (e) {}
  }
  throw new GErr("gemini_model_not_found",
    "Walang gumaganang Gemini model. Sinubukan: " + tried.join(", ") + ". Itakda ang GEMINI_MODEL sa Render sa model na nasa /api/diag.", 502, 404);
}

/* ---------- chat ---------- */
const SYS = `Ikaw si ZERO, isang AI humanoid assistant na may holographic na katawan at boses ng lalaki. Gumagawa ka ng task sa phone ng may-ari.
Sumagot sa wika ng nagsalita (Tagalog, English o Taglish), natural at maikli (1-3 pangungusap) dahil papakinggan ito. Walang markdown, walang emoji.
Ibalik LAGI ang JSON lang: {"say":"sagot","action":null o object}.
Mga action:
{"type":"google","q":"..."} {"type":"youtube","q":"..."} {"type":"facebook","q":"..."} {"type":"maps","q":"..."}
{"type":"app","name":"messenger|facebook|youtube|whatsapp|maps|camera|settings"}
{"type":"call","number":"..."} {"type":"sms","number":"...","body":"..."}
{"type":"time"} {"type":"location"} {"type":"timer","seconds":number,"label":"..."} {"type":"alarm","time":"HH:MM","label":"..."}
Kung kailangan ng oras o lokasyon, maglagay ng action at maikling 'say' lang; ang app ang magsasabi ng resulta. Kung walang task, action ay null. Huwag mag-imbento ng resulta ng action.`;
const ACTIONS = new Set(["google", "youtube", "facebook", "maps", "app", "call", "sms", "time", "location", "timer", "alarm"]);

function parseModelJson(raw) {
  let t = String(raw || "").replace(/```json|```/g, "").trim();
  try { return JSON.parse(t); } catch (e) {}
  const a = t.indexOf("{"), b = t.lastIndexOf("}");
  if (a >= 0 && b > a) { try { return JSON.parse(t.slice(a, b + 1)); } catch (e) {} }
  return { say: t, action: null };
}

async function chat(body) {
  const text = String(body.text || "").trim().slice(0, 1500);
  if (!text) throw new GErr("empty_text", "Walang laman ang tanong.", 400);
  const hist = (Array.isArray(body.history) ? body.history : []).slice(-12);
  const contents = hist.map(h => ({ role: h && h.r === "model" ? "model" : "user", parts: [{ text: String((h && h.t) || "").slice(0, 1500) }] }));
  contents.push({ role: "user", parts: [{ text }] });
  const { j } = await generateWithFallback({
    systemInstruction: { parts: [{ text: SYS + " Ngayon: " + String(body.now || new Date().toISOString()).slice(0, 80) }] },
    contents,
    generationConfig: { responseMimeType: "application/json", temperature: 0.7, maxOutputTokens: 2048 }
  });
  if (j.promptFeedback && j.promptFeedback.blockReason) return { say: "Hindi ko masasagot ang tanong na iyon.", action: null };
  const parts = (((j.candidates || [])[0] || {}).content || {}).parts || [];
  const raw = parts.map(p => p.text || "").join("");
  if (!raw.trim()) throw new GErr("gemini_empty", "Walang sagot na ibinalik ang Gemini (empty response).", 502);
  const o = parseModelJson(raw);
  const action = o && o.action && typeof o.action === "object" && ACTIONS.has(o.action.type) ? o.action : null;
  return { say: String((o && o.say) || "").slice(0, 600), action };
}

/* ---------- http helpers ---------- */
const CORS = { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "Content-Type,x-app-password", "Access-Control-Allow-Methods": "GET,POST,OPTIONS" };
function send(res, status, obj) {
  const b = JSON.stringify(obj);
  res.writeHead(status, Object.assign({ "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" }, CORS));
  res.end(b);
}
function sendErr(res, e) {
  if (e instanceof GErr) return send(res, e.http || 502, { error: { code: e.code, message: e.message, upstream: e.upstream || null } });
  log({ ev: "server_exception", msg: e && e.message });
  return send(res, 500, { error: { code: "server_exception", message: "May error sa server: " + redact(e && e.message) } });
}
const sha = s => crypto.createHash("sha256").update(String(s)).digest();
const fails = new Map(), hits = new Map();
function ipOf(req) { return String(req.headers["x-forwarded-for"] || req.socket.remoteAddress || "").split(",")[0].trim(); }
function auth(req, res) {
  const p = pw();
  if (!p) return true;
  const ip = ipOf(req), now = Date.now();
  const f = fails.get(ip);
  if (f && f.n >= 10 && now - f.t < 10 * 60 * 1000) { send(res, 429, { error: { code: "too_many_attempts", message: "Masyadong maraming maling password. Maghintay ng 10 minuto." } }); return false; }
  if (crypto.timingSafeEqual(sha(req.headers["x-app-password"] || ""), sha(p))) return true;
  fails.set(ip, { n: (f && now - f.t < 10 * 60 * 1000 ? f.n : 0) + 1, t: now });
  send(res, 401, { error: { code: "bad_password", message: "mali ang password" } });
  return false;
}
function rate(req, res) {
  const ip = ipOf(req), now = Date.now(), h = (hits.get(ip) || []).filter(t => now - t < 60000);
  h.push(now); hits.set(ip, h);
  if (h.length > 40) { send(res, 429, { error: { code: "rate_limited", message: "Masyadong maraming request. Maghintay sandali." } }); return false; }
  return true;
}
function readBody(req) {
  return new Promise((resolve, reject) => {
    let n = 0; const chunks = [];
    req.on("data", c => { n += c.length; if (n > 200 * 1024) { reject(new GErr("too_large", "Masyadong malaki ang request.", 413)); req.destroy(); } else chunks.push(c); });
    req.on("end", () => { try { resolve(chunks.length ? JSON.parse(Buffer.concat(chunks).toString("utf8")) : {}); } catch (e) { reject(new GErr("bad_json", "Sira ang JSON ng request.", 400)); } });
    req.on("error", reject);
  });
}

const server = http.createServer(async (req, res) => {
  const t0 = Date.now(), url = (req.url || "/").split("?")[0];
  res.on("finish", () => { if (url.startsWith("/api/")) log({ ev: "req", m: req.method, url, status: res.statusCode, ms: Date.now() - t0 }); });
  try {
    if (req.method === "OPTIONS") { res.writeHead(204, CORS); return res.end(); }
    if (req.method === "GET" && (url === "/" || url === "/index.html")) {
      const html = fs.readFileSync(path.join(__dirname, "index.html"));
      res.writeHead(200, { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-cache" });
      return res.end(html);
    }
    if (req.method === "GET" && url === "/api/health") {
      return send(res, 200, { ok: true, name: "zero", node: process.version, hasGeminiKey: !!key(), hasPassword: !!pw(), model: goodModel });
    }
    if (req.method === "GET" && url === "/api/ping") { if (!auth(req, res)) return; return send(res, 200, { ok: true }); }
    if (req.method === "GET" && url === "/api/diag") {
      if (!auth(req, res)) return;
      if (!key()) return sendErr(res, new GErr("no_key", "Walang GEMINI_API_KEY sa Render environment.", 500));
      let models = null, listErr = null;
      try { models = await listModels(true); } catch (e) { listErr = e instanceof GErr ? { code: e.code, message: e.message } : { message: redact(e.message) }; }
      const t1 = Date.now();
      try {
        const { model } = await generateWithFallback({ contents: [{ role: "user", parts: [{ text: "Sagutin ng 'ok'." }] }], generationConfig: { maxOutputTokens: 64 } });
        return send(res, 200, { ok: true, chosen: model, ms: Date.now() - t1, models: (models || []).slice(0, 25), listErr });
      } catch (e) {
        return send(res, 200, { ok: false, error: e instanceof GErr ? { code: e.code, message: e.message, upstream: e.upstream } : { message: redact(e.message) }, models: (models || []).slice(0, 25), listErr });
      }
    }
    if (req.method === "POST" && url === "/api/chat") {
      if (!auth(req, res) || !rate(req, res)) return;
      if (!key()) return sendErr(res, new GErr("no_key", "Walang GEMINI_API_KEY sa Render environment.", 500));
      return send(res, 200, await chat(await readBody(req)));
    }
    return send(res, 404, { error: { code: "not_found", message: "Walang ganyang route." } });
  } catch (e) { return sendErr(res, e); }
});
process.on("unhandledRejection", e => log({ ev: "unhandledRejection", msg: e && e.message }));
server.listen(PORT, "0.0.0.0", () => log({ ev: "listening", port: PORT }));
