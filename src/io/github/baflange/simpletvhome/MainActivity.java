package io.github.baflange.simpletvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.List;
import java.util.WeakHashMap;

public class MainActivity extends Activity {

    // Top-row apps, in this order of preference:
    //  1. a list saved at install time:  am start -n io.github.baflange.simpletvhome/.MainActivity --es apps "pkg1,pkg2"
    //     (--es apps auto  goes back to 2; add --es stamp <unique> so it's applied only once, see saveConfig)
    //  2. apps.txt (built in as Apps.LIST), topped up with other TV apps installed on this TV.
    // Missing apps are skipped. Topping up stops at one row of apps, but a chosen list can be any length:
    // apps wrap into rows (Settings > Apps per row), and the page scrolls once they don't all fit.
    // On the TV, hold OK on an app (or press Menu) to move or remove it; Settings > Add app adds one. That saves list 1.
    // Settings shortcuts can join the Settings icon at the bottom right, off until picked the same way:
    //   --es quick "bluetooth,sound,picture"     (--es quick none  takes them away again)
    private static final String PREFS = "config";
    private static final List<String> NOT_APPS = Arrays.asList("com.android.vending", "com.android.tv.settings",
            "com.android.tv", "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx",
            "com.google.android.leanbacklauncher");
    private static final String PLAY_STORE = "com.android.vending";
    private static final String SETTINGS = "settings";
    private static final String[] EDIT_ITEMS = {"Move", "Replace", "Remove from home", "Uninstall"};
    private static final String[] EDIT_ITEMS_SYSTEM = {"Move", "Replace", "Remove from home", "App info"};
    // Tiles have a fixed width of 194 px on the 1920 px design grid and sit in the
    // bottom left corner. Apps per row is a setting (Settings > Apps per row, PER_ROW_MIN..MAX, saved as "per_row"; 3 until
    // picked). Only perRow() sets the row length: x, rows and Up/Down all follow it. Seven tiles still end well left of the icon.
    private static final int PER_ROW_DEFAULT = 3, PER_ROW_MIN = 2, PER_ROW_MAX = 7, TILE_W = 194;
    private int perRow = -1; // Apps per row in use (also while the menu previews one); -1 = not read yet

    private int perRow() {
        if (perRow < 1)
            perRow = Math.max(PER_ROW_MIN, Math.min(PER_ROW_MAX, getSharedPreferences(PREFS, 0).getInt("per_row", PER_ROW_DEFAULT)));
        return perRow;
    }

    // Settings shortcuts: name, label, icon. Tile keys are QUICK + name.
    private static final String QUICK = "quick:";
    private static final String[] QUICK_NAMES = {"bluetooth", "sound", "picture"};
    private static final String[] QUICK_LABELS = {"Bluetooth", "Sound", "Picture"};
    private static final int[][] QUICK_ICONS = {LineIcon.BLUETOOTH, LineIcon.SOUND, LineIcon.PICTURE};

    // The clock override, saved on the TV: "12" or "24"; absent follows the TV.
    private static final String PREF_CLOCK = "clock";
    // TCL keeps its own Picture and Sound panels in a service; Android's Sound page is missing there.
    private static final String TCL_SHOW_WINDOW = "com.tcl.settings.SHOW_WINDOW";

    // Layout is designed at 1920x1080 and scaled to the TV's real UI size by px().
    private static final int MARGIN = 96, GAP = 36, RADIUS = 26;
    // The focus lift: the tile grows, rises, brightens and rounds off a little, then settles.
    // Long enough to read as motion, short enough that a run of d-pad presses never feels behind.
    private static final int FOCUS_MS = 180, RADIUS_UP = 10;
    private static final Interpolator FOCUS_EASE = new DecelerateInterpolator(1.8f);
    // The row step follows the tile height: tile + ROW_GAP, the same 36 as the horizontal GAP.
    private static final int ROW1_TOP = 604, ROW_GAP = 36;
    private static final int GRID_MIN_TOP = 48, EDGE = 64; /* EDGE = distance of the apps (left, bottom) and of the top bar (right) to the screen edge; GRID_MIN_TOP was 400, the grid now grows upwards from the bottom left corner until here */ // More rows push the grid up, but not into the clock
    private static final int ICON_TOP = 76, ICON_BUTTON = 72, ICON_SIZE = 40, ICON_GAP = 12; // level with the date

    // The Settings tile opens a small menu: the home screen's own settings, then the TV's.
    // "Add app" and "Play Store" live here instead of on the home screen itself; "Tile style" picks how the app tiles look.
    private static final String[] SETTINGS_ITEMS = {"Wallpaper", "Tile style", "Apps per row", "Remote buttons", "TV settings", "Add app", "Play Store"};
    // "Remote buttons" is only shown when this separate add-on app is installed; it opens the add-on's own settings screen.
    // The key filter lives there, so this home screen itself needs no access to the remote's keys.
    private static final String REMOTE_PKG = "local.beamer.remotebuttons";

    // The tile styles. Original = the apps' own banners. Muted / Black & white = the banners, filtered so they
    // stop clashing; full colour returns on focus. Text on glass = a glass tile with only the name; Text only = no tile at all,
    // only the name (glass appears on focus). Both text styles are half as tall as an app tile and sit closer together.
    // The saved choice is the NAME.
    private static final String[] TILE_STYLES = {"Original", "Muted", "Black & white", "Text on glass", "Text only"};
    private static final int STYLE_ORIGINAL = 0, STYLE_MUTED = 1, STYLE_MONO = 2, STYLE_TEXT = 3, STYLE_PLAIN = 4;
    // Text styles: the gaps between the (half-height) blocks, replacing GAP and ROW_GAP (36 each).
    private static final int TEXT_GAP = 16, TEXT_ROW_GAP = 10;
    private int colGap; // Distance between columns, GAP or TEXT_GAP; set by buildTiles
    private int tileStyle = -1; // The style in use (also while the menu previews one); -1 = not read yet
    // Wallpapers: ten photos (Wikimedia Commons; authors and licences in WALLPAPER_CREDITS.txt), saved by NAME, so names must
    // stay unique. A saved name that is no longer listed falls back to the first, "Mountains".
    // The photos in res/drawable-nodpi are BAKED by tools/BakeWallpapers.java from wallpaper-originals/: cropped to 1920x1080,
    // darkened at the top (clock), the bottom (apps) and a little overall, and saved as WebP, so the TV only decodes them.
    // Each also has a 96 px square for the wallpaper list: wallpaper_*_thumb.webp.
    private static final String[] WALLPAPER_NAMES = {"Mountains", "Misty Trees", "Misty Valley", "Snow Peaks", "Northern Lights",
            "Waterfall", "Tokyo", "Amsterdam", "Nyhavn", "Honfleur"};
    private static final String[] PHOTO_RES = {"wallpaper_nature", "wallpaper_mist_trees", "wallpaper_mist_valley",
            "wallpaper_snow", "wallpaper_aurora", "wallpaper_waterfall", "wallpaper_tokyo", "wallpaper_amsterdam",
            "wallpaper_nyhavn", "wallpaper_honfleur"};
    // Per photo, colours for its list picture if the thumbnail can't be read: a gradient top to bottom, then a glow from
    // the top left and one from the bottom right.
    private static final int[][] PHOTO_COLOURS = {
        {0xFF2C5C7C, 0xFF1C4A5C, 0xFF0C2A30, 0x6070B0D8, 0x604C8C6C}, // Mountains
        {0xFF6A5230, 0xFFB08040, 0xFF201810, 0xFFFFE0A0, 0xFF3A2C18}, // Misty Trees
        {0xFF5A7A3C, 0xFFE06830, 0xFF304028, 0xFFE8E4E0, 0xFF5A7A3C}, // Misty Valley
        {0xFF8FA6C8, 0xFFA8BCD8, 0xFF405070, 0xFFFFFFFF, 0xFF5870A0}, // Snow Peaks
        {0xFF103A40, 0xFF1A5A70, 0xFF050E14, 0xFF40E0A0, 0xFF0A1830}, // Northern Lights
        {0xFF3A5A48, 0xFF6A8A3C, 0xFF0C1814, 0xFFE8E0D0, 0xFF3A2C20}, // Waterfall
        {0xFF1C3A44, 0xFF1C3A44, 0xFF0A1418, 0xFFFFB030, 0xFF0A1418}, // Tokyo
        {0xFF4A4038, 0xFF4A4038, 0xFF2A3038, 0xFFC86040, 0xFF2A3038}, // Amsterdam
        {0xFF4A6A90, 0xFF4A6A90, 0xFF2A3A50, 0xFFFFA040, 0xFFD04838}, // Nyhavn
        {0xFF5080C0, 0xFF5080C0, 0xFF3A5A88, 0xFFF0F4FF, 0xFF6A5A48}, // Honfleur
    };
    // A dark "Dusk" gradient: the background when a photo can't be drawn.
    private static final int[] DUSK = {0xFF15100F, 0xFF0A0809, 0xFF050405, 0x36FF8048, 0x2EAA3C8C};
    private float scale = 1f;

