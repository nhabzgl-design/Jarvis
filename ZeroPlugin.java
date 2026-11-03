package com.zero.assistant;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** Tulay ng web page (index.html) papunta sa ZeroService. EXPERIMENTAL. */
@CapacitorPlugin(name = "ZeroService")
public class ZeroPlugin extends Plugin {
    static ZeroPlugin instance;
    static volatile boolean visible = false;

    @Override
    public void load() { instance = this; }

    @Override
    protected void handleOnResume() { visible = true; }

    @Override
    protected void handleOnPause() { visible = false; }

    static void emit(String event, String key, String value) {
        final ZeroPlugin p = instance;
        if (p == null) return;
        JSObject o = new JSObject();
        o.put(key, value);
        p.notifyListeners(event, o);
    }

    @PluginMethod
    public void start(PluginCall call) {
        Context c = getContext();
        SharedPreferences.Editor e = c.getSharedPreferences("zero", Context.MODE_PRIVATE).edit();
        e.putString("base", call.getString("base", ""));
        e.putString("pw", call.getString("pw", ""));
        e.putString("lang", call.getString("lang", "tl"));
        e.putBoolean("wake", call.getBoolean("wake", true));
        e.apply();
        Intent i = new Intent(c, ZeroService.class);
        i.setAction("START");
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
            call.resolve();
        } catch (Exception ex) {
            call.reject("Hindi masimulan ang service: " + ex.getMessage());
        }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        try { getContext().stopService(new Intent(getContext(), ZeroService.class)); } catch (Exception ex) { }
        call.resolve();
    }

    @PluginMethod
    public void isRunning(PluginCall call) {
        JSObject o = new JSObject();
        o.put("running", ZeroService.running);
        call.resolve(o);
    }

    @PluginMethod
    public void openOverlaySettings(PluginCall call) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getContext().getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception ex) { call.reject("Hindi mabuksan ang settings: " + ex.getMessage()); }
    }

    @PluginMethod
    public void requestIgnoreBattery(PluginCall call) {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getContext().getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception ex) { call.reject("Hindi mabuksan ang settings: " + ex.getMessage()); }
    }
}
