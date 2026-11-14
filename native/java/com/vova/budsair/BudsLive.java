package com.vova.budsair;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.Bundle;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/**
 * Android 16 «Live Update»: уведомление, которое система показывает в своей капсуле у камеры
 * (на realme UI 7 / ColorOS 16 — Live Alerts, на Pixel — чип в статус-баре).
 *
 * Требования Android 16 к такому уведомлению: ongoing, есть заголовок, обычный стиль,
 * без своей разметки и без colorized. Флаг запроса — extra "android.requestPromotedOngoing",
 * текст в капсуле — "android.shortCriticalText" (ставим через extras, чтобы не поднимать compileSdk до 36).
 */
public final class BudsLive {
    private static final String CHANNEL = "buds_live";
    private static final int ID = 202;
    private static final String EXTRA_PROMOTE = "android.requestPromotedOngoing";
    private static final String EXTRA_SHORT = "android.shortCriticalText";

    private BudsLive() {}

    public static void post(Context ctx, BudsLink l) {
        try {
            NotificationManagerCompat nm = NotificationManagerCompat.from(ctx);
            if (!nm.areNotificationsEnabled()) return;
            channel(ctx);

            int a = l.batteryLeft, b = l.batteryRight, c = l.batteryCase;
            int min = a < 0 ? b : (b < 0 ? a : Math.min(a, b));
            StringBuilder t = new StringBuilder();
            if (a >= 0) t.append("L ").append(a).append('%');
            if (b >= 0) { if (t.length() > 0) t.append(" · "); t.append("R ").append(b).append('%'); }
            if (c >= 0) { if (t.length() > 0) t.append(" · "); t.append("Кейс ").append(c).append('%'); }
            if (t.length() == 0) t.append("Подключено");

            Intent open = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            PendingIntent pi = PendingIntent.getActivity(ctx, 7, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

            Bundle ex = new Bundle();
            ex.putBoolean(EXTRA_PROMOTE, true);
            ex.putCharSequence(EXTRA_SHORT, min >= 0 ? min + "%" : "🎧");

            NotificationCompat.Builder nb = new NotificationCompat.Builder(ctx, CHANNEL)
                    .setSmallIcon(R.drawable.ic_buds_tile)
                    .setContentTitle(l.name != null ? l.name : "Наушники подключены")
                    .setContentText(t.toString())
                    .setSubText("Подключено")
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setSilent(true)
                    .setShowWhen(false)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setTimeoutAfter(9000)          // через 9 секунд исчезнет сама, как на iPhone
                    .addExtras(ex);
            nm.notify(ID, nb.build());
        } catch (SecurityException e) {
            BudsLink.get(ctx).note("live: нет разрешения на уведомления");
        } catch (Exception e) {
            BudsLink.get(ctx).note("live: " + e.getMessage());
        }
    }

    public static void cancel(Context ctx) {
        try { NotificationManagerCompat.from(ctx).cancel(ID); } catch (Exception ignored) {}
    }

    private static void channel(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) return;
        // важность DEFAULT: системе нельзя продвигать уведомления из канала с IMPORTANCE_MIN
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Подключение наушников", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setSound(null, (AudioAttributes) null);
        ch.enableVibration(false);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }
}