    private static Typeface regular, semibold;

    private FrameLayout root; // the page, inside a vertical scroller
    private int tileW, tileH, gridTop; // app grid geometry, set by buildTiles
    private int rowStep;   // Distance between rows, tile height + ROW_GAP
    private LinearLayout iconBar; // Bottom right: the Settings icon and any settings shortcuts (the clock is on its own, top right)
    private FrameLayout.LayoutParams iconBarLp;
    private float tileK = 1f; // How much smaller than a 258 px reference tile the tiles are (1 = same size)
    private final List<View> tiles = new ArrayList<>();
    private final Map<View, ValueAnimator> lifts = new WeakHashMap<>(); // a running focus fade per tile
    private boolean dirty = true;
    private java.util.Locale shownLocale; // the TV's language (and region) when the page was built
    private String focusedKey;
    private final List<String> rowApps = new ArrayList<>(); // the top row as shown, in order
    private String movingKey;   // the app being moved with left/right, if any
    private TextView moveHint;  // shown above the apps while moving
    private boolean longPressed; // OK was held: swallow the key-up so the app doesn't open
    private boolean backHeld; // Back was held and opened Google TV's home screen: swallow the key-up
    // The tile OK went down on. An app that closes on OK's key-down (Netflix's "Exit") hands the matching
    // key-up to the tile still focused here, so a key-up only opens the app it went down on.
    private String okDownKey;

    // Apps installed, removed or updated. This often lands once Home is already back in front (an app
    // uninstalled from the menu is gone only after Android's prompt has closed), so catch up then.
    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            dirty = true;
            refreshApps();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        saveConfig(getIntent());
        loadFonts();
        scale = getResources().getDisplayMetrics().widthPixels / 1920f;
        showWallpaper(wallpaper());

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroller.setClipChildren(false);
        root = new FrameLayout(this);
        root.setClipChildren(false);
        scroller.addView(root, new FrameLayout.LayoutParams(-1, -2));

        LinearLayout clock = new LinearLayout(this);
        clock.setOrientation(LinearLayout.VERTICAL);
        clock.setGravity(Gravity.END); // Date and time right-aligned
        shownLocale = java.util.Locale.getDefault();
        String day = Strings.datePattern();
        TextClock date = clock(day, day, px(26), 0xA8FFFFFF, regular); // was 30
        // The clock follows the TV unless --es clock 12 or 24 says otherwise.
        String clockPref = prefs().getString(PREF_CLOCK, null);
        TextClock time = clock("24".equals(clockPref) ? "H:mm" : "h:mm", "12".equals(clockPref) ? "h:mm" : "H:mm",
                px(88), Color.WHITE, semibold); // was 176
        time.setLetterSpacing(-0.028f);
        time.setFontFeatureSettings("tnum");
        time.setIncludeFontPadding(false);
        // Wrap, not the default match_parent: the column must be as wide as the date, not just the time,
        // or "0:00" to "9:59" (one digit less) cuts a long date off.
        clock.addView(date, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.topMargin = px(-8); // Pull the digits up under the date (was -20 for the bigger digits)
        clock.addView(time, tp);
        // The clock sits top right on its own (48 px from the top, EDGE from the right). buildTiles puts the Settings icon
        // (and any settings shortcuts) in a bar at the bottom right, level with the bottom row of apps. That bar is added to
        // the page by buildTiles, after the apps, so the first app keeps first focus.
        FrameLayout.LayoutParams clockLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        clockLp.topMargin = px(EDGE - 16);
        clockLp.rightMargin = px(EDGE);
        root.addView(clock, clockLp);
        iconBar = new LinearLayout(this);
        iconBar.setOrientation(LinearLayout.HORIZONTAL);
        iconBar.setGravity(Gravity.CENTER_VERTICAL);
        iconBar.setClipChildren(false);
        iconBar.setClipToPadding(false);
        iconBarLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        // The last icon's glyph lines up with the clock's right edge (its focus circle sticks out past it).
        iconBarLp.rightMargin = px(EDGE) - (px(ICON_BUTTON) - px(ICON_SIZE)) / 2;

        moveHint = text(px(24), 0xA8FFFFFF, regular);
        moveHint.setText(Strings.t("Use the arrows to move it, then press OK"));
        moveHint.setVisibility(View.GONE);
        root.addView(moveHint, at(px(EDGE), px(ROW1_TOP - 64), -2, -2));

        setContentView(scroller);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(packagesChanged, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(packagesChanged, f);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packagesChanged);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // A clock change needs the whole page rebuilt; a new app list only needs the tiles.
        boolean clock = intent != null && intent.hasExtra("clock");
        if (saveConfig(intent)) {
            if (clock) recreate();
            else buildTiles();
        }
    }

    // Saves an app list passed with --es apps "...", shortcuts passed with --es quick "...", and a
    // clock override passed with --es clock 12|24|auto. Returns true if it changed.
    // When Android kills the home screen in the background, it can start it again with the same old
    // intent; the stamp install.sh sends stops that from undoing changes made on the TV since.
    private boolean saveConfig(Intent intent) {
        String apps = intent == null ? null : intent.getStringExtra("apps");
        String quick = intent == null ? null : intent.getStringExtra("quick");
        String clock = intent == null ? null : intent.getStringExtra("clock");
        if (apps == null && quick == null && clock == null) return false;
        SharedPreferences prefs = getSharedPreferences(PREFS, 0);
        String stamp = intent.getStringExtra("stamp");
        if (stamp != null && stamp.equals(prefs.getString("stamp", null))) return false;
        SharedPreferences.Editor e = prefs.edit();
        if (stamp != null) e.putString("stamp", stamp);
        if (apps != null) {
            apps = apps.trim();
            if (apps.isEmpty() || apps.equalsIgnoreCase("auto")) e.remove("apps");
            else e.putString("apps", apps);
        }
        if (quick != null) {
            quick = quick.trim();
            if (quick.isEmpty() || quick.equalsIgnoreCase("none")) e.remove("quick");
            else e.putString("quick", quick);
        }
        if (clock != null) {
            clock = clock.trim();
            if (clock.isEmpty() || clock.equalsIgnoreCase("auto")) e.remove(PREF_CLOCK);
            else e.putString(PREF_CLOCK, clock);
        }
        e.apply();
        dirty = true;
        return true;
    }

    private List<String> appList(PackageManager pm) {
        List<String> out = new ArrayList<>();
        String saved = getSharedPreferences(PREFS, 0).getString("apps", null);
        String[] wanted = saved != null ? saved.split("[,\\s]+") : Apps.LIST;
        for (String p : wanted) if (!p.isEmpty() && !out.contains(p) && launchIntent(pm, p) != null) out.add(p);
        if (saved == null) {
            // Top up with other TV apps people installed (or updated), skipping launchers and system tools.
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                if (out.size() >= perRow()) break;
                String p = ri.activityInfo.packageName;
                int flags = ri.activityInfo.applicationInfo.flags;
                boolean stockSystem = (flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
                if (stockSystem || out.contains(p) || NOT_APPS.contains(p) || p.equals(getPackageName())) continue;
                out.add(p);
            }
        }
        return out;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Rebuild only when apps were installed, removed or updated.
        if (dirty) {
            dirty = false;
            buildTiles();
        }
        View f = getCurrentFocus();
        if (f == null && !tiles.isEmpty()) tiles.get(0).requestFocus();
        HomeButton.onHomeResumed(this); // back on this home screen: Home comes here again
        HomeButton.setHomeVisible(true); // Lets the Home button service see whether this home is in front
        // Only counted as opened once it has stayed up a moment, so a crash right after opening still
        // counts as a failed launch.
        getWindow().getDecorView().postDelayed(homeOk, 1500);
    }

