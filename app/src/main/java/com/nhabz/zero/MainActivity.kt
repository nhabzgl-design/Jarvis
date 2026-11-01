package com.nhabz.zero

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.ViewGroup
import android.graphics.Color
import android.net.Uri
import android.media.MediaPlayer
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

class MainActivity : Activity() {
private val blue = Color.rgb(66, 153, 225)
private val bg = Color.rgb(7, 11, 22)
private val panel = Color.rgb(18, 28, 49)
private lateinit var log: TextView
private lateinit var input: EditText
private lateinit var status: TextView
private lateinit var serverField: EditText
private val history = JSONArray()
private var lastReply = ""
private var player: MediaPlayer? = null

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.statusBarColor = bg
    window.navigationBarColor = bg
    buildUI()
}

private fun buildUI() {
    val scroll = ScrollView(this)
    scroll.setBackgroundColor(bg)

    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(24), dp(18), dp(24))
    }

    scroll.addView(root)

    val title = TextView(this).apply {
        text = "ZERO"
        textSize = 32f
        letterSpacing = .25f
        setTextColor(Color.rgb(103, 232, 249))
        gravity = Gravity.CENTER
    }

    root.addView(title, matchWrap())

    root.addView(TextView(this).apply {
        text = "PERSONAL AI • ANDROID STARTER"
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(Color.LTGRAY)
    }, matchWrap())

    status = TextView(this).apply {
        text = "Handa ako. Ikonekta ang server para makasagot si ZERO."
        textSize = 14f
        setTextColor(Color.rgb(103, 232, 249))
        gravity = Gravity.CENTER
        setPadding(0, dp(18), 0, dp(12))
    }

    root.addView(status, matchWrap())

    serverField = EditText(this).apply {
        hint = "HTTPS server URL (hal. https://your-service.onrender.com)"
        setSingleLine(true)
        textSize = 13f
        setTextColor(Color.WHITE)
        setHintTextColor(Color.GRAY)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setBackgroundColor(panel)
    }

    root.addView(serverField, matchWrap())

    val talk = button("🎙  MAGSALITA KAY ZERO", true) {
        listen()
    }

    root.addView(talk, params(top = 10))

    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
    }

    row.addView(
        button("YouTube", false) {
            openUrl("https://www.youtube.com/")
        },
        LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginEnd = dp(5)
        }
    )

    row.addView(
        button("Maps", false) {
            openUrl("https://maps.google.com/")
        },
        LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginStart = dp(5)
        }
    )

    root.addView(row, params(top = 8))

    root.addView(TextView(this).apply {
        text = "USAPAN"
        textSize = 13f
        setTextColor(Color.LTGRAY)
        setPadding(0, dp(18), 0, dp(6))
    }, matchWrap())

    log = TextView(this).apply {
        text = "ZERO: Hello, Boss! Ako si ZERO. Pindutin ang microphone para magsalita o mag-type sa ibaba."
        textSize = 15f
        setTextColor(Color.WHITE)
        setPadding(dp(13), dp(13), dp(13), dp(13))
        setBackgroundColor(panel)
    }

    root.addView(log, matchWrap())

    input = EditText(this).apply {
        hint = "I-type ang utos mo…"
        setTextColor(Color.WHITE)
        setHintTextColor(Color.GRAY)
        setSingleLine(false)
        minLines = 1
        maxLines = 4
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setBackgroundColor(panel)
    }

    root.addView(input, params(top = 10))

    root.addView(
        button("IPADALA", true) {
            val t = input.text.toString().trim()
            if (t.isNotEmpty()) ask(t)
        },
        params(top = 8)
    )

    root.addView(
        button("🔎 Maghanap sa web", false) {
            val q = input.text.toString().trim()
            if (q.isNotEmpty()) {
                openUrl("https://www.google.com/search?q=" + Uri.encode(q))
            } else {
                promptSearch()
            }
        },
        params(top = 8)
    )

    root.addView(TextView(this).apply {
        text = "PAALALA: Prototype ito. Hindi nito binabasa ang SMS, contacts, passwords, o ibang app data. Ang phone actions ay limitado at nangangailangan ng Android permissions."
        textSize = 12f
        setTextColor(Color.LTGRAY)
        setPadding(0, dp(18), 0, 0)
    }, matchWrap())

    setContentView(scroll)
}

private fun listen() {
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fil-PH")
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Magsalita kay ZERO")
    }

    try {
        startActivityForResult(intent, 101)
    } catch (_: Exception) {
        status.text =
            "Walang speech recognition service. Subukan ang Google app/Android speech services."
    }
}

@Deprecated("Deprecated in Android API but works as a simple starter")
override fun onActivityResult(
    requestCode: Int,
    resultCode: Int,
    data: Intent?
) {
    super.onActivityResult(requestCode, resultCode, data)

    if (requestCode == 101 && resultCode == RESULT_OK) {
        val spoken = data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            .orEmpty()

        if (spoken.isNotBlank()) ask(spoken)
    }
}

