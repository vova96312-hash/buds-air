package com.vova.budsair;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;

import java.util.List;

/**
 * «Dynamic Island» как на iPhone: при подключении наушников из фронтальной камеры
 * вырастает чёрная капсула → разворачивается в карточку с зарядом → сворачивается обратно в камеру.
 *
 * Рисуется поверх всех окон (нужно разрешение «Поверх других приложений»).
 * Капсула центрируется по вырезу камеры (на realme 12+ 5G — по центру сверху).
 */
public final class BudsIsland {
    public static final String PREF_ON = "island";

    private static BudsIsland instance;
    private final Context ctx;
    private final WindowManager wm;
    private final Handler h = new Handler(Looper.getMainLooper());
    private IslandView view;
    private WindowManager.LayoutParams lp;

    // данные
    String name = "Наушники";
    int left = -1, right = -1, box = -1;
    boolean chL, chR, chC;

    public static synchronized BudsIsland get(Context c) {
        if (instance == null) instance = new BudsIsland(c.getApplicationContext());
        return instance;
    }

    private BudsIsland(Context c) {
        ctx = c;
        wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
    }

    public static boolean canDraw(Context c) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    public static boolean enabled(Context c) {
        return c.getSharedPreferences(BudsLink.PREFS, Context.MODE_PRIVATE).getBoolean(PREF_ON, true);
    }

    /** Обновить данные (если капсула на экране — перерисуется). */
    public void setData(BudsLink l) {
        final String n = l.name != null ? l.name : "Наушники";
        final int a = l.batteryLeft, b = l.batteryRight, c = l.batteryCase;
        final boolean x = l.chargingLeft, y = l.chargingRight, z = l.chargingCase;
        h.post(() -> {
            name = n; left = a; right = b; box = c; chL = x; chR = y; chC = z;
            if (view != null) view.invalidate();
        });
    }

    public boolean isShowing() { return view != null; }

    /** Полная анимация подключения. */
    public void showConnected() {
        h.post(() -> {
            if (!canDraw(ctx)) return;
            if (view != null) { view.replay(); return; }
            attach();
            if (view != null) view.play();
        });
    }

    public void hideNow() { h.post(this::detach); }

    // ------------------------------------------------------------------ окно

    private void attach() {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int sw = dm.widthPixels, sh = dm.heightPixels;
        float d = dm.density;
        Rect cut = cameraRect();
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowMetrics m = wm.getCurrentWindowMetrics();
            sw = m.getBounds().width(); sh = m.getBounds().height();
        }
        if (sw > sh) return; // в альбомной ориентации не показываем

