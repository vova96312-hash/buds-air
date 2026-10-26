package com.vova.budsair;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.os.Build;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@CapacitorPlugin(
    name = "Buds",
    permissions = { @Permission(alias = "bt", strings = { Manifest.permission.BLUETOOTH_CONNECT }) }
)
public class BudsPlugin extends Plugin {
    private BudsLink link;
    private final BudsLink.Listener listener = () -> notifyListeners("state", stateJson());

    @Override
    public void load() {
        link = BudsLink.get(getContext());
        link.addListener(listener);
    }

    @Override
    protected void handleOnDestroy() { if (link != null) link.removeListener(listener); }

    private boolean hasBt() { return Build.VERSION.SDK_INT < 31 || getPermissionState("bt") == PermissionState.GRANTED; }

    @PluginMethod
    public void ensurePermission(PluginCall call) {
        if (hasBt()) { JSObject r = new JSObject(); r.put("granted", true); call.resolve(r); return; }
        requestPermissionForAlias("bt", call, "permCallback");
    }

    @PermissionCallback
    private void permCallback(PluginCall call) { JSObject r = new JSObject(); r.put("granted", hasBt()); call.resolve(r); }

    @SuppressLint("MissingPermission")
    @PluginMethod
    public void getBonded(PluginCall call) {
        if (!hasBt()) { call.reject("Нет разрешения Bluetooth", "NO_PERMISSION"); return; }
        BluetoothAdapter a = link.adapter();
        if (a == null) { call.reject("Bluetooth недоступен"); return; }
        if (!a.isEnabled()) { call.reject("Bluetooth выключен", "BT_OFF"); return; }
        JSArray arr = new JSArray();
        try {
            Set<BluetoothDevice> set = a.getBondedDevices();
            if (set != null) for (BluetoothDevice d : set) {
                JSObject o = new JSObject();
                o.put("name", d.getName() == null ? d.getAddress() : d.getName());
                o.put("address", d.getAddress());
                arr.put(o);
            }
        } catch (SecurityException e) { call.reject("Нет разрешения Bluetooth", "NO_PERMISSION"); return; }
        JSObject r = new JSObject();
        r.put("devices", arr);
        call.resolve(r);
    }

    @PluginMethod
    public void connect(PluginCall call) {
        if (!hasBt()) { call.reject("Нет разрешения Bluetooth", "NO_PERMISSION"); return; }
        String addr = call.getString("address", link.address);
        new Thread(() -> {
            if (link.connectBlocking(addr)) call.resolve(stateJson());
            else call.reject(link.error == null ? "Не удалось подключиться" : link.error);
        }, "buds-plugin-connect").start();
    }

    @PluginMethod public void disconnect(PluginCall call) { link.disconnect(); call.resolve(); }
    @PluginMethod public void getState(PluginCall call) { call.resolve(stateJson()); }
    @PluginMethod public void refresh(PluginCall call) { link.refresh(); call.resolve(); }

    private int num(PluginCall call, String k, int def) { Integer v = call.getInt(k); return v == null ? def : v; }

    @PluginMethod public void setAnc(PluginCall call) { link.setAnc(num(call, "mask", BudsLink.ANC_ON)); call.resolve(); }
    @PluginMethod public void setHoldMask(PluginCall call) { link.setHoldMask(num(call, "mask", 0x0B)); call.resolve(); }
    @PluginMethod public void setFeature(PluginCall call) { link.setFeature(num(call, "id", 6), Boolean.TRUE.equals(call.getBoolean("on", false))); call.resolve(); }
    @PluginMethod public void find(PluginCall call) { link.find(Boolean.TRUE.equals(call.getBoolean("start", true))); call.resolve(); }
    @PluginMethod public void fitTest(PluginCall call) { link.fitTest(Boolean.TRUE.equals(call.getBoolean("start", true))); call.resolve(); }
    @PluginMethod public void setEqPreset(PluginCall call) { link.setEqPreset(num(call, "id", 0)); call.resolve(); }
    @PluginMethod public void setAlertVolume(PluginCall call) { link.setAlertVolume(num(call, "level", 5)); call.resolve(); }
    @PluginMethod public void queryDevices(PluginCall call) { link.send(BudsLink.DEVICES_Q, null); call.resolve(); }

