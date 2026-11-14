package com.vova.budsair;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

/**
 * Фоновая служба для поддержания связи с наушниками и показа уведомления в шторке.
 */
public class BudsService extends Service {
    private static final String CHANNEL_ID = "buds_service";
    private static final int NOTIF_ID = 101;
    private BudsLink link;
    private final BudsLink.Listener listener = this::updateNotification;

    @Override
    public void onCreate() {
        super.onCreate();
        link = BudsLink.get(this);
        link.addListener(listener);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification());
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        link.removeListener(listener);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel chan = new NotificationChannel(CHANNEL_ID, "Состояние наушников", NotificationManager.IMPORTANCE_LOW);
            chan.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(chan);
        }
    }

    private void updateNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification());
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        String title = link.connected ? (link.name != null ? link.name : "Подключено") : "Поиск наушников...";
        String text = "";
        if (link.connected) {
            if (link.batteryLeft >= 0) text += "L: " + link.batteryLeft + "% ";
            if (link.batteryRight >= 0) text += "R: " + link.batteryRight + "% ";
            if (link.batteryCase >= 0) text += "C: " + link.batteryCase + "%";
            if (text.isEmpty()) text = "Данные обновляются";
            text += " · " + BudsLink.ancLabel(link.ancMode);
        } else {
            text = "Нажмите, чтобы открыть приложение";
        }

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_buds_tile)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
}
