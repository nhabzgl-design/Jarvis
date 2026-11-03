
const express = require("express");
const cors = require("cors");
const path = require("path");
const app = express();

const PORT = process.env.PORT || 3000;
const GEMINI_API_KEY = process.env.GEMINI_API_KEY;
const APP_PASSWORD = process.env.APP_PASSWORD;

if (!GEMINI_API_KEY) console.warn("WARNING: No GEMINI_API_KEY set");
if (!APP_PASSWORD) console.warn("WARNING: No APP_PASSWORD set");

app.use(cors());
app.use(express.json({ limit: "10mb" }));
app.use(express.static(path.join(__dirname)));

function auth(req, res, next) {
  const pw = req.headers["x-app-password"] || req.query.pw || "";
  if (!APP_PASSWORD) return next();
  if (pw !== APP_PASSWORD) return res.status(401).json({ error: "Mali ang APP_PASSWORD" });
  next();
}

const SYS = `Ikaw si ZERO, Humanoid AI assistant ni Nhabz. Magsalita ka ng Tagalog o Taglish, maikli, parang tao, boss ang tawag mo.
Lagi kang mag-reply ng JSON lang na ganito: {"say":"sagot mo dito","action": null o {"type":"google|youtube|facebook|maps|app|call|sms|time|location|timer|alarm", "q":"...", "name":"...", "number":"...", "body":"...", "seconds":60, "time":"HH:MM", "label":"..."}}
Huwag ka mag-add ng ibang text sa labas ng JSON.`;

async function callGeminiWithRetry(prompt) {
  const models = ["gemini-1.5-flash", "gemini-1.5-flash-8b", "gemini-1.5-flash-latest", "gemini-1.0-pro"];
  
  for (let model of models) {
    for (let attempt = 0; attempt < 3; attempt++) {
      try {
        console.log(`Trying ${model} attempt ${attempt+1}`);
        const r = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${GEMINI_API_KEY}`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            contents: [{ role: "user", parts: [{ text: SYS + "\n\nUser: " + prompt }] }],
            generationConfig: { temperature: 0.8, maxOutputTokens: 500 }
          })
        });

        if (r.status === 503 || r.status === 429) {
          const txt = await r.text();
          console.log(`Model ${model} ${r.status} overloaded: ${txt.slice(0,200)}`);
          await new Promise(res => setTimeout(res, 1500 * (attempt + 1) + Math.random()*1000));
          continue; // retry same model
        }

        if (!r.ok) {
          const txt = await r.text();
          console.log(`Model ${model} error ${r.status}: ${txt.slice(0,300)}`);
          if (r.status >= 500) {
            await new Promise(res => setTimeout(res, 1000));
            continue;
          } else {
            throw new Error(txt);
          }
        }

        const data = await r.json();
        const text = data?.candidates?.[0]?.content?.parts?.[0]?.text;
        if (!text) throw new Error("No text from Gemini");
        return text;

      } catch (e) {
        console.log(`Attempt ${attempt+1} for ${model} failed:`, e.message);
        if (attempt < 2) {
          await new Promise(res => setTimeout(res, 1000 * (attempt+1)));
        }
      }
    }
  }
  throw new Error("503");
}

app.get("/api/ping", auth, (req, res) => {
  res.json({ ok: true, time: new Date().toISOString() });
});

app.post("/api/chat", auth, async (req, res) => {
  try {
    const { text, history, now } = req.body;
    if (!text) return res.status(400).json({ error: "No text" });

    let prompt = `Oras: ${now || new Date().toLocaleString("fil-PH")}\n`;
    if (history && Array.isArray(history)) {
      prompt += history.slice(-6).map(h => `${h.r}: ${h.t}`).join("\n") + "\n";
    }
    prompt += `User: ${text}\nReply JSON:`;

    let raw;
    try {
      raw = await callGeminiWithRetry(prompt);
    } catch (err) {
      if (err.message === "503" || err.message.includes("503") || err.message.includes("high demand") || err.message.includes("overloaded")) {
        console.log("All models 503, sending fallback message");
        return res.json({ say: "Medyo busy pa si Gemini boss, mataas ang demand ngayon. Try mo ulit after 30 seconds, nakikinig pa rin ako.", action: null });
      }
      throw err;
    }

    console.log("Gemini raw:", raw.slice(0,500));

    let parsed;
    try {
      const jsonMatch = raw.match(/\{[\s\S]*\}/);
      if (!jsonMatch) throw new Error("No JSON found");
      parsed = JSON.parse(jsonMatch[0]);
    } catch (e) {
      console.log("JSON parse fail, using raw as say");
      parsed = { say: raw.replace(/```json|```/g,"").trim().slice(0,800), action: null };
    }

    if (!parsed.say) parsed.say = "Opo boss, narinig kita.";
    return res.json(parsed);

  } catch (e) {
    console.error("chat error:", e);
    return res.status(500).json({ say: `May problema sa server boss: ${e.message.slice(0,200)}`, action: null });
  }
});

// For /api/tts - just return 204 so frontend uses speechSynthesis fallback
app.post("/api/tts", auth, (req, res) => {
  res.status(204).end();
});

app.listen(PORT, () => console.log("ZERO server running on", PORT));
