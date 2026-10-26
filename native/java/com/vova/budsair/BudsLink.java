package com.vova.budsair;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Связь с наушниками realme / OPPO / OnePlus по их протоколу поверх Bluetooth Classic (RFCOMM).
 *
 * Кадр: AA | len(LEB128, = 7 + payloadLen) | 00 00 | cmd u16 LE | seq | payloadLen u16 LE | payload
 * Ответ: cmd | 0x8000, тот же seq. seq 0xFF — событие от наушников (push).
 *
 * Команды и форматы — по открытым проектам QuickBuds, Gadgetbridge и buds-control (GPL-3.0).
 * Один экземпляр на процесс: им пользуются приложение, виджет и плитка в шторке.
 */
public final class BudsLink {
    private static final String TAG = "BudsLink";
    public static final String PREFS = "buds";
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final UUID VENDOR1 = UUID.fromString("00001107-d102-11e1-9b23-00025b00a5a5");
    private static final UUID VENDOR2 = UUID.fromString("0000079a-d102-11e1-9b23-00025b00a5a5");

    // --- команды ---
    public static final int HANDSHAKE = 0x0100, PRODUCT_ID = 0x0103, FIRMWARE = 0x0105, BATTERY = 0x0106,
            GESTURES = 0x0108, WEAR = 0x0109, ANC_Q = 0x010C, FEATURES_Q = 0x010D, EQ_Q = 0x010F,
            DEVICES_Q = 0x0112, ALERT_Q = 0x0130, BROADCAST = 0x0200, SUBSCRIBE = 0x0205, PUSH = 0x0204,
            FIND = 0x0400, GESTURE_SET = 0x0401, FEATURE_SET = 0x0403, ANC_SET = 0x0404, FIT_TEST = 0x0405,
            EQ_SET = 0x0406, EQ_CUSTOM = 0x0418, SPATIAL_SRC = 0x041E, ALERT_SET = 0x0427;

    // режимы шумоподавления realme (одна таблица для записи и отчёта)
    public static final int ANC_OFF = 0x01, ANC_TRANSPARENCY = 0x02, ANC_LIGHT = 0x04, ANC_DEEP = 0x08,
            ANC_MEDIUM = 0x10, ANC_SMART = 0x20;
    public static final int ANC_ON = ANC_DEEP; // «шумоподавление» без уровня

    /** Переключатели функций, о которых спрашиваем наушники (0x010D). */
    static final byte[] FEATURE_IDS = {0x05, 0x04, 0x06, 0x09, 0x0B, 0x0C, 0x11, 0x17, 0x18, 0x1A, 0x1B, 0x1C, 0x1D, 0x27, 0x28, 0x30, 0x31, 0x35, 0x38, 0x3A};

    public interface Listener { void onChanged(); }

    // ---- состояние ----
    public volatile boolean connected, connecting;
    public volatile String address, name, error, firmware, productId;
    public volatile int batteryLeft = -1, batteryRight = -1, batteryCase = -1;
    public volatile boolean chargingLeft, chargingRight, chargingCase;
    public volatile int wearLeft = -1, wearRight = -1, wearCase = -1;
    public volatile int ancMode = 0;      // текущая маска режима
    public volatile int ancLevel = 0;     // последний выбранный уровень шумодава
    public volatile int smartLevel = 0;   // уровень, который выбрал «умный» режим
    public volatile int holdMask = -1;    // какие режимы переключает удержание
    public volatile int eqPreset = -1;
    public volatile int alertVolume = -1;
    public volatile String fitLeft, fitRight;
    public volatile boolean findAcked, eqAcked;
    public volatile String lastAck;
    public final Map<Integer, Integer> features = Collections.synchronizedMap(new LinkedHashMap<>());
    public final Set<Integer> commands = Collections.synchronizedSet(new HashSet<>());
    public final Map<String, Integer> gestures = Collections.synchronizedMap(new LinkedHashMap<>()); // "dev:btn:act" -> fn
    public final List<Map<String, Object>> devices = Collections.synchronizedList(new ArrayList<>());
    private final List<String> log = Collections.synchronizedList(new ArrayList<>());
    private volatile Set<Integer> offeredEvents;

