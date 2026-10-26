package com.vova.budsair;

import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Плитка в шторке: нажатие переключает Шумоподавление → Прозрачность → Выкл. */
public class BudsTile extends TileService {
    private final BudsLink.Listener listener = this::render;

    @Override
    public void onStartListening() {
        BudsLink.get(this).addListener(listener);
        render();
    }

    @Override
    public void onStopListening() {
        BudsLink.get(this).removeListener(listener);
    }

    @Override
    public void onClick() {
        if (!BudsWidget.hasBt(this)) return;
        final BudsLink l = BudsLink.get(this);
        new Thread(() -> {
            if (!l.connected) l.connectBlocking(l.address);
            if (l.connected) l.setAnc(l.nextAnc());
        }, "buds-tile").start();
    }

    private void render() {
        Tile t = getQsTile();
        if (t == null) return;
        BudsLink l = BudsLink.get(this);
        t.setIcon(Icon.createWithResource(this, R.drawable.ic_buds_tile));
        t.setLabel(l.connected ? BudsLink.ancLabel(l.ancMode) : "Наушники");
        if (Build.VERSION.SDK_INT >= 29) {
            String bat = (l.batteryLeft >= 0 ? "L " + l.batteryLeft + "%" : "") + (l.batteryRight >= 0 ? "  R " + l.batteryRight + "%" : "");
            t.setSubtitle(l.connected ? bat.trim() : "нажми — подключить");
        }
        t.setState(l.connected && l.ancMode != BudsLink.ANC_OFF && l.ancMode != 0 ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.updateTile();
    }
}
