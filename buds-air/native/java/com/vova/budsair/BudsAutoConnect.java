package com.vova.budsair;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Ловит подключение/отключение наушников к телефону и сам устанавливает канал управления. */
public class BudsAutoConnect extends BroadcastReceiver {
    @SuppressLint("MissingPermission")
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!BudsWidget.hasBt(ctx)) return;
        String action = intent.getAction();
        BudsLink link = BudsLink.get(ctx.getApplicationContext());
        if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
            int st = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
            if (st == BluetoothAdapter.STATE_OFF) link.onLinkLost(link.address);
            return;
        }
        BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (d == null) return;
        String addr = d.getAddress();
        String name = null;
        try { name = d.getName(); } catch (SecurityException ignored) {}
        boolean ours = link.address != null ? link.address.equals(addr) : BudsLink.looksLikeBuds(name);
        if (!ours) return;
        if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
            final PendingResult r = goAsync();
            link.autoConnect(addr, 1800);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(r::finish, 9000);
        } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
            link.onLinkLost(addr);
        }
    }
}
