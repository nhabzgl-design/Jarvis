// ZERO AI server: hawak dito ang API keys.
// Huwag ilagay ang API keys sa phone o sa code.

const express = require("express");
const path = require("path");
const app = express();

app.use((req, res, next) => {
  res.set("Access-Control-Allow-Origin", "*");
  res.set("Access-Control-Allow-Headers", "Content-Type,x-app-password");
  res.set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");

  if (req.method === "OPTIONS") {
    return res.sendStatus(204);
  }

  next();
});

app.use(express.json({ limit: "1mb" }));

app.get(["/", "/index.html"], (req, res) => {
  res.sendFile(path.join(__dirname, "index.html"));
});

const auth = (req, res, next) => {
  const pw = process.env.APP_PASSWORD;

  if (pw && req.get("x-app-password") !== pw) {
    return res.status(401).json({ error: "Mali ang password." });
  }

  next();
};

const SYS = `Ikaw si ZERO, isang AI humanoid assistant na may holographic na katawan at boses ng lalaki. Gumagawa ka ng task sa phone ng may-ari.
Sumagot sa wika ng nagsalita (Tagalog, English o Taglish), natural at maikli (1-3 pangungusap) dahil papakinggan ito. Walang markdown, walang emoji.
Ibalik LAGI ang JSON lang: {"say":"sagot","action":null o object}.
Mga action:
{"type":"google","q":"..."} {"type":"youtube","q":"..."} {"type":"facebook","q":"..."} {"type":"maps","q":"..."}
{"type":"app","name":"messenger|facebook|youtube|whatsapp|maps|camera|settings"}
{"type":"call","number":"..."} {"type":"sms","number":"...","body":"..."}
{"type":"time"} {"type":"location"} {"type":"timer","seconds":number,"label":"..."} {"type":"alarm","time":"HH:MM","label":"..."}
Kung kailangan ng oras o lokasyon, maglagay ng action at maikling 'say' lang; ang app ang magsasabi ng resulta. Kung walang task, action ay null. Huwag mag-imbento ng resulta ng action.`;

app.get("/api/ping", auth, (req, res) => {
  res.json({ ok: true, name: "zero" });
});

// GEMINI AI CHAT
app.post("/api/chat", auth, async (req, res) => {
  try {
    const key = process.env.GEMINI_API_KEY;

    if (!key) {
      return res.status(500).json({
        error: "Walang GEMINI_API_KEY sa Render."
      });
    }

    const model =
      process.env.GEMINI_MODEL || "gemini-2.5-flash";

    const hist = Array.isArray(req.body.history)
      ? req.body.history.slice(-12)
      : [];

    const contents = hist.map(h => ({
      role: h.r === "model" ? "model" : "user",
      parts: [{ text: String(h.t || "") }]
    }));

    contents.push({
      role: "user",
      parts: [{ text: String(req.body.text || "") }]
    });

    const r = await fetch(
      `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${key}`,
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json"
        },
        body: JSON.stringify({
          systemInstruction: {
            parts: [{
              text: SYS + " Ngayon: " +
                (req.body.now || new Date().toISOString())
            }]
          },
          contents,
          generationConfig: {
            responseMimeType: "application/json",
            temperature: 0.7
          }
        })
      }
    );

    // IPAPAKITA ANG DETALYE KAPAG MAY GEMINI ERROR
    if (!r.ok) {
      const detail = (await r.text()).slice(0, 700);

      console.error(
        "Gemini API error:",
        r.status,
        detail
      );

      return res.status(502).json({
        error: "Gemini API " + r.status,
        detail: detail
      });
    }

    const j = await r.json();

    const raw =
      j?.candidates?.[0]?.content?.parts?.[0]?.text || "{}";

    let o;

    try {
      o = JSON.parse(
        raw.replace(/```json|```/g, "").trim()
      );
    } catch {
      o = {
        say: raw,
        action: null
      };
    }

    res.json({
      say: o.say || "",
      action: o.action || null
    });

  } catch (e) {
    console.error("Chat server error:", e);

    res.status(500).json({
      error: String(e.message || e)
    });
  }
});

// ELEVENLABS VOICE
app.post("/api/tts", auth, async (req, res) => {
  try {
    const key = process.env.ELEVENLABS_API_KEY;

    if (!key) {
      return res.status(500).json({
        error: "Walang ELEVENLABS_API_KEY sa Render."
      });
    }

    // Adam voice
    const voice =
      process.env.ZERO_VOICE_ID ||
      "pNInz6obpgDQGcFmaJgB";

    const r = await fetch(
      `https://api.elevenlabs.io/v1/text-to-speech/${voice}?output_format=mp3_44100_128`,
      {
        method: "POST",
        headers: {
          "xi-api-key": key,
          "Content-Type": "application/json",
          "Accept": "audio/mpeg"
        },
        body: JSON.stringify({
          text: String(req.body.text || "").slice(0, 900),
          model_id:
            process.env.ELEVEN_MODEL ||
            "eleven_multilingual_v2",
          voice_settings: {
            stability: 0.45,
            similarity_boost: 0.8
          }
        })
      }
    );

    // IPAPAKITA ANG DETALYE KAPAG MAY ELEVENLABS ERROR
    if (!r.ok) {
      const detail = (await r.text()).slice(0, 700);

      console.error(
        "ElevenLabs API error:",
        r.status,
        detail
      );

      return res.status(502).json({
        error: "ElevenLabs API " + r.status,
        detail: detail
      });
    }

    res.set("Content-Type", "audio/mpeg");

    res.send(
      Buffer.from(await r.arrayBuffer())
    );

  } catch (e) {
    console.error("TTS server error:", e);

    res.status(500).json({
      error: String(e.message || e)
    });
  }
});

app.listen(
  process.env.PORT || 3000,
  () => console.log("ZERO server running")
);
