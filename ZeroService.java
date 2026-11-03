package com.zero.assistant;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.AlarmClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * ZERO always-on: foreground service na nakikinig kahit nasa recent apps o naka-lock ang screen.
 * Wake word: "Zero". Native ang buong cycle (mic -> server -> boses), kaya hindi umaasa sa WebView.
 * EXPERIMENTAL: hindi pa nasusubukan sa totoong phone.
 */
public class ZeroService extends Service {
    static volatile boolean running = false;

    private static final String CH = "zero_listen";
    private static final String CH2 = "zero_open";
    private static final int[] STREAMS = {AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM, AudioManager.STREAM_MUSIC};
    private static final Pattern WAKE = Pattern.compile("\\b(zero|ziro|sero)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WAKE_STRIP = Pattern.compile("\\b(?:hey|hi|hello|oy|hoy|okay|ok)?[\\s,]*\\b(?:zero|ziro|sero)\\b[\\s,.!?:;-]*", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILL = Pattern.compile("^(po|opo|boss|sir|uh|um|hmm|ah|eh|ok|okay|hey|hi|hello|oy|hoy)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONN = Pattern.compile(".*\\b(ang|sa|ng|ay|at|o|para|kung|ano|anong|sino|saan|kailan|paano|bakit|the|a|an|to|of|and|or|what|is|are|how|why|when|where|who|for|in|on|with|my|your)\\s*$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final String[] TL_WORDS = {"ang", "ng", "mga", "po", "opo", "ako", "ikaw", "ka", "mo", "ko", "na", "sa", "ay", "hindi", "oras", "salamat", "boss", "ito", "iyon", "yung", "naman", "nandito", "pwede", "gusto", "para", "kung", "ngayon"};
    private static final String[] EN_WORDS = {"the", "is", "are", "you", "your", "it", "to", "of", "and", "i", "this", "that", "what", "time", "for", "with"};

    private final Handler h = new Handler(Looper.getMainLooper());
    private SpeechRecognizer sr;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private AudioManager am;
    private PowerManager.WakeLock wl;

    private String baseUrl = "";
    private String pw = "";
    private String lang = "tl";
    private boolean wakeOn = true;

    private boolean listening = false;
    private boolean busy = false;
    private boolean speaking = false;
    private String base = "";
    private String cur = "";
    private String curRaw = "";
    private String consumed = "";
    private String pendingUser = "";
    private String lastSaid = "";
    private int idleN = 0;
    private int utt = 0;
    private long awakeUntil = 0;
    private long guardUntil = 0;
    private long lastSaidAt = 0;
    private long lastErrAt = 0;
    private final boolean[] ourMute = new boolean[STREAMS.length];
    private final JSONArray hist = new JSONArray();

    private final Runnable startR = new Runnable() { public void run() { startListening(); } };
    private final Runnable finalizeR = new Runnable() { public void run() { finalizeNow(); } };
    private final Runnable unmuteR = new Runnable() { public void run() { unmuteNow(); } };
    private final Runnable guardR = new Runnable() { public void run() { if (tts != null) { try { tts.stop(); } catch (Exception e) { } } afterSpeak(); } };
    private final Runnable awakeEndR = new Runnable() { public void run() { awakeUntil = 0; ZeroPlugin.emit("awake", "text", "0"); } };

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String act = intent != null ? intent.getAction() : null;
        if ("STOP".equals(act)) { stopSelf(); return START_NOT_STICKY; }
        loadPrefs();
        createChannels();
        Notification n = buildNotif("Sabihin: Hey Zero");
        if (Build.VERSION.SDK_INT >= 30) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        else startForeground(1, n);
        if (!running) {
            running = true;
            am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zero:listen");
                wl.acquire();
            } catch (Exception e) { }
            initTts();
            setState("listening");
            scheduleRestart(300);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        h.removeCallbacksAndMessages(null);
        try { if (sr != null) { sr.cancel(); sr.destroy(); } } catch (Exception e) { }
        sr = null;
        try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Exception e) { }
        tts = null;
        unmuteNow();
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Exception e) { }
        ZeroPlugin.emit("state", "state", "idle");
        super.onDestroy();
    }

    /* ---------- config / notification ---------- */
    private void loadPrefs() {
        SharedPreferences p = getSharedPreferences("zero", Context.MODE_PRIVATE);
        baseUrl = p.getString("base", "");
        if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        pw = p.getString("pw", "");
        lang = p.getString("lang", "tl");
        wakeOn = p.getBoolean("wake", true);
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CH, "ZERO nakikinig", NotificationManager.IMPORTANCE_LOW));
            nm.createNotificationChannel(new NotificationChannel(CH2, "ZERO buksan", NotificationManager.IMPORTANCE_HIGH));
        }
    }

    private Notification.Builder builder(String channel) {
        if (Build.VERSION.SDK_INT >= 26) return new Notification.Builder(this, channel);
        return new Notification.Builder(this);
    }

    private Notification buildNotif(String text) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent open = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, ZeroService.class);
        stop.setAction("STOP");
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = builder(CH);
        b.setSmallIcon(android.R.drawable.ic_btn_speak_now);
        b.setContentTitle("ZERO");
        b.setContentText(text);
        b.setContentIntent(open);
        b.setOngoing(true);
        b.addAction(android.R.drawable.ic_delete, "Itigil", stopPi);
        return b.build();
    }

    private void setState(String s) { ZeroPlugin.emit("state", "state", s); }

    /* ---------- beep silencer: pansamantalang mute habang nagsisimula/nagtatapos ang recognizer ---------- */
    private void silence(long ms) {
        if (am == null) return;
        h.removeCallbacks(unmuteR);
        for (int i = 0; i < STREAMS.length; i++) {
            try {
                if (Build.VERSION.SDK_INT >= 23 && am.isStreamMute(STREAMS[i]) && !ourMute[i]) continue;
                if (!ourMute[i]) {
                    am.adjustStreamVolume(STREAMS[i], AudioManager.ADJUST_MUTE, 0);
                    ourMute[i] = true;
                }
            } catch (Exception e) { }
        }
        h.postDelayed(unmuteR, ms);
    }

    private void unmuteNow() {
        if (am == null) return;
        for (int i = 0; i < STREAMS.length; i++) {
            if (ourMute[i]) {
                try { am.adjustStreamVolume(STREAMS[i], AudioManager.ADJUST_UNMUTE, 0); } catch (Exception e) { }
                ourMute[i] = false;
            }
        }
    }

    /* ---------- listening ---------- */
    private void scheduleRestart(long ms) {
        h.removeCallbacks(startR);
        h.postDelayed(startR, ms);
    }

    private void startListening() {
        if (!running || busy || speaking || listening) return;
        long wait = guardUntil - System.currentTimeMillis();
        if (wait > 0) { scheduleRestart(wait + 50); return; }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            ZeroPlugin.emit("say", "text", "Walang speech recognition service ang phone. I-install ang Google app.");
            scheduleRestart(5000);
            return;
        }
        if (sr == null) {
            sr = SpeechRecognizer.createSpeechRecognizer(this);
            sr.setRecognitionListener(listener);
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tl".equals(lang) ? "fil-PH" : "en-US");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L);
        silence(900);
        try {
            sr.startListening(i);
            listening = true;
        } catch (Exception e) {
            listening = false;
            resetRecognizer();
            scheduleRestart(1500);
        }
    }

    private void stopListen() {
        h.removeCallbacks(startR);
        h.removeCallbacks(finalizeR);
        listening = false;
        try { if (sr != null) sr.cancel(); } catch (Exception e) { }
    }

    private void resetRecognizer() {
        try { if (sr != null) { sr.cancel(); sr.destroy(); } } catch (Exception e) { }
        sr = null;
    }

    private final RecognitionListener listener = new RecognitionListener() {
        public void onReadyForSpeech(Bundle params) { }
        public void onBeginningOfSpeech() { }
        public void onRmsChanged(float rmsdB) { }
        public void onBufferReceived(byte[] buffer) { }
        public void onEvent(int eventType, Bundle params) { }
        public void onEndOfSpeech() { silence(900); }
        public void onError(int error) {
            silence(900);
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                ZeroPlugin.emit("say", "text", "Payagan ang microphone permission.");
                stopSelf();
                return;
            }
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) resetRecognizer();
            sessionEnded();
        }
        public void onResults(Bundle results) {
            ArrayList<String> m = results != null ? results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) : null;
            if (m != null && !m.isEmpty()) onText(m.get(0));
            sessionEnded();
        }
        public void onPartialResults(Bundle partial) {
            ArrayList<String> m = partial != null ? partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) : null;
            if (m != null && !m.isEmpty()) onText(m.get(0));
        }
    };

    private String fullText() { return (base + " " + cur).replaceAll("\\s+", " ").trim(); }

    private long silenceMs(String full) { return CONN.matcher(full).matches() ? 3200 : 2000; }

    private void onText(String raw) {
        if (!running || busy || speaking) return;
        curRaw = raw == null ? "" : raw;
        String m = curRaw;
        if (!consumed.isEmpty() && m.startsWith(consumed)) m = m.substring(consumed.length());
        cur = m.trim();
        String full = fullText();
        if (full.isEmpty()) return;
        ZeroPlugin.emit("heard", "text", full);
        h.removeCallbacks(finalizeR);
        h.postDelayed(finalizeR, silenceMs(full));
    }

    private void sessionEnded() {
        listening = false;
        if (!running || busy || speaking) return;
        if (!cur.isEmpty()) {
            base = fullText();
            cur = "";
            curRaw = "";
            consumed = "";
            idleN = 0;
        } else {
            consumed = "";
            idleN++;
        }
        scheduleRestart(!base.isEmpty() ? 250 : Math.min(1200, 400 * (1 + idleN)));
    }

    /* ---------- wake word / desisyon ---------- */
    private String[] words(String t) {
        String s = t.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\u00f1\\s]", " ").trim();
        if (s.isEmpty()) return new String[0];
        return s.split("\\s+");
    }

    private String stripWake(String raw) { return WAKE_STRIP.matcher(raw).replaceAll(" ").replaceAll("\\s+", " ").trim(); }

    private boolean meaningful(String t) {
        int len = 0;
        int n = 0;
        for (String w : words(t)) {
            if (FILL.matcher(w).matches()) continue;
            n++;
            len += w.length();
        }
        return n >= 1 && len >= 2;
    }

    private boolean isEcho(String cmd) {
        if (lastSaid.isEmpty() || System.currentTimeMillis() - lastSaidAt > 20000) return false;
        String[] a = words(cmd);
        String[] b = words(lastSaid);
        if (a.length < 2) return false;
        int hit = 0;
        for (String x : a) {
            for (String y : b) {
                if (x.equals(y)) { hit++; break; }
            }
        }
        return ((double) hit) / a.length >= 0.7;
    }

    private String decide(String raw) {
        raw = raw.trim();
        if (raw.isEmpty()) return null;
        boolean hasWake = WAKE.matcher(raw).find();
        if (wakeOn) {
            if (hasWake) {
                String c = stripWake(raw);
                if (!meaningful(c)) {
                    awakeUntil = System.currentTimeMillis() + 8000;
                    ZeroPlugin.emit("awake", "text", "1");
                    h.removeCallbacks(awakeEndR);
                    h.postDelayed(awakeEndR, 8100);
                    return null;
                }
                return c;
            }
            if (System.currentTimeMillis() < awakeUntil) {
                if (!meaningful(raw) || isEcho(raw)) return null;
                return raw;
            }
            return null;
        }
        if (!meaningful(raw) || isEcho(raw)) return null;
        if (hasWake) {
            String s = stripWake(raw);
            return s.isEmpty() ? raw : s;
        }
        return raw;
    }

    private void finalizeNow() {
        String full = fullText();
        base = "";
        cur = "";
        if (full.isEmpty()) return;
        String cmd = decide(full);
        if (cmd == null) {
            consumed = curRaw;
            ZeroPlugin.emit("heard", "text", "");
            return;
        }
        busy = true;
        stopListen();
        h.removeCallbacks(awakeEndR);
        awakeUntil = 0;
        ZeroPlugin.emit("awake", "text", "0");
        setState("processing");
        ZeroPlugin.emit("heard", "text", cmd);
        pendingUser = cmd;
        final String c = cmd;
        new Thread(new Runnable() { public void run() { askServer(c); } }).start();
    }

    /* ---------- server (Gemini) ---------- */
    private String[] httpPost(String u, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(38000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("x-app-password", pw);
        OutputStream os = c.getOutputStream();
        os.write(json.getBytes("UTF-8"));
        os.close();
        int st = c.getResponseCode();
        InputStream is = st >= 400 ? c.getErrorStream() : c.getInputStream();
        StringBuilder sb = new StringBuilder();
        if (is != null) {
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
        }
        c.disconnect();
        return new String[]{String.valueOf(st), sb.toString()};
    }

    private JSONObject parseObj(String s) {
        try { return new JSONObject(s); } catch (Exception e) { return null; }
    }

    private void askServer(String text) {
        String say = null;
        JSONObject action = null;
        String err = null;
        try {
            JSONObject body = new JSONObject();
            body.put("history", hist);
            body.put("text", text);
            body.put("now", new SimpleDateFormat("EEEE, d MMMM yyyy HH:mm", new Locale("fil", "PH")).format(new Date()));
            String payload = body.toString();
            String[] r = httpPost(baseUrl + "/api/chat", payload);
            int st = Integer.parseInt(r[0]);
            JSONObject j = parseObj(r[1]);
            if (j == null && (st == 502 || st == 503 || st == 504)) {
                Thread.sleep(6000);
                r = httpPost(baseUrl + "/api/chat", payload);
                st = Integer.parseInt(r[0]);
                j = parseObj(r[1]);
            }
            if (st == 401) {
                err = "Mali o kulang ang APP_PASSWORD.";
            } else if (st >= 200 && st < 300 && j != null) {
                say = j.optString("say", "");
                action = j.optJSONObject("action");
            } else {
                JSONObject e = j != null ? j.optJSONObject("error") : null;
                err = e != null ? e.optString("message", "Server error " + st) : "Server error " + st;
            }
        } catch (Exception e) {
            err = "Walang koneksyon o hindi maabot ang server.";
        }
        final String fSay = say;
        final JSONObject fAct = action;
        final String fErr = err;
        h.post(new Runnable() {
            public void run() {
                if (!running) return;
                if (fErr != null) handleError(fErr);
                else handleReply(fSay == null ? "" : fSay, fAct);
            }
        });
    }

    private void addHist(String role, String text) {
        try {
            JSONObject o = new JSONObject();
            o.put("r", role);
            o.put("t", text);
            hist.put(o);
            while (hist.length() > 16) hist.remove(0);
        } catch (Exception e) { }
    }

    private void handleReply(String say, JSONObject action) {
        addHist("user", pendingUser);
        addHist("model", say);
        String extra = "";
        try { extra = runAction(action); } catch (Exception e) { extra = ""; }
        String text = (say + (extra.isEmpty() ? "" : " " + extra)).trim();
        speak(text.isEmpty() ? "Sige, boss." : text, false);
    }

    private void handleError(String msg) {
        ZeroPlugin.emit("say", "text", msg);
        long now = System.currentTimeMillis();
        if (now - lastErrAt > 30000) {
            lastErrAt = now;
            speak("May problema sa server, boss. Tingnan ang mensahe sa screen.", true);
        } else {
            speaking = true;
            afterSpeak();
        }
    }

    /* ---------- boses (Android TTS) ---------- */
    private void initTts() {
        tts = new TextToSpeech(getApplicationContext(), new TextToSpeech.OnInitListener() {
            public void onInit(int status) {
                ttsReady = status == TextToSpeech.SUCCESS;
                if (ttsReady && tts != null) {
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        public void onStart(String utteranceId) { }
                        public void onDone(String utteranceId) { h.post(new Runnable() { public void run() { afterSpeak(); } }); }
                        public void onError(String utteranceId) { h.post(new Runnable() { public void run() { afterSpeak(); } }); }
                    });
                }
            }
        });
    }

    private boolean guessTagalog(String text) {
        int tl = 0;
        int en = 0;
        for (String w : words(text)) {
            for (String t : TL_WORDS) { if (t.equals(w)) { tl++; break; } }
            for (String e : EN_WORDS) { if (e.equals(w)) { en++; break; } }
        }
        if (tl == en) return "tl".equals(lang);
        return tl > en;
    }

    private Locale pickLocale(String text) {
        Locale[] c = guessTagalog(text)
                ? new Locale[]{new Locale("fil", "PH"), new Locale("tl", "PH"), Locale.US}
                : new Locale[]{Locale.US, Locale.UK};
        for (Locale l : c) {
            try {
                if (tts.isLanguageAvailable(l) >= TextToSpeech.LANG_AVAILABLE) return l;
            } catch (Exception e) { }
        }
        return Locale.US;
    }

    private void speak(String text, boolean keepScreen) {
        speaking = true;
        stopListen();
        setState("speaking");
        if (!keepScreen) ZeroPlugin.emit("say", "text", text);
        lastSaid = text;
        if (!ttsReady || tts == null) { afterSpeak(); return; }
        try {
            tts.setLanguage(pickLocale(text));
            tts.setPitch(0.9f);
            tts.setSpeechRate(1.0f);
            utt++;
            h.removeCallbacks(guardR);
            h.postDelayed(guardR, Math.max(9000, text.length() * 160L));
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "z" + utt);
        } catch (Exception e) {
            afterSpeak();
        }
    }

    private void afterSpeak() {
        if (!speaking) return;
        speaking = false;
        h.removeCallbacks(guardR);
        lastSaidAt = System.currentTimeMillis();
        guardUntil = lastSaidAt + 800;
        busy = false;
        idleN = 0;
        base = "";
        cur = "";
        curRaw = "";
        consumed = "";
        setState("listening");
        scheduleRestart(900);
    }

    /* ---------- mga aksyon sa phone ---------- */
    private String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); } catch (Exception e) { return ""; }
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        boolean allowed = Build.VERSION.SDK_INT < 29 || ZeroPlugin.visible || Settings.canDrawOverlays(this);
        if (allowed) {
            try { startActivity(i); return; } catch (Exception e) { }
        }
        // hindi pinapayagan ng Android ang pagbukas mula sa background: notification na pipindutin
        PendingIntent pi = PendingIntent.getActivity(this, (int) (System.currentTimeMillis() & 0xfffffff), i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = builder(CH2);
        b.setSmallIcon(android.R.drawable.ic_btn_speak_now);
        b.setContentTitle("ZERO");
        b.setContentText("Pindutin para buksan");
        b.setContentIntent(pi);
        b.setAutoCancel(true);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(2, b.build());
    }

    private void openUrl(String url) { open(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }

    private String runAction(JSONObject a) {
        if (a == null) return "";
        String type = a.optString("type", "");
        if ("google".equals(type)) { openUrl("https://www.google.com/search?q=" + enc(a.optString("q"))); return ""; }
        if ("youtube".equals(type)) { openUrl("https://www.youtube.com/results?search_query=" + enc(a.optString("q"))); return ""; }
        if ("facebook".equals(type)) { openUrl("https://www.facebook.com/search/top?q=" + enc(a.optString("q"))); return ""; }
        if ("maps".equals(type)) { openUrl("https://www.google.com/maps/search/?api=1&query=" + enc(a.optString("q"))); return ""; }
        if ("app".equals(type)) {
            String n = a.optString("name");
            if ("messenger".equals(n)) openUrl("fb-messenger://");
            else if ("facebook".equals(n)) openUrl("fb://feed");
            else if ("youtube".equals(n)) openUrl("vnd.youtube://");
            else if ("whatsapp".equals(n)) openUrl("whatsapp://send");
            else if ("maps".equals(n)) openUrl("geo:0,0?q=");
            else if ("camera".equals(n)) open(new Intent(MediaStore.ACTION_IMAGE_CAPTURE));
            else if ("settings".equals(n)) open(new Intent(Settings.ACTION_SETTINGS));
            return "";
        }
        if ("call".equals(type)) { open(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + a.optString("number").replaceAll("[^\\d+]", "")))); return ""; }
        if ("sms".equals(type)) {
            Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + a.optString("number").replaceAll("[^\\d+]", "")));
            i.putExtra("sms_body", a.optString("body"));
            open(i);
            return "";
        }
        if ("time".equals(type)) {
            return "Ang oras ngayon ay " + new SimpleDateFormat("h:mm a, EEEE, MMMM d", new Locale("fil", "PH")).format(new Date()) + ".";
        }
        if ("location".equals(type)) {
            if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                return "Payagan ang location permission sa app.";
            Location l = null;
            try {
                LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
                l = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (l == null) l = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            } catch (SecurityException e) { }
            if (l == null) return "Hindi ko makuha ang location.";
            String la = String.format(Locale.US, "%.5f", l.getLatitude());
            String lo = String.format(Locale.US, "%.5f", l.getLongitude());
            openUrl("https://www.google.com/maps/search/?api=1&query=" + la + "," + lo);
            return "Nandito ka sa latitude " + la + ", longitude " + lo + ". Binuksan ko ang mapa.";
        }
        if ("timer".equals(type)) {
            int secs = Math.max(1, a.optInt("seconds", 60));
            Intent i = new Intent(AlarmClock.ACTION_SET_TIMER);
            i.putExtra(AlarmClock.EXTRA_LENGTH, secs);
            i.putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "ZERO"));
            i.putExtra(AlarmClock.EXTRA_SKIP_UI, true);
            open(i);
            return "";
        }
        if ("alarm".equals(type)) {
            String t = a.optString("time", "");
            String[] p = t.split(":");
            if (p.length != 2) return "Hindi ko naintindihan ang oras ng alarm.";
            try {
                Intent i = new Intent(AlarmClock.ACTION_SET_ALARM);
                i.putExtra(AlarmClock.EXTRA_HOUR, Integer.parseInt(p[0].trim()));
                i.putExtra(AlarmClock.EXTRA_MINUTES, Integer.parseInt(p[1].trim()));
                i.putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "ZERO"));
                i.putExtra(AlarmClock.EXTRA_SKIP_UI, true);
                open(i);
            } catch (Exception e) { return "Hindi ko naintindihan ang oras ng alarm."; }
            return "";
        }
        return "";
    }
}
