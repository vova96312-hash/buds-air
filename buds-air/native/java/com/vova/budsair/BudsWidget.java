package com.vova.budsair;

import android.Manifest;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.RemoteViews;

/** Виджет: заряд наушников и кейса + переключение режима шумоподавления. */
public class BudsWidget extends AppWidgetProvider {
    static final String ACT_OFF = "com.vova.budsair.ANC_OFF";
    static final String ACT_ON = "com.vova.budsair.ANC_ON";
    static final String ACT_TRANS = "com.vova.budsair.ANC_TRANS";
    static final String ACT_REFRESH = "com.vova.budsair.REFRESH";

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) mgr.updateAppWidget(id, build(ctx));
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        super.onReceive(ctx, intent);
        final String a = intent.getAction();
        if (!ACT_OFF.equals(a) && !ACT_ON.equals(a) && !ACT_TRANS.equals(a) && !ACT_REFRESH.equals(a)) return;
        if (!hasBt(ctx)) { openApp(ctx); return; }
        final PendingResult result = goAsync();
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                BudsLink link = BudsLink.get(app);
                if (!link.connected) link.connectBlocking(link.address);
                if (link.connected) {
                    if (ACT_OFF.equals(a)) link.setAnc(BudsLink.ANC_OFF);
                    else if (ACT_ON.equals(a)) link.setAnc(link.ancOnMask());
                    else if (ACT_TRANS.equals(a)) link.setAnc(BudsLink.ANC_TRANSPARENCY);
                    else link.refresh();
                    Thread.sleep(900);
                    link.send(BudsLink.BATTERY, null);
                    Thread.sleep(700);
                }
                updateAll(app);
            } catch (Exception ignored) {
            } finally {
                result.finish();
            }
        }, "buds-widget").start();
    }

    static boolean hasBt(Context ctx) {
        return Build.VERSION.SDK_INT < 31
                || ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    static void openApp(Context ctx) {
        Intent i = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }

    public static void updateAll(Context ctx) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, BudsWidget.class));
            if (ids == null || ids.length == 0) return;
            RemoteViews v = build(ctx);
            for (int id : ids) mgr.updateAppWidget(id, v);
        } catch (Exception ignored) {}
    }

    private static PendingIntent pi(Context ctx, String action, int code) {
        Intent i = new Intent(ctx, BudsWidget.class).setAction(action);
        return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static String pct(int v) { return v < 0 ? "—" : v + "%"; }

    static RemoteViews build(Context ctx) {
        BudsLink l = BudsLink.get(ctx);
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_buds);
        String name = l.name == null ? "Наушники" : l.name;
        v.setTextViewText(R.id.w_name, name);
        v.setTextViewText(R.id.w_status, l.connected ? BudsLink.ancLabel(l.ancMode) : (l.connecting ? "Подключение…" : "Нажми, чтобы подключить"));
        v.setTextViewText(R.id.w_left, pct(l.batteryLeft) + (l.chargingLeft ? " ⚡" : ""));
        v.setTextViewText(R.id.w_right, pct(l.batteryRight) + (l.chargingRight ? " ⚡" : ""));
        v.setTextViewText(R.id.w_case, pct(l.batteryCase) + (l.chargingCase ? " ⚡" : ""));
        int m = l.ancMode;
        v.setInt(R.id.w_btn_off, "setBackgroundResource", m == BudsLink.ANC_OFF ? R.drawable.w_btn_on : R.drawable.w_btn);
        v.setInt(R.id.w_btn_on, "setBackgroundResource", l.isAncOn(m) ? R.drawable.w_btn_on : R.drawable.w_btn);
        v.setInt(R.id.w_btn_trans, "setBackgroundResource", m == BudsLink.ANC_TRANSPARENCY ? R.drawable.w_btn_on : R.drawable.w_btn);
        v.setOnClickPendingIntent(R.id.w_btn_off, pi(ctx, ACT_OFF, 1));
        v.setOnClickPendingIntent(R.id.w_btn_on, pi(ctx, ACT_ON, 2));
        v.setOnClickPendingIntent(R.id.w_btn_trans, pi(ctx, ACT_TRANS, 3));
        v.setOnClickPendingIntent(R.id.w_refresh, pi(ctx, ACT_REFRESH, 4));
        Intent open = new Intent(ctx, MainActivity.class);
        v.setOnClickPendingIntent(R.id.w_head, PendingIntent.getActivity(ctx, 5, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        return v;
    }
}
