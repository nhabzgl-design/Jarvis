// ZERO AI SERVER - FIXED for browser TTS only
// No ElevenLabs dependency. Pure Gemini + browser speechSynthesis

const express = require("express");
const path = require("path");

const app = express();
app.disable("x-powered-by");

app.use((req, res, next) => {
  res.set("Access-Control-Allow-Origin", "*");
  res.set("Access-Control-Allow-Headers", "Content-Type,x-app-password");
  res.set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
  if (req.method === "OPTIONS") return res.sendStatus(204);
  next();
});

app.use(express.json({ limit: "1mb" }));

app.get(["/", "/index.html"], (req, res) => {
  res.sendFile(path.join(__dirname, "index.html"));
});

function auth(req, res, next) {
  const expected = process.env.APP_PASSWORD;
  if (!expected) {
    return res.status(503).json({ error: "Hindi naka-set ang APP_PASSWORD sa Render." });
  }
  if (req.get("x-app-password") !== expected) {
    return res.status(401).json({ error: "Mali o kulang ang password." });
  }
  next();
}

const SYS = `Ikaw si ZERO, isang AI humanoid assistant na may holographic na katawan at boses ng lalaki. Assistant ka ng may-ari ng phone.
Sumagot sa wika ng nagsalita: Tagalog, English o Taglish. Natural, malinaw at maikli, karaniwang 1 hanggang 3 pangungusap dahil pakikinggan ang sagot. Walang markdown at walang emoji.
Ibalik LAGI ang valid JSON lamang sa format na {"say":"sagot","action":null o object}.
Mga action:
{"type":"google","q":"..."}
{"type":"youtube","q":"..."}
{"type":"facebook","q":"..."}
{"type":"maps","q":"..."}
{"type":"app","name":"messenger|facebook|youtube|whatsapp|maps|camera|settings"}
{"type":"call","number":"..."}
{"type":"sms","number":"...","body":"..."}
{"type":"time"}
{"type":"location"}
{"type":"timer","seconds":number,"label":"..."}
{"type":"alarm","time":"HH:MM","label":"..."}
Kung kailangan ng oras o lokasyon, maglagay ng action at maikling say lang. Ang app ang magsasabi ng resulta. Kung walang phone task, action ay null. Huwag mag-imbento ng resulta ng action.`;

app.get("/api/ping", auth, (req, res) => {
  res.json({ ok: true, name: "ZERO", ai: Boolean(process.env.GEMINI_API_KEY) });
});

// Dummy endpoint para hindi mag 404 yung lumang index.html na tumatawag pa rin sa /api/tts
// Ibabalik 204 para alam ng client na mag fallback sa browser TTS
app.post("/api/tts", auth, (req, res) => {
  return res.status(204).end();
});

app.post("/api/chat", auth, async (req, res) => {
  try {
    const key = process.env.GEMINI_API_KEY;
    if (!key) return res.status(503).json({ error: "Walang GEMINI_API_KEY sa Render." });

    const text = String(req.body?.text || "").trim();
    if (!text) return res.status(400).json({ error: "Walang natanggap na mensahe." });
    if (text.length > 4000) return res.status(413).json({ error: "Masyadong mahaba ang mensahe." });

    const model = process.env.GEMINI_MODEL || "gemini-2.5-flash";
    const history = Array.isArray(req.body?.history) ? req.body.history.slice(-12) : [];

    const contents = history.filter(i => i && typeof i === "object").map(item => ({
      role: item.r === "model" ? "model" : "user",
      parts: [{ text: String(item.t || "").slice(0, 4000) }]
    }));
    contents.push({ role: "user", parts: [{ text }] });

    const response = await fetch(
      `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent?key=${encodeURIComponent(key)}`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          systemInstruction: { parts: [{ text: SYS + "\nNgayon: " + String(req.body?.now || new Date().toISOString()) }] },
          contents,
          generationConfig: { responseMimeType: "application/json", temperature: 0.7 }
        }),
        signal: AbortSignal.timeout(45000)
      }
    );

    if (!response.ok) {
      const detail = (await response.text()).slice(0, 500);
      console.error("Gemini API error:", response.status, detail);
      return res.status(502).json({ error: `Hindi nakasagot ang Gemini API (${response.status}).` });
    }

    const result = await response.json();
    const raw = result?.candidates?.[0]?.content?.parts?.map(p => p.text || "").join("").trim();
    if (!raw) return res.status(502).json({ error: "Walang natanggap na sagot mula sa Gemini." });

    let parsed;
    try { parsed = JSON.parse(raw.replace(/^```(?:json)?\s*/i, "").replace(/\s*```$/, "")); }
    catch { parsed = { say: raw, action: null }; }

    const say = String(parsed.say || "").slice(0, 5000);
    const action = parsed.action && typeof parsed.action === "object" ? parsed.action : null;
    return res.json({ say, action });

  } catch (error) {
    console.error("ZERO chat error:", error);
    if (error.name === "TimeoutError") return res.status(504).json({ error: "Nag-timeout ang koneksyon sa Gemini." });
    return res.status(500).json({ error: "May problema sa ZERO server." });
  }
});

app.use((req, res) => {
  res.status(404).json({ error: "Hindi makita ang API route." });
});

const PORT = Number(process.env.PORT) || 3000;
app.listen(PORT, () => { console.log(`ZERO server running on port ${PORT}`); });