    private static BudsLink instance;
    private final Context ctx;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Object connectLock = new Object(), writeLock = new Object(), seqLock = new Object();
    private volatile BluetoothSocket socket;
    private volatile OutputStream out;
    private int seq = 1;

    public static synchronized BudsLink get(Context c) {
        if (instance == null) instance = new BudsLink(c.getApplicationContext());
        return instance;
    }

    private BudsLink(Context c) {
        ctx = c;
        SharedPreferences p = prefs();
        address = p.getString("address", null);
        name = p.getString("name", null);
        productId = p.getString("product", null);
        batteryLeft = p.getInt("bl", -1);
        batteryRight = p.getInt("br", -1);
        batteryCase = p.getInt("bc", -1);
        ancMode = p.getInt("anc", 0);
        ancLevel = p.getInt("ancLevel", 0);
    }

    public SharedPreferences prefs() { return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    public void addListener(Listener l) { listeners.addIfAbsent(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    void changed() {
        prefs().edit().putInt("bl", batteryLeft).putInt("br", batteryRight).putInt("bc", batteryCase)
                .putInt("anc", ancMode).putInt("ancLevel", ancLevel).putString("product", productId).apply();
        main.post(() -> {
            for (Listener l : listeners) { try { l.onChanged(); } catch (Exception ignored) {} }
            BudsWidget.updateAll(ctx);
        });
    }

    void note(String s) {
        String line = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()) + " " + s;
        log.add(line);
        while (log.size() > 400) log.remove(0);
        Log.d(TAG, s);
    }

    public List<String> getLog() { synchronized (log) { return new ArrayList<>(log); } }

    public boolean has(int cmd) { return commands.isEmpty() || commands.contains(cmd); }

    // ---------------------------------------------------------------- подключение

    public BluetoothAdapter adapter() {
        BluetoothManager m = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        return m == null ? null : m.getAdapter();
    }

    /** Синхронное подключение — вызывать НЕ из главного потока. */
    public boolean connectBlocking(String addr) {
        synchronized (connectLock) { return connectLocked(addr); }
    }

    @SuppressLint("MissingPermission")
    private boolean connectLocked(String addr) {
        if (connected && socket != null && addr != null && addr.equals(address)) return true;
        closeSocket();
        BluetoothAdapter a = adapter();
        if (a == null || !a.isEnabled()) { error = "Bluetooth выключен"; changed(); return false; }
        if (addr == null) { error = "Наушники не выбраны"; changed(); return false; }
        connecting = true; error = null; changed();
        BluetoothDevice dev;
        try { dev = a.getRemoteDevice(addr); } catch (Exception e) { connecting = false; error = "Неверный адрес"; changed(); return false; }
        if (!addr.equals(address)) { productId = null; commands.clear(); features.clear(); gestures.clear(); }
        address = addr;
        try { name = dev.getName(); } catch (SecurityException ignored) {}
        prefs().edit().putString("address", addr).putString("name", name).apply();

        List<UUID> uuids = new ArrayList<>();
        try {
            ParcelUuid[] adv = dev.getUuids();
            if (adv != null) {
                for (ParcelUuid u : adv) if (VENDOR1.equals(u.getUuid()) || VENDOR2.equals(u.getUuid()) || SPP.equals(u.getUuid())) uuids.add(u.getUuid());
            }
        } catch (SecurityException ignored) {}
        for (UUID u : new UUID[]{SPP, VENDOR1, VENDOR2}) if (!uuids.contains(u)) uuids.add(u);
        try { a.cancelDiscovery(); } catch (SecurityException ignored) {}

        for (int attempt = 0; attempt <= uuids.size(); attempt++) {
            BluetoothSocket s = null;
            try {
                if (attempt < uuids.size()) {
                    note("connect " + uuids.get(attempt));
                    s = dev.createRfcommSocketToServiceRecord(uuids.get(attempt));
                } else {
                    note("connect insecure spp");
                    s = dev.createInsecureRfcommSocketToServiceRecord(SPP);
                }
                s.connect();
                socket = s;
                out = s.getOutputStream();
                connected = true; connecting = false; error = null;
                note("connected");
                startReader(s);
                changed();
                startInit();
                return true;
            } catch (SecurityException e) {
                connecting = false; error = "Нет разрешения Bluetooth"; changed();
                return false;
            } catch (IOException e) {
                note("fail: " + e.getMessage());
                try { if (s != null) s.close(); } catch (IOException ignored) {}
            }
        }
        connecting = false; connected = false;
        error = "Не удалось подключиться. Наушники должны быть подключены к телефону, а realme Link — закрыт";
        changed();
        return false;
    }

    public void disconnect() { closeSocket(); changed(); }

    private void closeSocket() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        socket = null; out = null; connected = false;
    }

    private void startReader(final BluetoothSocket s) {
        new Thread(() -> {
            ByteArrayOutputStream acc = new ByteArrayOutputStream();
            byte[] chunk = new byte[2048];
            try {
                InputStream in = s.getInputStream();
                while (true) {
                    int n = in.read(chunk);
                    if (n <= 0) break;
                    acc.write(chunk, 0, n);
                    byte[] all = acc.toByteArray();
                    int used = decode(all);
                    acc.reset();
                    if (used < all.length) acc.write(all, used, all.length - used);
                    if (acc.size() > 16384) acc.reset();
                }
            } catch (IOException e) {
                note("read end: " + e.getMessage());
            }
            if (socket == s) { connected = false; error = "Наушники отключились"; socket = null; out = null; changed(); }
        }, "buds-reader").start();
    }

    /** Порядок как у фирменного приложения: 300 мс до первого кадра, затем по 200 мс. */
    private void startInit() {
        new Thread(() -> {
            try {
                Thread.sleep(300);
                send(HANDSHAKE, null); Thread.sleep(200);
                send(PRODUCT_ID, null); Thread.sleep(200);
                offeredEvents = null;
                send(BROADCAST, null); Thread.sleep(400);
                subscribe(); Thread.sleep(200);
                send(FIRMWARE, null); Thread.sleep(200);
                queryFeatures(); Thread.sleep(200);
                send(ANC_Q, new byte[]{1, 1}); Thread.sleep(200);
                send(BATTERY, null); Thread.sleep(200);
                send(WEAR, null); Thread.sleep(200);
                send(GESTURES, null); Thread.sleep(200);
                send(ANC_Q, new byte[]{2, 1}); Thread.sleep(200);
                if (has(ANC_SET)) { send(ANC_Q, new byte[]{4, 1}); Thread.sleep(200); }
                if (has(EQ_Q)) { send(EQ_Q, null); Thread.sleep(200); }
                if (has(ALERT_Q)) { send(ALERT_Q, null); Thread.sleep(200); }
                if (has(DEVICES_Q)) { send(DEVICES_Q, null); Thread.sleep(200); }
                if (commands.contains(SPATIAL_SRC)) { send(SPATIAL_SRC, new byte[]{0}); Thread.sleep(200); }
                // часть моделей игнорирует первые запросы — повторяем то, что не пришло
                Thread.sleep(1200);
                if (!connected) return;
                if (batteryLeft < 0 && batteryRight < 0) send(BATTERY, null);
                if (ancMode == 0) send(ANC_Q, new byte[]{1, 1});
                if (features.isEmpty()) queryFeatures();
                if (gestures.isEmpty()) send(GESTURES, null);
            } catch (InterruptedException ignored) {}
        }, "buds-init").start();
    }

    private void subscribe() {
        int[] want = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06};
        List<Integer> ids = new ArrayList<>();
        Set<Integer> offered = offeredEvents;
        for (int w : want) {
            if (w == 0x04 && !has(FIT_TEST)) continue;
            if (offered == null ? w <= 0x03 : offered.contains(w)) ids.add(w);
        }
        byte[] p = new byte[1 + ids.size()];
        p[0] = (byte) ids.size();
        for (int i = 0; i < ids.size(); i++) p[1 + i] = (byte) (int) ids.get(i);
        send(SUBSCRIBE, p);
    }

