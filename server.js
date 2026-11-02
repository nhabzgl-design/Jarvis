if (!r.ok) return res.status(502).json({ error: "gemini " + r.status });