    private final Runnable homeOk = () -> HomeButton.onHomeOk(this);

    @Override
    protected void onPause() {
        super.onPause();
        // Left normally (an app or Settings opened), so it didn't crash.
        getWindow().getDecorView().removeCallbacks(homeOk);
        HomeButton.onHomeOk(this);
        HomeButton.setHomeVisible(false); // see onResume
    }

    // Back from a menu or a window on top (like the uninstall prompt): catch up on apps that changed.
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) refreshApps();
        else okDownKey = null; // an OK still held finishes in the window that took over
    }

    // Rebuilds the tiles if apps changed, but only while Home is in front with no menu open and no
    // app being moved; otherwise the next onResume, focus change or end of the move does it.
    private void refreshApps() {
        if (!dirty || !hasWindowFocus() || movingKey != null) return;
        dirty = false;
        buildTiles();
    }

    // The TV's language changed: start over so the labels and the date follow it.
    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (!java.util.Locale.getDefault().equals(shownLocale)) recreate();
    }

    @Override
    public void onBackPressed() { /* home screen: nothing to go back to */ }

    // Holding Back opens Google TV's own home screen, where it stays on (see HomeButton). Spotted on a
    // key repeat, or when Back is let go if the remote sends none.
    @Override
    public boolean onKeyLongPress(int code, KeyEvent ev) {
        if (code == KeyEvent.KEYCODE_BACK && HomeButton.openGoogleTv(this)) {
            backHeld = true;
            return true;
        }
        return super.onKeyLongPress(code, ev);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent ev) {
        if (code == KeyEvent.KEYCODE_BACK) {
            boolean held = backHeld;
            backHeld = false;
            if (held) return true;
            if (ev.isTracking() && !ev.isCanceled()
                    && ev.getEventTime() - ev.getDownTime() >= ViewConfiguration.getLongPressTimeout()
                    && HomeButton.openGoogleTv(this)) return true;
        }
        return super.onKeyUp(code, ev);
    }

    // Static gradient, drawn by the window: no extra view, no animation. It's painted once into a
    // screen-sized picture: as three gradients, low-end GPUs paint it again on every frame of a focus
    // move, and those frames take two to six times as long. On Android 8+ the picture moves to
    // the GPU, so the home screen uses no more memory than before; if anything fails, the gradients stay.
    private Drawable background(int[] c) {
        GradientDrawable base = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {c[0], c[1], c[2]});
        GradientDrawable top = radial(c[3], 0.08f, -0.12f, px(1250));
        GradientDrawable bottom = radial(c[4], 1.0f, 1.1f, px(1000));
        for (GradientDrawable g : new GradientDrawable[] {base, top, bottom}) g.setDither(true);
        Drawable layers = new LayerDrawable(new Drawable[] {base, top, bottom});
        int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
        try {
            Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            layers.setBounds(0, 0, w, h);
            layers.draw(new Canvas(bm));
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                Bitmap hw = bm.copy(Bitmap.Config.HARDWARE, false);
                if (hw != null) {
                    bm.recycle();
                    bm = hw;
                }
            }
            return new BitmapDrawable(getResources(), bm);
        } catch (Throwable t) {
            return layers;
        }
    }

    // The saved wallpaper, as an index into WALLPAPER_NAMES (Mountains if none is saved).
    private int wallpaper() {
        String saved = getSharedPreferences(PREFS, 0).getString("wallpaper", null);
        return Math.max(0, Arrays.asList(WALLPAPER_NAMES).indexOf(saved));
    }

    private Drawable photoBg; // The photo, drawn once and kept while it is the wallpaper
    private int photoIndex = -1; // and which photo that is

    // Decoding a full-size photo can take a noticeable moment on a slow TV. Done in the focus listener of the wallpaper list,
    // that froze the screen at every step, and presses that queued up meanwhile moved the highlight past the wanted entry.
    // So the list's preview only starts the decode on a worker thread once the highlight has rested for 200 ms, and a result
    // that arrives after the highlight moved on is dropped. Saving (OK), Back and starting up still use showWallpaper() below.
    private volatile int wallpaperRequest; // bumped on every change; an async result is only used while it is still the latest
    private android.os.Handler photoWorker; // a thread of its own, made on first use
    private final android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    private void previewWallpaper(final int i) {
        final int req = ++wallpaperRequest;
        if (photoBg != null && photoIndex == i) { // The photo already on screen: instant
            showWallpaper(i);
            return;
        }
        if (photoWorker == null) {
            android.os.HandlerThread t = new android.os.HandlerThread("wallpaper");
            t.start();
            photoWorker = new android.os.Handler(t.getLooper());
        }
        photoWorker.removeCallbacksAndMessages(null); // the highlight moved on: forget the one still waiting
        photoWorker.postDelayed(() -> {
            if (req != wallpaperRequest) return;
            final Drawable d = photoBackground(i);
            uiHandler.post(() -> {
                if (req != wallpaperRequest) return;
                photoBg = null; // let go of the previous photo first, so two never sit in memory together
                photoBg = d;
                photoIndex = i;
                getWindow().setBackgroundDrawable(d != null ? d : background(DUSK));
            });
        }, 200);
    }

    private void showWallpaper(int i) {
        wallpaperRequest++; // Whatever a worker is still drawing is out of date now
        if (photoBg == null || photoIndex != i) {
            photoBg = null; // Let go of the previous photo first, so two never sit in memory together
            photoBg = photoBackground(i);
            photoIndex = i;
        }
        // If the photo can't be drawn for any reason, fall back to the Dusk gradient.
        getWindow().setBackgroundDrawable(photoBg != null ? photoBg : background(DUSK));
    }

    // The baked photo (see PHOTO_RES): decoded straight into GPU memory on Android 8+, so a focus move costs nothing
    // extra and the home screen uses no more memory than with a gradient. Null if anything fails.
    private Drawable photoBackground(int p) {
        try {
            final long t0 = android.os.SystemClock.uptimeMillis(); // Timed, to see which photo is slow (logcat TvHomePhoto)
            int id = getResources().getIdentifier(PHOTO_RES[p], "drawable", getPackageName());
            if (id == 0) {
                android.util.Log.w("TvHomePhoto", PHOTO_RES[p] + ": resource not found");
                return null;
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inScaled = false; // nodpi: take the pixels as they are
            boolean gpu = android.os.Build.VERSION.SDK_INT >= 26;
            if (gpu) o.inPreferredConfig = Bitmap.Config.HARDWARE;
            Bitmap bm = BitmapFactory.decodeResource(getResources(), id, o);
            if (bm == null && gpu) { // no GPU copy possible right now: an ordinary bitmap does the same, just in normal memory
                o.inPreferredConfig = Bitmap.Config.ARGB_8888;
                bm = BitmapFactory.decodeResource(getResources(), id, o);
            }
            if (bm == null) {
                android.util.Log.w("TvHomePhoto", PHOTO_RES[p] + ": could not be decoded");
                return null;
            }
            android.util.Log.i("TvHomePhoto", PHOTO_RES[p] + ": decoded in " + (android.os.SystemClock.uptimeMillis() - t0) + " ms"
                    + (bm.getConfig() == Bitmap.Config.HARDWARE ? " (GPU)" : ""));
            return new BitmapDrawable(getResources(), bm);
        } catch (Throwable t) {
            android.util.Log.w("TvHomePhoto", PHOTO_RES[p] + ": failed", t);
            return null;
        }
    }

    // The wallpapers, each with a small picture of it. Moving through the list shows each one
    // behind the menu; OK keeps it, Back goes back to the one before.
    private void showWallpapers() {
        final int size = px(40);
        // The same small glass menu as the Settings menu and Add app. Each name has a rounded 40 px picture of the photo.
        dropdown(tile(SETTINGS), WALLPAPER_NAMES, wallpaper(), this::previewWallpaper, pos -> wallpaperThumb(pos, size), size, which -> {
            getSharedPreferences(PREFS, 0).edit().putString("wallpaper", WALLPAPER_NAMES[which]).apply();
            showWallpaper(which);
        }, () -> backToSettings("Wallpaper"));
    }

    private final Drawable[] wallpaperThumbs = new Drawable[WALLPAPER_NAMES.length]; // made on first use, a few KB each

    // The picker's picture of wallpaper p: a rounded square of the photo (its small ready-made thumbnail). If that can't be
    // read, its PHOTO_COLOURS as a gradient instead, glows made stronger so the dark ones can be told apart.
    private Drawable wallpaperThumb(int p, int size) {
        if (wallpaperThumbs[p] != null) return wallpaperThumbs[p];
        float corner = px(8);
        int edge = Math.max(1, px(2));
        Drawable d = null;
        Bitmap photo = photoThumb(p, size);
        if (photo != null) {
            Bitmap bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(bm);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            paint.setShader(new BitmapShader(photo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            cv.drawRoundRect(new RectF(0, 0, size, size), corner, corner, paint);
            Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(edge);
            line.setColor(0x26FFFFFF);
            cv.drawRoundRect(new RectF(edge / 2f, edge / 2f, size - edge / 2f, size - edge / 2f), corner, corner, line);
            photo.recycle();
            d = new BitmapDrawable(getResources(), bm);
        }
        if (d == null) {
            int[] c = PHOTO_COLOURS[p];
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[] {glow(c[3], c[0]), c[1], glow(c[4], c[0])});
            g.setCornerRadius(corner);
            g.setStroke(edge, 0x26FFFFFF);
            g.setSize(size, size);
            d = g;
        }
        wallpaperThumbs[p] = d;
        return d;
    }

    // The list picture of photo p, size x size px: the small ready-made square wallpaper_*_thumb.webp (96 px, the
    // middle of the untouched photo, made by tools/BakeWallpapers.java), scaled down. Null if anything fails.
    private Bitmap photoThumb(int p, int size) {
        try {
            int id = getResources().getIdentifier(PHOTO_RES[p] + "_thumb", "drawable", getPackageName());
            if (id == 0) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inScaled = false;
            Bitmap src = BitmapFactory.decodeResource(getResources(), id, o);
            if (src == null) return null;
            Bitmap out = Bitmap.createScaledBitmap(src, size, size, true);
            if (out != src) src.recycle();
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    // A glow colour made solid over the base, three times as strong, so a small dot shows it.
    private static int glow(int glow, int base) {
        float a = Math.min(1f, 3 * (glow >>> 24) / 255f);
        int r = Math.round(Color.red(base) + (Color.red(glow) - Color.red(base)) * a);
        int g = Math.round(Color.green(base) + (Color.green(glow) - Color.green(base)) * a);
        int b = Math.round(Color.blue(base) + (Color.blue(glow) - Color.blue(base)) * a);
        return Color.rgb(r, g, b);
    }

    private static GradientDrawable radial(int color, float cx, float cy, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setColors(new int[] {color, color & 0x00FFFFFF});
        g.setGradientCenter(cx, cy);
        g.setGradientRadius(r);
        return g;
    }

    private void buildTiles() {
        // While tiles are swapped out, removing the focused one would hand focus down the row (and
        // overwrite focusedKey), so hold focus off until the new tiles are in.
        String want = focusedKey;
        int wantAt = rowApps.indexOf(want); // where it was, in case the app is gone now
        root.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        for (View t : tiles) {
            View wrap = (View) t.getParent();
            ((ViewGroup) wrap.getParent()).removeView(wrap);
        }
        tiles.clear();
        root.removeView(iconBar); // Re-added at the end, so it comes after the apps in focus order
        rowApps.clear();
        movingKey = null;
        moveHint.setVisibility(View.GONE);

        PackageManager pm = getPackageManager();
        final int style = tileStyle(); // How the app tiles look (see TILE_STYLES)
        int m = px(MARGIN), g = px(GAP);
        int w = px(TILE_W); // Fixed tile width instead of filling the screen width
        // The two text styles have no banner to show, so their blocks are half as tall and sit closer together.
        final boolean textStyle = style == STYLE_TEXT || style == STYLE_PLAIN;
        int h = textStyle ? w * 9 / 32 : w * 9 / 16;
        tileW = w;
        tileH = h;
        colGap = px(textStyle ? TEXT_GAP : GAP);
        rowStep = h + px(textStyle ? TEXT_ROW_GAP : ROW_GAP);
        tileK = Math.min(1f, (float) w / px(258));

        List<String> apps = appList(pm);
        // Apps fill rows of perRow(). With more rows the grid starts higher, so up to two rows of apps and
        // the bottom row still fit on screen; past that the page scrolls.
        int launchable = 0;
        for (String pkg : apps) if (launchIntent(pm, pkg) != null) launchable++;
        int rows = Math.max(1, (launchable + perRow() - 1) / perRow());
        // The apps sit in the bottom left corner: the last row ends EDGE above the bottom of the
        // screen, and more rows grow upwards (up to GRID_MIN_TOP; past that the page scrolls).
        int screenH = getResources().getDisplayMetrics().heightPixels;
        gridTop = Math.max(px(GRID_MIN_TOP), screenH - px(EDGE) - h - (rows - 1) * rowStep);
        int lastRowTop = gridTop + (rows - 1) * rowStep;
        ((FrameLayout.LayoutParams) moveHint.getLayoutParams()).topMargin = gridTop - px(64);
        root.setMinimumHeight(lastRowTop + h + px(EDGE));

        for (String pkg : apps) {
            Intent li = launchIntent(pm, pkg);
            if (li == null) continue;
            Drawable banner = null;
            try {
                banner = pm.getActivityBanner(li);
                if (banner == null) banner = pm.getApplicationBanner(pkg);
            } catch (Exception ignored) {}
            // The two text styles ignore the banners: just the name, no icon
            if (textStyle) banner = null;
            String label = pkg;
            try { label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); } catch (Exception ignored) {}
            // Apps without a TV banner get a plain tile with their normal icon (the text styles have no icon at all).
            Drawable icon = null;
            if (banner == null && !textStyle)
                try { icon = pm.getApplicationIcon(pkg); } catch (Exception ignored) {}
            int i = rowApps.size();
            rowApps.add(pkg);
            addTile(root, pkg, label, banner, null, icon, gridX(i), gridY(i), w, h);
        }

        // Settings is a plain icon in a bar at the bottom right, level with the bottom row of apps, followed by any settings
        // shortcuts picked with --es quick.
        addIcon(SETTINGS, Strings.t("Settings"), new LineIcon(LineIcon.SETTINGS), 0);
        for (int q : quickList())
            addIcon(QUICK + QUICK_NAMES[q], Strings.t(QUICK_LABELS[q]), new LineIcon(QUICK_ICONS[q]), 0);
        // The bar goes in last, so the first app keeps first focus; Right from the last app of a row reaches the icons.
        // Its centre is the centre of the bottom row of apps (the bottom row ends EDGE above the screen's bottom edge).
        iconBarLp.bottomMargin = px(EDGE) + (h - px(ICON_BUTTON)) / 2;
        root.addView(iconBar, iconBarLp);

        root.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        // An app that was uninstalled hands focus to the one now in its place, as Remove does.
        if (wantAt >= 0 && !rowApps.contains(want))
            want = rowApps.isEmpty() ? SETTINGS : rowApps.get(Math.min(wantAt, rowApps.size() - 1)); // No Add tile any more
        View restore = null;
        for (View t : tiles) if (t.getTag().equals(want)) restore = t;
        if (restore != null) restore.requestFocus();
    }

    private void addTile(ViewGroup parent, final String key, String label, Drawable banner, int[] lineIcon, Drawable appIcon,
                         int x, int y, int w, int h) {
        FrameLayout card = new FrameLayout(this);
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(RADIUS));
        final int st = tileStyle(); // see TILE_STYLES
        final boolean textOnly = banner == null && (st == STYLE_TEXT || st == STYLE_PLAIN); // just the name, no icon
        final boolean plain = banner == null && st == STYLE_PLAIN; // and no tile until it is focused
        // In the filtered styles the banner is drawn through a colour matrix until the tile is focused.
        final float[] rest = banner != null ? restLook(st) : null;
        final ImageView bannerView;
        if (banner != null) {
            bg.setColor(0xFF1E1A1C);
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(fit(banner, w, h));
            if (rest != null) iv.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(rest)));
            bannerView = rest != null ? iv : null;
            card.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            bannerView = null;
            // Quiet "glass" tile: icon above label.
            // The text styles have no icon: just the name, in a glass tile (Text on glass) or on nothing (Text only,
            // where the glass only shows while the tile is focused).
            bg.setColor(plain ? 0x00FFFFFF : 0x13FFFFFF);
            if (!plain) bg.setStroke(Math.max(1, px(2)), 0x17FFFFFF);
            LinearLayout inner = new LinearLayout(this);
            inner.setOrientation(LinearLayout.VERTICAL);
            inner.setGravity(Gravity.CENTER);
            // The inside of a glass tile shrinks with the tile (but keeps the text readable).
            float ki = Math.max(tileK, 0.7f), kt = Math.max(tileK, 0.8f);
            int size = Math.round((lineIcon != null ? px(44) : px(64)) * ki);
            if (!textOnly) {
                ImageView iv = new ImageView(this);
                if (lineIcon != null) iv.setImageDrawable(new LineIcon(lineIcon));
                else if (appIcon != null) iv.setImageDrawable(fit(appIcon, size, size));
                inner.addView(iv, new LinearLayout.LayoutParams(size, size));
            }
            TextView t = text(Math.round(px(textOnly ? 32 : 24) * kt), textOnly ? 0xF2FFFFFF : 0xE0FFFFFF, textOnly ? semibold : regular);
            t.setText(label);
            t.setSingleLine(true); // the text blocks are half as tall now: one line, a long name is cut with …
            if (textOnly) t.setShadowLayer(px(6), 0, px(2), 0xB0000000); // readable on a bright part of a photo too
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setGravity(Gravity.CENTER);
            t.setPadding(Math.round(px(16) * tileK), 0, Math.round(px(16) * tileK), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = textOnly ? 0 : Math.round(px(12) * tileK);
            inner.addView(t, lp);
            card.addView(inner, new FrameLayout.LayoutParams(-1, -1));
        }
        card.setBackground(bg);
        card.setClipToOutline(true);
        card.setFocusable(true);
        card.setTag(key);

        final boolean glass = banner == null;
        card.setOnFocusChangeListener((v, has) -> {
            if (has) focusedKey = key;
            lift(v, bg, has, glass ? (plain ? 0x00FFFFFF : 0x13FFFFFF) : 0xFF1E1A1C, glass ? 0x38FFFFFF : 0xFF1E1A1C,
                    px(RADIUS), px(RADIUS + RADIUS_UP));
            if (bannerView != null) fadeLook(bannerView, rest, has);
        });
        final boolean editable = rowApps.contains(key);
        card.setOnClickListener(v -> open(key));
        if (editable) card.setOnLongClickListener(v -> { showEditMenu(key); return true; });
        // What a long OK (or Menu) on this tile opens: the app's menu (move, replace, remove).
        final Runnable menu = editable ? () -> showEditMenu(key) : null;
        card.setOnKeyListener((v, code, ev) -> {
            boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                    || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
            boolean down = ev.getAction() == KeyEvent.ACTION_DOWN;
            if (key.equals(movingKey)) return moveKey(key, code, ok, down);
            if (menu != null && code == KeyEvent.KEYCODE_MENU) {
                if (!down) menu.run();
                return true;
            }
            if (!ok) return false;
            if (down) {
                if (ev.getRepeatCount() == 0) {
                    longPressed = false;
                    okDownKey = key;
                } else if (menu != null && !longPressed && (ev.isLongPress()
                        || ev.getEventTime() - ev.getDownTime() >= ViewConfiguration.getLongPressTimeout())) {
                    longPressed = true;
                    menu.run();
                }
            } else {
                // Only the key-up of an OK that went down on this tile opens the app, and not a cancelled
                // one (focus left while OK was held). View does the same for a click with isPressed().
                boolean pressed = key.equals(okDownKey) && !ev.isCanceled();
                okDownKey = null;
                if (pressed && !longPressed) open(key);
            }
            return true;
        });

        FrameLayout wrap = new FrameLayout(this);
        wrap.setClipChildren(false);
        wrap.addView(card, new FrameLayout.LayoutParams(w, h));
        parent.addView(wrap, at(x, y, w, -2));
        tiles.add(card);
    }

    // The focus lift: the view grows and rises while its background fades and rounds off. A new
    // change cancels the fade the view was running, so a fast run of d-pad presses only ever shows
    // the tile in front and the one it just left mid-move.
    private void lift(View v, GradientDrawable bg, boolean has, int restColor, int focusColor,
                      int restRadius, int focusRadius) {
        v.animate().cancel();
        v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).translationZ(has ? px(26) : 0)
                .setDuration(FOCUS_MS).setInterpolator(FOCUS_EASE).withLayer().start();
        tween(v, bg, has, restColor, focusColor, restRadius, focusRadius);
    }

    // Just the background part: colour and corner radius, with no view animation. Used for the
    // dropdown rows, whose text already changes colour on focus.
    private void tween(View v, GradientDrawable bg, boolean has, int restColor, int focusColor,
                       int restRadius, int focusRadius) {
        if (bg == null) return;
        ValueAnimator old = lifts.remove(v);
        if (old != null) old.cancel();
        final int from = has ? restColor : focusColor, to = has ? focusColor : restColor;
        final int r0 = has ? restRadius : focusRadius, r1 = has ? focusRadius : restRadius;
        if (from == to && r0 == r1) return;
        // Tweened by hand so no objects are made per frame: this runs on TVs with 2 GB of RAM.
        final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(FOCUS_MS);
        a.setInterpolator(FOCUS_EASE);
        a.addUpdateListener(an -> {
            float t = (float) an.getAnimatedValue();
            if (from != to) bg.setColor(blend(from, to, t));
            if (r0 != r1) bg.setCornerRadius(r0 + (r1 - r0) * t);
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator an) { if (lifts.get(v) == a) lifts.remove(v); }
        });
        lifts.put(v, a);
        a.start();
    }

    private static int blend(int from, int to, float t) {
        return (channel(from, to, 24, t) << 24) | (channel(from, to, 16, t) << 16)
                | (channel(from, to, 8, t) << 8) | channel(from, to, 0, t);
    }

    private static int channel(int from, int to, int shift, float t) {
        int a = (from >>> shift) & 0xFF, b = (to >>> shift) & 0xFF;
        return Math.round(a + (b - a) * t);
    }

    // Tile styles ---------------------------------------------------------------------------------------

    // The style in use: the saved one, unless the menu is previewing another.
    private int tileStyle() {
        if (tileStyle < 0) {
            SharedPreferences p = getSharedPreferences(PREFS, 0);
            String name = p.getString("tile_look", null);
            int i = name == null ? -1 : Arrays.asList(TILE_STYLES).indexOf(name);
            if (name == null) {
                // The first builds saved a number (0 Original, 1 Glass, 2 Muted, 3 Black & white, 4 Duotone, 5 Text on
                // glass, 6 Text only); the two removed styles become Original.
                int[] old = {0, 0, 1, 2, 0, 3, 4};
                int n = p.getInt("tile_style", 0);
                i = n >= 0 && n < old.length ? old[n] : 0;
            }
            tileStyle = Math.max(0, i);
        }
        return tileStyle;
    }

    // The style names, one row each. Moving through them rebuilds the tiles behind the menu (the menu's
    // preview callback); OK saves the one shown, Back goes back to the one before.
    private void showTileStyles() {
        List<String> names = new ArrayList<>();
        for (String s : TILE_STYLES) names.add(Strings.t(s));
        // The same small glass menu as the Settings menu, no longer a side panel
        dropdown(tile(SETTINGS), names.toArray(new String[0]), tileStyle(), this::previewTileStyle, null, 0, which -> {
            getSharedPreferences(PREFS, 0).edit().putString("tile_look", TILE_STYLES[which]).apply();
            previewTileStyle(which);
        }, () -> backToSettings("Tile style"));
    }

    private void previewTileStyle(int which) {
        if (which == tileStyle) return;
        tileStyle = which;
        buildTiles();
    }

    // Apps per row, PER_ROW_MIN..PER_ROW_MAX, as the same small glass menu as the Settings menu (it opens in
    // its place). Live preview: moving through the list re-lays the apps, OK saves the number shown (pref "per_row"),
    // Back goes back to the one before. 
    private void showPerRow() {
        String[] items = new String[PER_ROW_MAX - PER_ROW_MIN + 1];
        for (int n = PER_ROW_MIN; n <= PER_ROW_MAX; n++)
            items[n - PER_ROW_MIN] = Strings.t("%d per row").replace("%d", String.valueOf(n));
        dropdown(tile(SETTINGS), items, perRow() - PER_ROW_MIN, which -> previewPerRow(PER_ROW_MIN + which), null, 0, which -> {
            int n = PER_ROW_MIN + which;
            getSharedPreferences(PREFS, 0).edit().putInt("per_row", n).apply();
            previewPerRow(n);
        }, () -> backToSettings("Apps per row"));
    }

    private void previewPerRow(int n) {
        if (n == perRow) return;
        perRow = n;
        buildTiles();
    }

    // How a banner looks while it is NOT focused, as a 4x5 colour matrix (null = untouched). Muted and Black & white
    // desaturate and dim it.
    private static float[] restLook(int style) {
        ColorMatrix m = new ColorMatrix();
        ColorMatrix s = new ColorMatrix();
        if (style == STYLE_MUTED) {
            m.setSaturation(0.4f);
            s.setScale(0.68f, 0.68f, 0.68f, 1f);
        } else if (style == STYLE_MONO) {
            m.setSaturation(0f);
            s.setScale(0.8f, 0.8f, 0.8f, 1f);
        } else {
            return null;
        }
        m.postConcat(s);
        return m.getArray();
    }

    // Fades a banner between its resting look and its own colours when its tile gains or loses focus.
    private void fadeLook(final ImageView iv, final float[] rest, boolean has) {
        ValueAnimator old = lifts.remove(iv);
        if (old != null) old.cancel();
        Object cur = iv.getTag(); // how far to full colour it is now (0..1), so a fast d-pad run never jumps
        final float from = cur instanceof Float ? (Float) cur : (has ? 0f : 1f), to = has ? 1f : 0f;
        final float[] full = new ColorMatrix().getArray();
        final float[] mix = new float[20];
        final ValueAnimator a = ValueAnimator.ofFloat(from, to);
        a.setDuration(FOCUS_MS);
        a.setInterpolator(FOCUS_EASE);
        a.addUpdateListener(an -> {
            float t = (float) an.getAnimatedValue();
            iv.setTag(t);
            if (t >= 1f) {
                iv.setColorFilter(null);
            } else {
                for (int i = 0; i < 20; i++) mix[i] = rest[i] + (full[i] - rest[i]) * t;
                iv.setColorFilter(new ColorMatrixColorFilter(mix));
            }
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator an) { if (lifts.get(iv) == a) lifts.remove(iv); }
        });
        lifts.put(iv, a);
        a.start();
    }

    // An icon on its own, no tile or label: a glass circle shows behind it only while it's focused.
    private ImageView addIcon(final String key, String label, Drawable icon, int x) {
        int b = px(ICON_BUTTON), size = px(ICON_SIZE);
        FrameLayout card = new FrameLayout(this);
        final GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.TRANSPARENT);
        card.setBackground(bg);
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(icon);
        card.addView(iv, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
        card.setFocusable(true);
        card.setTag(key);
        card.setContentDescription(label);
        card.setOnFocusChangeListener((v, has) -> {
            if (has) focusedKey = key;
            lift(v, bg, has, Color.TRANSPARENT, 0x38FFFFFF, 0, 0);
        });
        card.setOnClickListener(v -> open(key));
        FrameLayout wrap = new FrameLayout(this);
        wrap.setClipChildren(false);
        wrap.addView(card, new FrameLayout.LayoutParams(b, b));
        // Icons go into the bar at the bottom right, one after the other, not at a fixed x.
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(b, b);
        ilp.leftMargin = iconBar.getChildCount() == 0 ? 0 : px(ICON_GAP);
        iconBar.addView(wrap, ilp);
        tiles.add(card);
        return iv;
    }

    // The Settings icon's menu, starting on focusItem (an entry of SETTINGS_ITEMS) or on the first entry for null.
    // Split out of open() so the pages of this menu can come back to it with Back (see backToSettings).
    private void showSettingsMenu(String focusItem) {
        List<String> list = new ArrayList<>(Arrays.asList(SETTINGS_ITEMS));
        // Play Store only when it is installed and not already one of the apps on the home screen.
        if (rowApps.contains(PLAY_STORE) || launchIntent(getPackageManager(), PLAY_STORE) == null) list.remove("Play Store");
        if (!remoteButtonsInstalled()) list.remove("Remote buttons");
        final String[] items = list.toArray(new String[0]);
        View anchor = tile(SETTINGS);
        if (anchor == null) return;
        dropdown(anchor, items, focusItem == null ? -1 : list.indexOf(focusItem), null, null, 0, which -> {
            String item = items[which];
            if ("Wallpaper".equals(item)) showWallpapers();
            else if ("Tile style".equals(item)) showTileStyles();
            else if ("Apps per row".equals(item)) showPerRow();
            else if ("Add app".equals(item)) showAddMenu(-1);
            else if ("Play Store".equals(item)) open(PLAY_STORE);
            else if ("Remote buttons".equals(item)) openRemoteButtons();
            else openTvSettings();
        }, null);
    }

    private boolean remoteButtonsInstalled() {
        try { getPackageManager().getPackageInfo(REMOTE_PKG, 0); return true; } catch (Exception e) { return false; }
    }

    private void openRemoteButtons() {
        try {
            startActivity(new Intent().setClassName(REMOTE_PKG, REMOTE_PKG + ".SettingsActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {}
    }

    // Back in one of the pages of the Settings menu (Wallpaper, Tile style, Apps per row, Add app) goes back to the
    // Settings menu, on the entry it was opened from, instead of closing everything. Backing out of a page that previews live
    // (Tile style, Apps per row) puts the old look back, which rebuilds the tiles; the menu waits until the Settings icon has its
    // place again, or it would open at the wrong spot.
    private void backToSettings(final String item) {
        final View decor = getWindow().getDecorView();
        decor.post(new Runnable() {
            int tries = 0;
            @Override public void run() {
                View t = tile(SETTINGS);
                if (t != null && (t.isLayoutRequested() || !t.isLaidOut()) && ++tries < 20) {
                    decor.postDelayed(this, 50);
                    return;
                }
                showSettingsMenu(item);
            }
        });
    }

    private void open(String key) {
        if (SETTINGS.equals(key)) {
            showSettingsMenu(null);
            return;
        } else if (key.startsWith(QUICK)) {
            openQuick(key.substring(QUICK.length()));
            return;
        }
        Intent i = launchIntent(getPackageManager(), key);
        if (i == null) return;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (Exception ignored) {}
    }

    // The shortcuts picked with --es quick, in that order, as indexes into QUICK_NAMES. A shortcut
    // this TV has no screen for is left out, so it never shows a tile that does nothing.
    private List<Integer> quickList() {
        List<Integer> out = new ArrayList<>();
        String saved = getSharedPreferences(PREFS, 0).getString("quick", null);
        if (saved == null) return out;
        for (String name : saved.split("[,\\s]+")) {
            int q = Arrays.asList(QUICK_NAMES).indexOf(name.toLowerCase(java.util.Locale.ROOT));
            if (q < 0 || out.contains(q) || quickIntents(QUICK_NAMES[q]).isEmpty()) continue;
            out.add(q);
        }
        return out;
    }

    // Screens a shortcut can open, best first, keeping only those this TV has.
    private List<Intent> quickIntents(String name) {
        List<Intent> all = new ArrayList<>();
        String tclType = name.equals("sound") ? "SoundPreset" : name.equals("picture") ? "picture" : null;
        // Some TCL Google TVs (Q651G, Android 12) ship the same service inside the TV's own settings app.
        if (tclType != null)
            for (String pkg : new String[] {"com.tcl.settings", "com.android.tv.settings"})
                all.add(new Intent(TCL_SHOW_WINDOW).setClassName(pkg, "com.tcl.settings.ShowWindowService")
                        .putExtra("Type", tclType));
        switch (name) {
            case "bluetooth":
                all.add(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                all.add(new Intent("com.google.android.intent.action.CONNECT_INPUT")); // Android TV's pairing screen
                break;
            case "sound": all.add(new Intent(Settings.ACTION_SOUND_SETTINGS)); break;
            case "picture": all.add(new Intent(Settings.ACTION_DISPLAY_SETTINGS)); break;
        }
        PackageManager pm = getPackageManager();
        List<Intent> out = new ArrayList<>();
        for (Intent i : all) {
            boolean service = TCL_SHOW_WINDOW.equals(i.getAction());
            if (service ? pm.resolveService(i, 0) != null : i.resolveActivity(pm) != null) out.add(i);
        }
        return out;
    }

    // Opens a shortcut's screen: the first one that starts wins. False if none did.
    private boolean openQuick(String name) {
        for (Intent i : quickIntents(name)) {
            try {
                if (TCL_SHOW_WINDOW.equals(i.getAction())) startService(i);
                else startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    private void openTvSettings() {
        try { startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception ignored) {}
    }

    interface Pick { void at(int which); }
    interface Icon { Drawable at(int which) throws Exception; }

    // A small menu that drops down under a tile, in the same glass as the tiles. Opens above the
    // tile when there's no room below. Back closes it.
    private void dropdown(View anchor, String[] items, final Pick pick) {
        dropdown(anchor, items, -1, null, null, 0, pick, null);
    }

    private void dropdown(View anchor, String[] items, final int selected, final Pick preview, final Icon icon,
                          final int iconSize, final Pick pick) {
        dropdown(anchor, items, selected, preview, icon, iconSize, pick, null);
    }

    // With a preview, the list starts on item `selected`, focusing an item calls preview with it, and Back calls
    // it with `selected` again, to put things back. With icon (or null), each item gets an icon
    // of iconSize px. A list taller than the room on screen scrolls inside the menu. Used by the Settings menu's own pages
    // (Wallpaper, Tile style, Apps per row), so they all look like the Settings menu.
    // Back (or null) runs after Back (not after a pick), once the preview has been put back: a page of the
    // Settings menu uses it to return to the Settings menu instead of closing everything.
    private void dropdown(View anchor, String[] items, final int selected, final Pick preview, final Icon icon,
                          final int iconSize, final Pick pick, final Runnable back) {
        final Dialog d = new Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar);
        LinearLayout box = new LinearLayout(this); // the items; the glass (frame) stays put while this scrolls
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = px(10), rowH = px(64);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(RADIUS));
        bg.setColor(0xFF231D1F);
        bg.setStroke(Math.max(1, px(2)), 0x17FFFFFF);
        FrameLayout frame = new FrameLayout(this);
        frame.setPadding(pad, pad, pad, pad);
        frame.setBackground(bg);
        ScrollView scroll = new ScrollView(this);
        scroll.setFocusable(false); // the items take focus, the scroller only follows it
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.addView(box, new ViewGroup.LayoutParams(-1, -2));
        frame.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        float widest = 0; // some languages need more room than the tile is wide
        for (int i = 0; i < items.length; i++) {
            final int which = i;
            final TextView t = text(px(26), 0xD9FFFFFF, regular);
            t.setText(Strings.t(items[i]));
            t.setSingleLine(true);
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setPadding(px(22), 0, px(22), 0);
            float extra = 0;
            if (icon != null) {
                Drawable ic;
                try { ic = icon.at(i); } catch (Exception e) { ic = new ColorDrawable(Color.TRANSPARENT); }
                ic.setBounds(0, 0, iconSize, iconSize);
                t.setCompoundDrawablesRelative(ic, null, null, null);
                t.setCompoundDrawablePadding(px(16));
                extra = iconSize + px(16);
            }
            widest = Math.max(widest, t.getPaint().measureText(t.getText().toString()) + extra + 2 * px(22) + 2 * pad);
            final GradientDrawable focus = new GradientDrawable();
            focus.setCornerRadius(px(RADIUS - pad));
            focus.setColor(Color.TRANSPARENT);
            t.setBackground(focus);
            t.setFocusable(true);
            t.setOnFocusChangeListener((v, has) -> {
                tween(t, focus, has, Color.TRANSPARENT, 0x38FFFFFF, px(RADIUS - pad), px(RADIUS - pad));
                t.setTextColor(has ? Color.WHITE : 0xD9FFFFFF);
                if (has && preview != null) preview.at(which);
            });
            t.setOnClickListener(v -> {
                d.dismiss();
                pick.at(which);
            });
            box.addView(t, new LinearLayout.LayoutParams(-1, rowH));
        }
        d.setContentView(frame);
        if (preview != null || back != null) d.setOnCancelListener(x -> {
            if (preview != null) preview.at(selected);
            if (back != null) back.run();
        });
        if (selected >= 0 && selected < items.length) box.getChildAt(selected).requestFocus(); // start on the item in use

        // Line it up with the tile as drawn (focused tiles are scaled up), below its name.
        int[] at = new int[2];
        anchor.getLocationOnScreen(at);
        int tileBottom = at[1] + Math.round(anchor.getHeight() * anchor.getScaleY());
        int w = Math.max(Math.max(Math.round(anchor.getWidth() * anchor.getScaleX()), px(320)), (int) Math.ceil(widest));
        int h = items.length * rowH + 2 * pad;
        int y = tileBottom + (anchor.getHeight() < px(100) ? px(12) : px(64)); // An icon has no name underneath, so less gap
        int roomBelow = getResources().getDisplayMetrics().heightPixels - px(24) - y;
        if (h > roomBelow) {
            // No room below: on the side with the most room, no taller than that room (a longer list scrolls).
            // Above the tile when it fits there or there is more room; an icon near the top (Settings) keeps the menu below it.
            int roomAbove = at[1] - px(16) - px(24);
            if (roomAbove > roomBelow) {
                h = Math.min(h, Math.max(rowH + 2 * pad, roomAbove));
                y = at[1] - h - px(16);
            } else {
                h = Math.max(rowH + 2 * pad, roomBelow);
            }
        }
        Window win = d.getWindow();
        win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        win.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        win.setGravity(Gravity.TOP | Gravity.START);
        android.view.WindowManager.LayoutParams lp = win.getAttributes();
        // An icon at the right edge of the screen: line the menu's right edge up with the icon's instead of running off the screen.
        int screenW = getResources().getDisplayMetrics().widthPixels;
        lp.x = at[0] + w > screenW - px(24)
                ? Math.max(px(24), at[0] + Math.round(anchor.getWidth() * anchor.getScaleX()) - w) : at[0];
        lp.y = y;
        lp.width = w;
        lp.height = h;
        win.setAttributes(lp);
        d.show();
    }

    // Hold OK (or Menu) on a top-row app: move it, swap it for another, or take it off the home
    // screen. Removing only takes the tile away: the app stays installed and can be added back.
    // Apps the user installed can also be uninstalled; Android asks to confirm, and the tile goes
    // once the app is gone. Apps that came with the TV can't be, so they get App info instead,
    // where Android offers to turn them off.
    private void showEditMenu(final String key) {
        final int index = rowApps.indexOf(key);
        if (index < 0) return;
        final boolean user = userInstalled(key);
        dropdown(tile(key), user ? EDIT_ITEMS : EDIT_ITEMS_SYSTEM, which -> {
            if (which == 0) startMove(key);
            else if (which == 1) showAddMenu(index);
            else if (which == 3) {
                Uri pkg = Uri.parse("package:" + key);
                try { startActivity(user ? new Intent(Intent.ACTION_DELETE, pkg)
                        : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)); }
                catch (Exception ignored) {}
            } else {
                rowApps.remove(index);
                saveRow();
                focusedKey = rowApps.isEmpty() ? SETTINGS : rowApps.get(Math.min(index, rowApps.size() - 1)); // No Add tile any more
                buildTiles();
            }
        });
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, 0);
    }

    // Every launchable app that isn't on the top row yet, by name. The pick is added at the end,
    // or takes the place of the app at replaceIndex (-1 to add).
    private void showAddMenu(final int replaceIndex) {
        final PackageManager pm = getPackageManager();
        TreeMap<String, String> byLabel = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String category : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String p = ri.activityInfo.packageName;
                if (rowApps.contains(p) || byLabel.containsValue(p) || p.equals(getPackageName())
                        || (NOT_APPS.contains(p) && !p.equals(PLAY_STORE))) continue;
                String label = labelOf(p);
                if (!byLabel.containsKey(label)) byLabel.put(label, p);
            }
        }
        // The same small glass menu as the rest of the Settings menu, under the Settings icon (add) or the app being
        // replaced. It has no title: its place on screen says which one it is.
        View anchor = replaceIndex < 0 ? null : tile(rowApps.get(replaceIndex));
        if (anchor == null) anchor = tile(SETTINGS);
        if (anchor == null) return;
        // Back goes back to where this was opened from: the Settings menu (add) or the app's own menu (replace).
        final String replaced = replaceIndex < 0 ? null : rowApps.get(replaceIndex);
        final Runnable back = replaced == null ? () -> backToSettings("Add app") : () -> showEditMenu(replaced);
        if (byLabel.isEmpty()) {
            dropdown(anchor, new String[] {"Every app is already here"}, -1, null, null, 0, which -> {}, back);
            return;
        }
        final List<String> pkgs = new ArrayList<>(byLabel.values());
        dropdown(anchor, byLabel.keySet().toArray(new String[0]), -1, null,
                pos -> fit(pm.getApplicationIcon(pkgs.get(pos)), px(40), px(40)), px(40), which -> {
            if (replaceIndex < 0) rowApps.add(pkgs.get(which));
            else rowApps.set(replaceIndex, pkgs.get(which));
            saveRow();
            focusedKey = pkgs.get(which);
            buildTiles();
        }, back);
    }

    private String labelOf(String pkg) {
        PackageManager pm = getPackageManager();
        try { return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); }
        catch (Exception e) { return pkg; }
    }

    // Saves the top row as the app list, so it no longer follows apps.txt.
    private void saveRow() {
        getSharedPreferences(PREFS, 0).edit().putString("apps", android.text.TextUtils.join(",", rowApps)).apply();
    }

    // Where app number i sits in the grid.
    private int gridX(int i) {
        return px(EDGE) + (i % perRow()) * (tileW + colGap); // EDGE (64) instead of MARGIN (96); colGap = GAP, or TEXT_GAP in the text styles
    }

    private int gridY(int i) {
        return gridTop + (i / perRow()) * rowStep;
    }

    private View tile(String key) {
        for (View t : tiles) if (t.getTag().equals(key)) return t;
        return null;
    }

    // Move mode: the tile gets an outline, the arrows move it through the grid, OK or Back finishes.
    private void startMove(String key) {
        View card = tile(key);
        if (card == null) return;
        movingKey = key;
        GradientDrawable outline = new GradientDrawable();
        outline.setCornerRadius(px(RADIUS));
        outline.setStroke(px(4), Color.WHITE);
        ((FrameLayout) card).setForeground(outline);
        moveHint.setVisibility(View.VISIBLE);
        card.requestFocus();
    }

    private boolean moveKey(String key, int code, boolean ok, boolean down) {
        int step = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1
                : code == KeyEvent.KEYCODE_DPAD_UP ? -perRow() : code == KeyEvent.KEYCODE_DPAD_DOWN ? perRow() : 0;
        if (step != 0) {
            int from = rowApps.indexOf(key), to = from + step;
            if (down && to >= 0 && to < rowApps.size()) {
                // Slide the tiles into their new places: no rebuild, so it stays quick on slow TVs.
                rowApps.add(to, rowApps.remove(from));
                for (int i = Math.min(from, to); i <= Math.max(from, to); i++) {
                    View wrap = (View) tile(rowApps.get(i)).getParent();
                    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) wrap.getLayoutParams();
                    lp.leftMargin = gridX(i);
                    lp.topMargin = gridY(i);
                    wrap.setLayoutParams(lp);
                }
                // Scroll along with it once it has moved.
                final View card = tile(key);
                card.post(() -> card.requestRectangleOnScreen(
                        new android.graphics.Rect(0, 0, card.getWidth(), card.getHeight())));
            }
            return true;
        }
        if (ok || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) {
            if (!down) {
                movingKey = null;
                longPressed = false;
                ((FrameLayout) tile(key)).setForeground(null);
                moveHint.setVisibility(View.GONE);
                saveRow();
                refreshApps();
            }
            return true;
        }
        return true; // up/down and everything else stay put while moving
    }

    private boolean userInstalled(String pkg) {
        try { return (getPackageManager().getApplicationInfo(pkg, 0).flags & ApplicationInfo.FLAG_SYSTEM) == 0; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }

    private static Intent launchIntent(PackageManager pm, String pkg) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        return i != null ? i : pm.getLaunchIntentForPackage(pkg);
    }

    // Xiaomi TVs ship MiLan Pro but don't register it by name; every other TV gets Roboto.
    private static void loadFonts() {
        if (regular != null) return;
        regular = fromFile("/system/fonts/MILanPro_Normal.ttf", Typeface.create("sans-serif", Typeface.NORMAL));
        semibold = fromFile("/system/fonts/MILanPro_SemiBold.ttf", Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    private static Typeface fromFile(String path, Typeface fallback) {
        try { return new File(path).exists() ? Typeface.createFromFile(path) : fallback; }
        catch (Exception e) { return fallback; }
    }

    // Draws a banner once into a tile-sized bitmap (center-cropped). Some apps ship huge banners
    // that Android 9 refuses to draw ("Canvas: trying to draw too large bitmap"); this also saves RAM.
    private Drawable fit(Drawable d, int w, int h) {
        try {
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
            if (iw <= 0 || ih <= 0) { iw = w; ih = h; }
            float s = Math.max((float) w / iw, (float) h / ih);
            int dw = Math.round(iw * s), dh = Math.round(ih * s);
            d.setBounds((w - dw) / 2, (h - dh) / 2, (w - dw) / 2 + dw, (h - dh) / 2 + dh);
            d.draw(new Canvas(b));
            return new BitmapDrawable(getResources(), b);
        } catch (Throwable t) {
            return d;
        }
    }

    private int px(int v) {
        return Math.round(v * scale);
    }

    private TextClock clock(String f12, String f24, int px, int color, Typeface tf) {
        TextClock c = new TextClock(this);
        c.setFormat12Hour(f12);
        c.setFormat24Hour(f24);
        c.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        c.setTextColor(color);
        c.setTypeface(tf);
        return c;
    }

    private TextView text(int px, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        t.setTextColor(color);
        if (tf != null) t.setTypeface(tf);
        return t;
    }

    private static FrameLayout.LayoutParams at(int x, int y, int w, int h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.leftMargin = x;
        lp.topMargin = y;
        return lp;
    }
}