    public void queryFeatures() {
        byte[] p = new byte[1 + FEATURE_IDS.length];
        p[0] = (byte) FEATURE_IDS.length;
        System.arraycopy(FEATURE_IDS, 0, p, 1, FEATURE_IDS.length);
        send(FEATURES_Q, p);
    }

    // ---------------------------------------------------------------- кадры

    public void send(int cmd, byte[] payload) { sendSeq(cmd, -1, payload); }

    private void sendSeq(int cmd, int fixedSeq, byte[] payload) {
        final byte[] frame = encode(cmd, fixedSeq, payload);
        io.execute(() -> {
            OutputStream o = out;
            if (o == null) { note("skip (нет связи) " + hex(frame)); return; }
            try { synchronized (writeLock) { o.write(frame); o.flush(); } note("→ " + hex(frame)); }
            catch (IOException e) { note("send fail " + e.getMessage()); }
        });
    }

    private byte[] encode(int cmd, int fixedSeq, byte[] p) {
        if (p == null) p = new byte[0];
        int s;
        if (fixedSeq >= 0) s = fixedSeq;
        else synchronized (seqLock) { s = seq; seq = seq >= 0xFE ? 1 : seq + 1; }
        int total = 7 + p.length;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0xAA);
        do { int x = total & 0x7F; total >>>= 7; b.write(total != 0 ? (x | 0x80) : x); } while (total != 0);
        b.write(0); b.write(0);
        b.write(cmd & 0xFF); b.write((cmd >> 8) & 0xFF);
        b.write(s & 0xFF);
        b.write(p.length & 0xFF); b.write((p.length >> 8) & 0xFF);
        b.write(p, 0, p.length);
        return b.toByteArray();
    }

    /** Разбирает буфер; возвращает, сколько байт израсходовано. */
    private int decode(byte[] d) {
        int pos = 0;
        while (pos < d.length) {
            if (d[pos] != (byte) 0xAA) { pos++; continue; }
            int i = pos + 1, total = 0, shift = 0;
            boolean ok = false;
            while (i < d.length && shift <= 21) {
                int b = d[i++] & 0xFF;
                total |= (b & 0x7F) << shift;
                shift += 7;
                if ((b & 0x80) == 0) { ok = true; break; }
            }
            if (!ok) break;               // длина ещё не дошла
            if (d.length - i < total) break; // кадр ещё не дошёл
            if (total < 7) { pos = i; continue; }
            int cmd = (d[i + 2] & 0xFF) | ((d[i + 3] & 0xFF) << 8);
            int sq = d[i + 4] & 0xFF;
            int plen = (d[i + 5] & 0xFF) | ((d[i + 6] & 0xFF) << 8);
            if (7 + plen > total) { pos = i + total; continue; }
            byte[] p = new byte[plen];
            System.arraycopy(d, i + 7, p, 0, plen);
            byte[] frame = new byte[i + total - pos];
            System.arraycopy(d, pos, frame, 0, frame.length);
            note("← " + hex(frame));
            pos = i + total;
            try { handle(cmd, sq, p); } catch (Exception e) { note("parse error " + e); }
        }
        return pos;
    }

    private void handle(int cmd, int sq, byte[] p) {
        if ((cmd & 0x8000) == 0) { answerRequest(cmd, sq, p); return; }
        int st = p.length > 0 ? p[0] & 0xFF : -1;
        switch (cmd) {
            case HANDSHAKE | 0x8000: parseCommands(p); break;
            case PRODUCT_ID | 0x8000:
                if (p.length >= 4 && st == 0) {
                    int id = (p[1] & 0xFF) | ((p[2] & 0xFF) << 8) | ((p[3] & 0xFF) << 16);
                    productId = String.format(Locale.ROOT, "%06X", id); changed();
                }
                break;
            case BROADCAST | 0x8000:
                if (p.length >= 2 && st == 0) {
                    Set<Integer> s = new HashSet<>();
                    int n = p[1] & 0xFF;
                    for (int k = 0; k < n && 2 + k < p.length; k++) s.add(p[2 + k] & 0xFF);
                    offeredEvents = s;
                }
                break;
            case FIRMWARE | 0x8000: parseFirmware(p); break;
            case BATTERY | 0x8000: if (st == 0) parseBattery(p, 1); break;
            case WEAR | 0x8000: parseWear(p); break;
            case ANC_Q | 0x8000: if (st == 0 && p.length >= 4) parseAnc(p[1] & 0xFF, p, 3); break;
            case FEATURES_Q | 0x8000: parseFeatures(p); break;
            case GESTURES | 0x8000: parseGestures(p); break;
            case EQ_Q | 0x8000: if (st == 0 && p.length >= 2) { eqPreset = p[1] & 0xFF; changed(); } break;
            case ALERT_Q | 0x8000: if (st == 0 && p.length >= 2) { alertVolume = p[1] & 0xFF; changed(); } break;
            case DEVICES_Q | 0x8000: if (st == 0) parseDevices(p, 1); break;
            case FIND | 0x8000: findAcked = true; changed(); break;
            case EQ_CUSTOM | 0x8000: eqAcked = st == 0; changed(); break;
            case ANC_SET | 0x8000:
                if (st == 0x0E) { error = "Вставьте хотя бы один наушник в ухо — иначе режим не меняется"; changed(); }
                else if (st != 0) { lastAck = "ANC: ошибка " + st; changed(); }
                send(ANC_Q, new byte[]{1, 1});
                send(ANC_Q, new byte[]{2, 1});
                break;
            case FEATURE_SET | 0x8000:
            case GESTURE_SET | 0x8000:
            case EQ_SET | 0x8000:
            case ALERT_SET | 0x8000:
            case FIT_TEST | 0x8000:
                lastAck = String.format(Locale.ROOT, "%04x:%02x", cmd, st);
                if (cmd == (FEATURE_SET | 0x8000)) main.postDelayed(this::queryFeatures, 300);
                if (cmd == (GESTURE_SET | 0x8000)) main.postDelayed(() -> send(GESTURES, null), 300);
                if (cmd == (EQ_SET | 0x8000)) main.postDelayed(() -> send(EQ_Q, null), 200);
                if (cmd == (ALERT_SET | 0x8000) && p.length >= 2) alertVolume = p[1] & 0xFF;
                changed();
                break;
            default: break;
        }
    }

    /** Ответы на запросы от самих наушников — некоторые прошивки рвут связь без них. */
    private void answerRequest(int cmd, int sq, byte[] p) {
        if (cmd == PUSH) { handlePush(p, sq); return; }
        byte[] ans = null;
        if (cmd == 0x0500) {
            long t = System.currentTimeMillis() / 1000;
            ans = new byte[]{0, (byte) t, (byte) (t >> 8), (byte) (t >> 16), (byte) (t >> 24)};
        } else if (cmd == 0x050C) ans = new byte[]{0, 1, 0};
        else if (cmd == 0x051C) ans = new byte[]{0, 0, 0};
        else if (cmd == 0x051D) ans = new byte[]{1};
        else if (cmd == 0x0504) { if (p.length > 0) { eqPreset = p[0] & 0xFF; changed(); } }
        else if (cmd == 0x0501 || cmd == 0x0502 || cmd == 0x0510) { /* без ответа */ }
        else {
            int group = cmd & 0x7F00;
            if (group != 0x0100 && group != 0x0200 && group != 0x0300 && group != 0x0400 && group != 0x0500 && group != 0x0F00) ans = new byte[]{1};
        }
        if (ans != null) sendSeq(cmd | 0x8000, sq, ans);
    }

    private void handlePush(byte[] p, int sq) {
        if (p.length == 0) return;
        int sub = p[0] & 0xFF;
        switch (sub) {
            case 0x01: parseBattery(p, 1); break;
            case 0x02: parseWear(p); break;
            case 0x03:
                if (p.length >= 4) parseAnc(p[1] & 0xFF, p, 3);
                break;
            case 0x04: // проверка посадки: 04 <dev> <s> <dev> <s>
                for (int i = 1; i + 1 < p.length; i += 2) {
                    String r = fitName(p[i + 1] & 0xFF);
                    if ((p[i] & 0xFF) == 1) fitLeft = r; else if ((p[i] & 0xFF) == 2) fitRight = r;
                }
                changed();
                break;
            case 0x05:
                if (p.length >= 2) { features.put(0x06, (p[1] & 0xFF) != 0 ? 1 : 0); changed(); }
                break;
            case 0x06: parseDevices(p, 1); break;
            case 0xF2: sendSeq(PUSH | 0x8000, sq, new byte[]{0, (byte) 0xF2}); break;
            case 0xF4: sendSeq(PUSH | 0x8000, sq, new byte[]{0, (byte) 0xF4}); break;
            default: break;
        }
    }

    private static String fitName(int s) { return s == 1 ? "good" : s == 0 ? "average" : s == 6 ? "poor" : "error"; }

    private static final int[][] CAP_BITS = {
        {0x0105}, {0x0106}, {0x0107}, {0x0108, 0x0401, 0x0416}, {0x0109}, {0x0400}, {0x0402}, {0x0403},
        {0x010C, 0x0404}, {0x0405}, {0x0406, 0x010F}, {0x0407}, {}, {0x0408}, {0x0409}, {}, {}, {0x0114}, {},
        {0x040E, 0x040D, 0x0115, 0x0116}, {0x040F}, {0x0410, 0x0119}, {0x0205}, {0x0F00}, {}, {0x0118, 0x0411},
        {0x011A, 0x0412}, {0x011C, 0x0413}, {}, {0x0112, 0x040B}, {0x011E, 0x011F, 0x0415}, {0x040D}, {},
        {0x0121, 0x0417}, {0x0122, 0x0418}, {}, {0x011D, 0x0414}, {0x0123, 0x041A}, {0x0124, 0x041B},
        {0x0125, 0x041C, 0x0127, 0x041D, 0x041F}, {0x0421, 0x0126, 0x0129}, {0xEF01}, {0xEF02}, {0xEF03, 0x041E},
        {0x0420}, {0x001C}, {}, {0x0422, 0x012A}, {0xEF04}, {0x0423, 0x012B}, {}, {0x0424}, {0xEF06}, {}, {},
        {0x0425, 0x012E, 0x0426}, {0x012F}, {0x0427, 0x0130}, {0x0131, 0x0428}, {0x0429, 0x0132}, {0x0014},
        {0x042D, 0x0133}, {0x042E}, {0xEF07}, {0xEF08}, {0xEF09}, {0x0431, 0x0134},
    };

    private void parseCommands(byte[] p) {
        if (p.length < 2 || p[0] != 0) return;
        Set<Integer> s = new HashSet<>();
        for (int c : new int[]{0x0100, 0x0101, 0x0102, 0x0103, 0x0104, 0x0106, 0x010B, 0x010D, 0x0F00}) s.add(c);
        int bits = Math.min((p.length - 1) * 8, CAP_BITS.length);
        for (int b = 0; b < bits; b++) if ((((p[1 + b / 8] & 0xFF) >> (b % 8)) & 1) == 1) for (int c : CAP_BITS[b]) s.add(c);
        commands.clear(); commands.addAll(s);
        changed();
    }

    private void parseBattery(byte[] p, int countAt) {
        if (p.length < countAt + 3) return;
        for (int i = countAt + 1; i + 1 < p.length; i += 2) {
            int idx = p[i] & 0xFF;
            int lvl = p[i + 1] & 0x7F;
            boolean chg = (p[i + 1] & 0x80) != 0;
            if (idx == 1) { batteryLeft = lvl > 0 ? lvl : -1; chargingLeft = chg; }
            else if (idx == 2) { batteryRight = lvl > 0 ? lvl : -1; chargingRight = chg; }
            else if (idx == 3) { if (lvl > 0) { batteryCase = lvl; chargingCase = chg; } }
        }
        changed();
    }

    private void parseWear(byte[] p) {
        // ответ: [status?][count][comp st]..., событие: 02 [count][comp st]...
        int off = (p.length > 0 && (p[0] & 0xFF) == 0x02 && p.length >= 4) ? 1 : 0;
        if (!tryWear(p, off) && !tryWear(p, off + 1)) return;
        changed();
    }

    private boolean tryWear(byte[] p, int off) {
        if (p.length < off + 3) return false;
        int n = p[off] & 0xFF;
        if (n == 0 || n > 8) return false;
        int pos = off + 1;
        int l = wearLeft, r = wearRight, c = wearCase;
        for (int k = 0; k < n && pos + 1 < p.length; k++, pos += 2) {
            int comp = p[pos] & 0xFF, raw = p[pos + 1] & 0xFF;
            if (comp < 1 || comp > 3 || raw > 15) return false;
            int st = raw & 0x07;
            if (comp == 1) l = st; else if (comp == 2) r = st; else c = st;
        }
        wearLeft = l; wearRight = r; wearCase = c;
        return true;
    }

    /** p[at], p[at+1] — маска (LE). kind: 1 режим, 2 цикл удержания, 4 выбор «умного» режима. */
    private void parseAnc(int kind, byte[] p, int at) {
        int mask = p[at] & 0xFF;
        if (p.length > at + 1) mask |= (p[at + 1] & 0xFF) << 8;
        if (kind == 1) {
            ancMode = mask;
            if (mask == ANC_LIGHT || mask == ANC_MEDIUM || mask == ANC_DEEP || mask == ANC_SMART) ancLevel = mask;
        } else if (kind == 2) holdMask = mask;
        else if (kind == 4) smartLevel = mask;
        changed();
    }

    private void parseFeatures(byte[] p) {
        if (p.length < 2 || p[0] != 0) return;
        int n = p[1] & 0xFF;
        for (int k = 0, i = 2; k < n && i + 1 < p.length; k++, i += 2) features.put(p[i] & 0xFF, p[i + 1] & 0xFF);
        changed();
    }

    private void parseGestures(byte[] p) {
        if (p.length < 2 || p[0] != 0) return;
        int n = p[1] & 0xFF;
        gestures.clear();
        for (int k = 0, i = 2; k < n && i + 3 < p.length; k++, i += 4)
            gestures.put((p[i] & 0xFF) + ":" + (p[i + 1] & 0xFF) + ":" + (p[i + 2] & 0xFF), p[i + 3] & 0xFF);
        changed();
    }

    private void parseFirmware(byte[] p) {
        if (p.length < 3) return;
        String raw = new String(p, 2, p.length - 2, StandardCharsets.UTF_8).replace("\u0000", "").trim();
        String[] parts = raw.split(",");
        List<String> v = new ArrayList<>();
        for (int i = 0; i + 2 < parts.length; i += 3) if ("2".equals(parts[i + 1].trim())) v.add(parts[i + 2].trim());
        firmware = v.isEmpty() ? raw : String.join(".", v);
        changed();
    }

    private void parseDevices(byte[] p, int countAt) {
        if (p.length <= countAt) return;
        int n = p[countAt] & 0xFF, pos = countAt + 1;
        List<Map<String, Object>> list = new ArrayList<>();
        for (int k = 0; k < n && pos + 7 <= p.length; k++) {
            StringBuilder mac = new StringBuilder();
            for (int j = 5; j >= 0; j--) { mac.append(String.format(Locale.ROOT, "%02X", p[pos + j] & 0xFF)); if (j > 0) mac.append(':'); }
            int len = p[pos + 6] & 0xFF;
            int base = pos + 7;
            if (base + len > p.length || len < 3) break;
            int state = p[base] & 0xFF, flags = p[base + 1] & 0xFF, nl = p[base + 2] & 0xFF;
            String nm = new String(p, base + 3, Math.min(nl, len - 3), StandardCharsets.UTF_8);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("mac", mac.toString()); m.put("name", nm); m.put("connected", state == 2);
            m.put("thisPhone", (flags & 1) != 0); m.put("main", (flags & 2) != 0); m.put("playing", (flags & 4) != 0);
            list.add(m);
            pos = base + len;
        }
        devices.clear(); devices.addAll(list);
        changed();
    }

    // ---------------------------------------------------------------- действия

    public void refresh() {
        send(BATTERY, null);
        send(WEAR, null);
        send(ANC_Q, new byte[]{1, 1});
        queryFeatures();
    }

    public void setAnc(int mask) {
        ancMode = mask;
        if (mask == ANC_LIGHT || mask == ANC_MEDIUM || mask == ANC_DEEP || mask == ANC_SMART) ancLevel = mask;
        error = null;
        changed();
        byte[] m = mask > 0xFF ? new byte[]{0x01, 0x01, (byte) mask, (byte) (mask >> 8)} : new byte[]{0x01, 0x01, (byte) mask};
        send(ANC_SET, m);
    }

    /** Режим «шумоподавление» с последним выбранным уровнем. */
    public int ancOnMask() { return ancLevel != 0 ? ancLevel : ANC_ON; }

    public boolean isAncOn(int m) { return m == ANC_LIGHT || m == ANC_MEDIUM || m == ANC_DEEP || m == ANC_SMART; }

    public int nextAnc() {
        if (isAncOn(ancMode)) return ANC_TRANSPARENCY;
        if (ancMode == ANC_TRANSPARENCY) return ANC_OFF;
        return ancOnMask();
    }

    public void setHoldMask(int mask) { holdMask = mask; changed(); send(ANC_SET, new byte[]{0x02, 0x01, (byte) mask}); }
    public void setFeature(int id, boolean on) { features.put(id, on ? 1 : 0); changed(); send(FEATURE_SET, new byte[]{(byte) id, (byte) (on ? 1 : 0)}); }
    public void find(boolean start) { findAcked = false; send(FIND, new byte[]{(byte) (start ? 1 : 0)}); }
    public void fitTest(boolean start) { if (start) { fitLeft = null; fitRight = null; changed(); } send(FIT_TEST, new byte[]{(byte) (start ? 1 : 0)}); }
    public void setEqPreset(int id) { eqPreset = id; changed(); send(EQ_SET, new byte[]{(byte) id}); }
    public void setAlertVolume(int level) { alertVolume = level; changed(); send(ALERT_SET, new byte[]{(byte) Math.max(1, Math.min(10, level))}); }

    public void setGesture(int dev, int btn, int act, int fn) {
        gestures.put(dev + ":" + btn + ":" + act, fn); changed();
        send(GESTURE_SET, new byte[]{0x01, (byte) dev, (byte) btn, (byte) act, (byte) fn});
    }

    /** Своя кривая эквалайзера (0x0418): FA 06 = диапазон ±6 дБ. */
    public void setCustomEq(int[] freqs, int[] gains) {
        byte[] p = new byte[6 + freqs.length * 3];
        p[0] = 0; p[1] = (byte) 0xFA; p[2] = 6; p[3] = 0; p[4] = 0; p[5] = (byte) freqs.length;
        for (int i = 0, j = 6; i < freqs.length; i++, j += 3) {
            p[j] = (byte) (freqs[i] & 0xFF); p[j + 1] = (byte) ((freqs[i] >> 8) & 0xFF);
            p[j + 2] = (byte) Math.max(-6, Math.min(6, gains[i]));
        }
        eqAcked = false;
        send(EQ_CUSTOM, p);
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x ", x));
        return sb.toString().trim();
    }

    public static String ancLabel(int m) {
        switch (m) {
            case ANC_OFF: return "Выключено";
            case ANC_TRANSPARENCY: return "Прозрачность";
            case ANC_LIGHT: return "Шумодав · слабый";
            case ANC_MEDIUM: return "Шумодав · средний";
            case ANC_DEEP: return "Шумоподавление";
            case ANC_SMART: return "Шумодав · умный";
            default: return "—";
        }
    }
}