        view = new IslandView(ctx, this, sw, cut, d);
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, view.windowHeight(), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0; lp.y = 0;
        lp.setTitle("BudsIsland");
        if (Build.VERSION.SDK_INT >= 30) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            lp.setFitInsetsTypes(0);           // иначе окно сдвинется под статус-бар
            lp.setFitInsetsIgnoringVisibility(true);
        } else if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        try { wm.addView(view, lp); }
        catch (Exception e) { view = null; BudsLink.get(ctx).note("island: " + e.getMessage()); }
    }

    void setTouchable(boolean on) {
        if (view == null || lp == null) return;
        if (on) lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try { wm.updateViewLayout(view, lp); } catch (Exception ignored) {}
    }

    void detach() {
        if (view == null) return;
        view.cancel();
        try { wm.removeViewImmediate(view); } catch (Exception ignored) {}
        view = null;
    }

    void openApp() {
        try {
            Intent i = new Intent(ctx, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            ctx.startActivity(i);
        } catch (Exception ignored) {}
    }

    /** Где фронтальная камера (вырез). null — выреза нет. */
    private Rect cameraRect() {
        DisplayCutout c = null;
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsets ins = wm.getCurrentWindowMetrics().getWindowInsets();
                c = ins.getDisplayCutout();
            } else if (Build.VERSION.SDK_INT >= 29) {
                c = wm.getDefaultDisplay().getCutout();
            }
        } catch (Exception ignored) {}
        if (c == null) return null;
        List<Rect> rs = c.getBoundingRects();
        for (Rect r : rs) if (r.top <= 0 || r.top < r.height()) return new Rect(r);
        return null;
    }

    // ================================================================== отрисовка

    static final class IslandView extends View {
        // фазы
        static final int DOT = 0, COMPACT = 1, EXPANDED = 2;

        private final BudsIsland is;
        private final float d;
        private final float cx, camR, top;
        private final float dotW, compW, compH, expW, expH, expRad;
        private final int winH;

        // текущая форма
        private float w, hgt, rad, alpha, cA, eA; // cA/eA — прозрачность компактного/развёрнутого содержимого
        private ValueAnimator anim;
        private final RectF rect = new RectF();
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG),
                sub = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG),
                track = new Paint(Paint.ANTI_ALIAS_FLAG), small = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Drawable icon;
        private final Handler hh = new Handler(Looper.getMainLooper());

        IslandView(Context c, BudsIsland is, int screenW, Rect cut, float d) {
            super(c);
            this.is = is; this.d = d;
            if (cut != null && cut.width() < screenW / 2) {
                cx = cut.exactCenterX();
                camR = Math.max(cut.width(), cut.height()) / 2f;
                top = Math.max(0, cut.exactCenterY() - camR - 5 * d);
            } else {           // нет выреза — капсула по центру под самым краем
                cx = screenW / 2f; camR = 12 * d; top = 6 * d;
            }
            compH = Math.max(camR * 2 + 10 * d, 34 * d);
            dotW = camR * 2;
            compW = Math.min(screenW - 24 * d, camR * 2 + 150 * d);
            expW = Math.min(screenW - 20 * d, 380 * d);
            expH = compH + 66 * d;
            expRad = 38 * d;
            winH = (int) (top + expH + 16 * d);

            w = dotW; hgt = dotW; rad = dotW / 2; alpha = 0;

            bg.setColor(Color.BLACK);
            txt.setColor(Color.WHITE); txt.setTextSize(15 * d); txt.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            sub.setColor(0xFF9A9AA0); sub.setTextSize(12.5f * d);
            small.setColor(Color.WHITE); small.setTextSize(11 * d); small.setTextAlign(Paint.Align.CENTER);
            small.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            ring.setStyle(Paint.Style.STROKE); ring.setStrokeCap(Paint.Cap.ROUND); ring.setStrokeWidth(3.2f * d);
            track.setStyle(Paint.Style.STROKE); track.setStrokeWidth(3.2f * d); track.setColor(0xFF2A2A2E);
            icon = c.getResources().getDrawable(R.drawable.ic_buds_tile, c.getTheme()).mutate();
            setLayerType(LAYER_TYPE_HARDWARE, null);
        }

        int windowHeight() { return winH; }

        // ---------------------------------------------------- сценарий

        void play() {
            seq(new Object[][]{
                    {COMPACT, 420L, 0L},
                    {EXPANDED, 520L, 350L},
                    {COMPACT, 380L, 3600L},
                    {DOT, 340L, 1300L},
            });
        }

        void replay() { cancel(); play(); }

        private void seq(Object[][] plan) {
            long t = 0;
            for (Object[] p : plan) {
                t += (Long) p[2];
                final int to = (Integer) p[0];
                final long dur = (Long) p[1];
                hh.postDelayed(new Step(to, dur), t);
                t += dur;
            }
        }

        private final class Step implements Runnable {
            final int to; final long dur;
            Step(int to, long dur) { this.to = to; this.dur = dur; }
            @Override public void run() { morph(to, dur); }
        }

        void cancel() {
            hh.removeCallbacksAndMessages(null);
            if (anim != null) anim.cancel();
        }

        private void morph(final int to, long dur) {
            final float w0 = w, h0 = hgt, r0 = rad, a0 = alpha, c0 = cA, e0 = eA;
            final float w1, h1, r1, a1, c1, e1;
            Interpolator ip;
            switch (to) {
                case EXPANDED: w1 = expW; h1 = expH; r1 = expRad; a1 = 1; c1 = 0; e1 = 1; ip = new OvershootInterpolator(0.9f); break;
                case COMPACT:  w1 = compW; h1 = compH; r1 = compH / 2; a1 = 1; c1 = 1; e1 = 0; ip = new OvershootInterpolator(1.1f); break;
                default:       w1 = dotW; h1 = dotW; r1 = dotW / 2; a1 = 0; c1 = 0; e1 = 0; ip = new DecelerateInterpolator(1.6f);
            }
            is.setTouchable(to == EXPANDED);
            if (anim != null) anim.cancel();
            anim = ValueAnimator.ofFloat(0, 1);
            anim.setDuration(dur);
            anim.setInterpolator(ip);
            anim.addUpdateListener(v -> {
                float f = (float) v.getAnimatedValue();
                float g = Math.max(0, Math.min(1, f));        // для прозрачности без «перелёта»
                w = w0 + (w1 - w0) * f; hgt = h0 + (h1 - h0) * f; rad = r0 + (r1 - r0) * f;
                alpha = a0 + (a1 - a0) * g;
                // содержимое появляется во второй половине, исчезает в первой
                cA = c1 > c0 ? c0 + (c1 - c0) * clamp((g - .45f) / .55f) : c0 + (c1 - c0) * clamp(g / .4f);
                eA = e1 > e0 ? e0 + (e1 - e0) * clamp((g - .5f) / .5f) : e0 + (e1 - e0) * clamp(g / .35f);
                invalidate();
            });
            if (to == DOT) anim.addListener(new AnimatorListenerAdapter() {
                boolean cancelled;
                @Override public void onAnimationCancel(Animator a) { cancelled = true; }
                @Override public void onAnimationEnd(Animator a) { if (!cancelled) is.detach(); }
            });
            anim.start();
        }

        private static float clamp(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

        // ---------------------------------------------------- касания

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_UP) {
                cancel();
                if (rect.contains(e.getX(), e.getY())) is.openApp();
                morph(DOT, 300);
            }
            return true;
        }

        // ---------------------------------------------------- рисование

        @Override
        protected void onDraw(Canvas cv) {
            if (alpha <= 0.01f) return;
            rect.set(cx - w / 2, top, cx + w / 2, top + hgt);
            bg.setAlpha((int) (255 * alpha));
            cv.drawRoundRect(rect, rad, rad, bg);
            if (cA > 0.01f) drawCompact(cv, cA);
            if (eA > 0.01f) drawExpanded(cv, eA);
        }

        private int pctColor(int p, boolean charging) {
            if (charging) return 0xFF30D158;
            if (p >= 0 && p <= 15) return 0xFFFF453A;
            if (p >= 0 && p <= 30) return 0xFFFFD60A;
            return 0xFF30D158;
        }

        private int minPct() {
            int a = is.left, b = is.right;
            if (a < 0) return b; if (b < 0) return a;
            return Math.min(a, b);
        }

        private void drawCompact(Canvas cv, float a) {
            int al = (int) (255 * a);
            float cy = top + hgt / 2, s = 20 * d;
            // слева — значок наушников
            float lx = rect.left + 14 * d;
            icon.setTint(Color.WHITE);
            icon.setAlpha(al);
            icon.setBounds((int) lx, (int) (cy - s / 2), (int) (lx + s), (int) (cy + s / 2));
            icon.draw(cv);
            // справа — кольцо и процент
            int p = minPct();
            float rr = 8 * d, rx = rect.right - 14 * d - rr;
            ring(cv, rx, cy, rr, p, is.chL || is.chR, al, 2.6f * d);
            if (p >= 0) {
                Paint t = small; t.setTextAlign(Paint.Align.RIGHT); t.setTextSize(13 * d);
                t.setColor(pctColor(p, is.chL || is.chR)); t.setAlpha(al);
                cv.drawText(p + "%", rx - rr - 7 * d, cy + 4.6f * d, t);
                t.setTextAlign(Paint.Align.CENTER); t.setColor(Color.WHITE); t.setTextSize(11 * d);
            }
        }

        private void drawExpanded(Canvas cv, float a) {
            int al = (int) (255 * a);
            float rowTop = top + compH - 2 * d, rowH = hgt - compH;
            float cy = rowTop + rowH / 2;
            // значок
            float s = 40 * d, lx = rect.left + 20 * d;
            icon.setTint(Color.WHITE); icon.setAlpha(al);
            icon.setBounds((int) lx, (int) (cy - s / 2), (int) (lx + s), (int) (cy + s / 2));
            icon.draw(cv);
            // кольца справа: L, R, (кейс)
            float rr = 15 * d, gap = 44 * d;
            int n = is.box >= 0 ? 3 : 2;
            float x = rect.right - 22 * d - rr;
            float textRight = x - (n - 1) * gap - rr - 10 * d;
            if (n == 3) { ringLabeled(cv, x, cy, rr, is.box, is.chC, "Кейс", al); x -= gap; }
            ringLabeled(cv, x, cy, rr, is.right, is.chR, "R", al); x -= gap;
            ringLabeled(cv, x, cy, rr, is.left, is.chL, "L", al);
            // имя и статус
            float tx = lx + s + 14 * d, maxW = Math.max(40 * d, textRight - tx);
            txt.setAlpha(al); sub.setAlpha(al);
            cv.drawText(ellipsize(is.name, txt, maxW), tx, cy - 2 * d, txt);
            cv.drawText("Подключено", tx, cy + 15 * d, sub);
        }

        private void ringLabeled(Canvas cv, float x, float cy, float r, int p, boolean ch, String label, int al) {
            float y = cy - 6 * d;
            ring(cv, x, y, r, p, ch, al, 3.2f * d);
            small.setAlpha(al); small.setTextSize(11 * d); small.setColor(Color.WHITE); small.setAlpha(al);
            cv.drawText(p >= 0 ? String.valueOf(p) : "–", x, y + 4 * d, small);
            sub.setTextAlign(Paint.Align.CENTER); sub.setTextSize(10.5f * d); sub.setAlpha(al);
            cv.drawText(ch ? "⚡" + label : label, x, y + r + 13 * d, sub);
            sub.setTextAlign(Paint.Align.LEFT); sub.setTextSize(12.5f * d);
        }

        private void ring(Canvas cv, float x, float y, float r, int p, boolean ch, int al, float sw) {
            track.setStrokeWidth(sw); track.setAlpha(al);
            cv.drawCircle(x, y, r, track);
            if (p < 0) return;
            ring.setStrokeWidth(sw); ring.setColor(pctColor(p, ch)); ring.setAlpha(al);
            cv.drawArc(new RectF(x - r, y - r, x + r, y + r), -90, 360f * Math.min(100, p) / 100f, false, ring);
        }

        private static String ellipsize(String s, Paint p, float max) {
            if (s == null) return "";
            if (p.measureText(s) <= max) return s;
            int n = p.breakText(s, true, max - p.measureText("…"), null);
            return s.substring(0, Math.max(0, n)) + "…";
        }
    }
}