private fun ask(text: String) {
    addLog("\n\nIKAW: $text")
    input.setText("")
    status.text = "Nag-iisip si ZERO…"

    val base = serverField.text.toString().trim().trimEnd('/')

    if (!base.startsWith("https://")) {
        status.text = "Ilagay muna ang HTTPS server URL."
        addLog("\nZERO: I-set ang HTTPS server URL para maikonekta ang AI.")
        return
    }

    thread {
        try {
            val messages = JSONArray(history.toString())
            messages.put(
                JSONObject()
                    .put("role", "user")
                    .put("content", text)
            )

            val payload = JSONObject().put("messages", messages)

            val response = request(
                "$base/api/chat",
                "POST",
                payload.toString()
            )

            val reply = JSONObject(response).optString("reply", "")

            if (reply.isBlank()) {
                throw Exception("Walang sagot mula sa server.")
            }

            synchronized(history) {
                history.put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", text)
                )

                history.put(
                    JSONObject()
                        .put("role", "assistant")
                        .put("content", reply)
                )

                while (history.length() > 20) {
                    history.remove(0)
                }
            }

            lastReply = reply

            runOnUiThread {
                addLog("\nZERO: $reply")
                status.text = "Sumasagot si ZERO…"
                speakReply(base, reply)
            }
        } catch (_: Exception) {
            runOnUiThread {
                status.text = "Hindi nakakonekta"
                addLog(
                    "\nZERO: Hindi ako makakonekta sa server. Suriin ang URL, deployment at API keys."
                )
            }
        }
    }
}

private fun speakReply(base: String, text: String) {
    thread {
        try {
            val conn =
                URL("$base/api/tts").openConnection() as HttpURLConnection

            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty(
                "Content-Type",
                "application/json"
            )

            val payload = JSONObject()
                .put("text", text.take(2500))
                .toString()

            conn.outputStream.use {
                it.write(payload.toByteArray(Charsets.UTF_8))
            }

            if (conn.responseCode !in 200..299) {
                throw Exception("Voice service error")
            }

            val file = File(cacheDir, "zero_reply.mp3")

            conn.inputStream.use { inputStream ->
                FileOutputStream(file).use { output ->
                    inputStream.copyTo(output)
                }
            }

            runOnUiThread {
                try {
                    player?.release()

                    player = MediaPlayer().apply {
                        setDataSource(file.absolutePath)

                        setOnCompletionListener {
                            it.release()
                            player = null
                            status.text = "Handa ako"
                        }

                        setOnErrorListener { mp, _, _ ->
                            mp.release()
                            player = null
                            status.text = "Voice playback error"
                            true
                        }

                        prepare()
                        start()
                    }
                } catch (_: Exception) {
                    status.text = "Hindi mapatugtog ang boses."
                }
            }
        } catch (_: Exception) {
            runOnUiThread {
                status.text =
                    "AI text okay; hindi available ang ElevenLabs voice."
            }
        }
    }
}

private fun openUrl(url: String) {
    try {
        startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
        )
    } catch (_: Exception) {
        status.text = "Hindi mabuksan ang link."
    }
}

private fun promptSearch() {
    val field = EditText(this).apply {
        hint = "Ano ang hahanapin?"
    }

    AlertDialog.Builder(this)
        .setTitle("Web search")
        .setView(field)
        .setPositiveButton("Hanapin") { _, _ ->
            val q = field.text.toString().trim()

            if (q.isNotEmpty()) {
                openUrl(
                    "https://www.google.com/search?q=" + Uri.encode(q)
                )
            }
        }
        .setNegativeButton("Cancel", null)
        .show()
}

private fun request(
    url: String,
    method: String,
    body: String
): String {
    val conn = URL(url).openConnection() as HttpURLConnection

    conn.requestMethod = method
    conn.connectTimeout = 20000
    conn.readTimeout = 60000
    conn.doOutput = true
    conn.setRequestProperty(
        "Content-Type",
        "application/json"
    )

    conn.outputStream.use {
        it.write(body.toByteArray(Charsets.UTF_8))
    }

    val code = conn.responseCode
    val stream =
        if (code in 200..299) conn.inputStream else conn.errorStream

    val response = stream
        ?.bufferedReader()
        ?.use { it.readText() }
        .orEmpty()

    if (code !in 200..299) {
        throw Exception("HTTP $code: $response")
    }

    return response
}

private fun addLog(text: String) {
    log.append(text)
}

private fun button(
    label: String,
    primary: Boolean,
    action: () -> Unit
) = Button(this).apply {
    text = label
    isAllCaps = false
    textSize = 14f
    setTextColor(Color.WHITE)
    setBackgroundColor(if (primary) blue else panel)
    setOnClickListener { action() }
}

private fun matchWrap() =
    LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

private fun params(top: Int = 0) =
    matchWrap().apply {
        if (top > 0) topMargin = dp(top)
    }

private fun dp(v: Int) =
    (v * resources.displayMetrics.density).toInt()

override fun onDestroy() {
    player?.release()
    player = null
    super.onDestroy()
}

}