    @PluginMethod
    public void setGesture(PluginCall call) {
        link.setGesture(num(call, "dev", 1), num(call, "btn", 1), num(call, "act", 2), num(call, "fn", 0));
        call.resolve();
    }

    @PluginMethod
    public void setCustomEq(PluginCall call) {
        JSArray f = call.getArray("freqs"), g = call.getArray("gains");
        if (f == null || g == null || f.length() != g.length()) { call.reject("freqs/gains"); return; }
        int[] fr = new int[f.length()], ga = new int[g.length()];
        for (int i = 0; i < fr.length; i++) { fr[i] = f.optInt(i); ga[i] = g.optInt(i); }
        link.setCustomEq(fr, ga);
        call.resolve();
    }

    /** Отправить произвольную команду (для экспериментов из журнала). */
    @PluginMethod
    public void send(PluginCall call) {
        JSArray arr = call.getArray("payload");
        byte[] p = new byte[arr == null ? 0 : arr.length()];
        for (int i = 0; i < p.length; i++) p[i] = (byte) arr.optInt(i);
        link.send(num(call, "cmd", 0x0100), p);
        call.resolve();
    }

    @PluginMethod
    public void getLog(PluginCall call) { JSObject r = new JSObject(); r.put("lines", new JSONArray(link.getLog())); call.resolve(r); }

    @PluginMethod public void updateWidget(PluginCall call) { BudsWidget.updateAll(getContext()); call.resolve(); }

    private JSObject stateJson() {
        JSObject o = new JSObject();
        o.put("connected", link.connected);
        o.put("connecting", link.connecting);
        o.put("address", link.address);
        o.put("name", link.name);
        o.put("error", link.error);
        o.put("firmware", link.firmware);
        o.put("productId", link.productId);
        o.put("batteryLeft", link.batteryLeft);
        o.put("batteryRight", link.batteryRight);
        o.put("batteryCase", link.batteryCase);
        o.put("chargingLeft", link.chargingLeft);
        o.put("chargingRight", link.chargingRight);
        o.put("chargingCase", link.chargingCase);
        o.put("wearLeft", link.wearLeft);
        o.put("wearRight", link.wearRight);
        o.put("wearCase", link.wearCase);
        o.put("ancMode", link.ancMode);
        o.put("ancLevel", link.ancLevel);
        o.put("smartLevel", link.smartLevel);
        o.put("holdMask", link.holdMask);
        o.put("eqPreset", link.eqPreset);
        o.put("eqAcked", link.eqAcked);
        o.put("alertVolume", link.alertVolume);
        o.put("fitLeft", link.fitLeft);
        o.put("fitRight", link.fitRight);
        o.put("findAcked", link.findAcked);
        o.put("lastAck", link.lastAck);
        JSObject f = new JSObject();
        synchronized (link.features) { for (Map.Entry<Integer, Integer> e : link.features.entrySet()) f.put(String.valueOf(e.getKey()), e.getValue()); }
        o.put("features", f);
        List<Integer> cmds;
        synchronized (link.commands) { cmds = new ArrayList<>(link.commands); }
        o.put("commands", new JSONArray(cmds));
        JSObject g = new JSObject();
        synchronized (link.gestures) { for (Map.Entry<String, Integer> e : link.gestures.entrySet()) g.put(e.getKey(), e.getValue()); }
        o.put("gestures", g);
        JSONArray d = new JSONArray();
        synchronized (link.devices) { for (Map<String, Object> m : link.devices) d.put(new JSONObject(m)); }
        o.put("devices", d);
        return o;
    }
}
