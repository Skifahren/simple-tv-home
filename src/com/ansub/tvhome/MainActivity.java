package com.ansub.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
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
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.List;
import java.util.WeakHashMap;

public class MainActivity extends Activity {

    // Top-row apps, in this order of preference:
    //  1. a list saved at install time:  am start -n com.ansub.tvhome/.MainActivity --es apps "pkg1,pkg2"
    //     (--es apps auto  goes back to 2; add --es stamp <unique> so it's applied only once, see saveConfig)
    //  2. apps.txt (built in as Apps.LIST), topped up with other TV apps installed on this TV.
    // Missing apps are skipped. Topping up stops at six apps, but a chosen list can be any length:
    // apps wrap into rows of six, like Apple TV, and the page scrolls down once they don't all fit.
    // On the TV, hold OK on an app (or press Menu) to move or remove it, or add another; that saves list 1.
    // A network tile always sits at the top right, level with the clock. Settings shortcuts can join
    // it there, off until picked the same way:
    //   --es quick "bluetooth,sound,picture"
    // (--es quick none  takes them away again; "wifi" and "ethernet" are the network tile now).
    private static final String PREFS = "config";
    private static final List<String> NOT_APPS = Arrays.asList("com.android.vending", "com.android.tv.settings",
            "com.android.tv", "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx",
            "com.google.android.leanbacklauncher");
    private static final String PLAY_STORE = "com.android.vending";
    private static final String SETTINGS = "settings";
    private static final String ADD = "add";
    private static final String[] EDIT_ITEMS = {"Move", "Replace", "Remove from home", "Uninstall"};
    private static final String[] EDIT_ITEMS_SYSTEM = {"Move", "Replace", "Remove from home", "App info"};
    // LOCAL CHANGE (Beamer, 2026-11-06): 3 tiles per row since 17:4x (it was 4 for the first rounds; upstream: 6) at a
    // fixed tile width of 194 px on the 1920 px design grid (upstream: derived, 258 px), i.e. 25% smaller tiles,
    // left-aligned in the bottom left corner. Only perRow() sets the row length: x, rows and Up/Down all follow it.
    // LOCAL CHANGE (2026-11-06 ~18:20): the row length is a setting now (Settings icon > Apps per row, PER_ROW_MIN..MAX,
    // saved as "per_row"); PER_ROW_DEFAULT is what it is until the user picks one. Seven tiles still end well left of the icon.
    private static final int PER_ROW_DEFAULT = 3, PER_ROW_MIN = 2, PER_ROW_MAX = 7, MAX_INPUTS = 4, TILE_W = 194;
    private int perRow = -1; // apps per row in use (also while the panel previews one); -1 = not read yet

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
    private static final String NETWORK = "network";
    private static final int OFFLINE = 0, ETHERNET = 4; // network states; 1 to 3 are Wi-Fi bars

    // Saved on the TV: custom input names ("id=name" per line), hidden inputs (one id per line),
    // and the clock override ("12" or "24"; absent follows the TV).
    private static final String PREF_INPUT_NAMES = "inputnames", PREF_HIDDEN_INPUTS = "hiddeninputs";
    private static final String PREF_CLOCK = "clock", PREF_FAKE_INPUTS = "fakeinputs";
    // Upstream's emulator test hook (a throwaway input with no HDMI port behind it). This repository's install.sh
    // no longer sends it and does not ship that input; the code is kept as it was so the app behaves the same.
    private static final String FAKE_INPUT_PKG = "com.ansub.fakeinput";
    // Names an input can be given with the remote alone: no keyboard, no typing.
    private static final String[] INPUT_PRESETS = {"TV", "PlayStation", "Xbox", "Switch", "Laptop", "PC", "Blu-ray", "Soundbar"};
    // TCL keeps its own Picture and Sound panels in a service; Android's Sound page is missing there.
    private static final String TCL_SHOW_WINDOW = "com.tcl.settings.SHOW_WINDOW";

    // Layout is designed at 1920x1080 and scaled to the TV's real UI size by px().
    private static final int MARGIN = 96, GAP = 36, RADIUS = 26;
    // The focus lift: the tile grows, rises, brightens and rounds off a little, then settles.
    // Long enough to read as motion, short enough that a run of d-pad presses never feels behind.
    private static final int FOCUS_MS = 180, RADIUS_UP = 10;
    private static final Interpolator FOCUS_EASE = new DecelerateInterpolator(1.8f);
    // LOCAL CHANGE: the row step follows the tile height (tile + ROW_GAP). Upstream used a fixed 224, which is
    // exactly 145 (a tile at 6 per row) + 79, so nothing moves at 6 per row.
    // ROW_GAP is now 36 (same as the horizontal GAP): there is no caption under the tiles any more, so the room it needed (79) is gone.
    private static final int ROW1_TOP = 604, ROW_GAP = 36;
    private static final int GRID_MIN_TOP = 48, EDGE = 64; /* LOCAL CHANGE: EDGE = distance of the apps (left, bottom) and of the top bar (right) to the screen edge; GRID_MIN_TOP was 400, the grid now grows upwards from the bottom left corner until here */ // more rows push the grid up, but not into the clock
    private static final int ICON_TOP = 76, ICON_BUTTON = 72, ICON_SIZE = 40, ICON_GAP = 12; // level with the date

    // The Settings tile opens a small menu: the home screen's own settings, then the TV's.
    // LOCAL CHANGE: "Add app" and "Play Store" moved here from the icon bar (which now only holds Settings), and
    // "Tile style" picks how the app tiles look.
    // ("Brightness" was removed from this menu at the user's request, 2026-11-06 ~18:45; openBrightness() is still there, unused:
    // put "Brightness" back between "Apps per row" and "TV settings", plus its branch in open(), to restore it.)
    private static final String[] SETTINGS_ITEMS = {"Wallpaper", "Tile style", "Apps per row", "Remote buttons", "TV settings", "Add app", "Play Store"};
    // "Remote buttons" is only shown when this separate add-on app is installed; it opens the add-on's own settings screen.
    // The key filter lives there, so this home screen itself needs no access to the remote's keys.
    private static final String REMOTE_PKG = "local.beamer.remotebuttons";

    // LOCAL CHANGE: the tile styles. Original = the apps' own banners. Muted / Black & white = the banners, filtered so they
    // stop clashing; full colour returns on focus. Text on glass = a glass tile with only the name; Text only = no tile at all,
    // only the name (glass appears on focus). Both text styles are half as tall as an app tile and sit closer together.
    // (Glass and Duotone styles were tried and removed at the user's request; see the change log.) The saved choice is the NAME.
    private static final String[] TILE_STYLES = {"Original", "Muted", "Black & white", "Text on glass", "Text only"};
    private static final int STYLE_ORIGINAL = 0, STYLE_MUTED = 1, STYLE_MONO = 2, STYLE_TEXT = 3, STYLE_PLAIN = 4;
    // Text styles: the gaps between the (half-height) blocks, replacing GAP and ROW_GAP (36 each).
    private static final int TEXT_GAP = 16, TEXT_ROW_GAP = 10;
    private int colGap; // LOCAL CHANGE: distance between columns, GAP or TEXT_GAP; set by buildTiles
    private int tileStyle = -1; // the style in use (also while a preview in the panel is showing); -1 = not read yet
    // Android TV 11 and later (Google TV too) has no Display page, but its "Display & Sound" page holds Picture.
    private static final String[] TV_DISPLAY_SOUND = {"com.android.tv.settings",
            "com.android.tv.settings.device.displaysound.DisplaySoundActivity"};
    // Wallpapers, saved by name: a gradient top to bottom, then a glow from the top left and one from
    // the bottom right. Dark ones first, then bright ones that get darker towards the bottom, so the
    // white labels stay easy to read. Only colours, no images, so every one costs the same as Dusk.
    // LOCAL CHANGE: the first PHOTO_COUNT entries are photos (res/drawable-nodpi/wallpaper_*.jpg, authors and licences
    // in WALLPAPER_CREDITS.txt) instead of gradients, "Mountains" first and the default. Their rows below only give the
    // little dot in the picker its colours. Names must stay unique: the choice is saved by name.
    // LOCAL CHANGE (7 Nov): the picker only lists the ten photos. The 14 gradients below Honfleur (Dusk, Midnight, Plum, Forest,
    // Graphite, Ocean, Ember, Sunset, Lagoon, Aurora, Coral, Iris, Meadow, Gold) were taken out at the user's request; their rows
    // stay in WALLPAPERS (Dusk, row 10, is the fallback when a photo can't be drawn). To bring one back, add its name here in
    // the same order as the rows. A saved name that is no longer listed (e.g. "Gold") falls back to "Mountains" in wallpaper().
    private static final String[] WALLPAPER_NAMES = {"Mountains", "Misty Trees", "Misty Valley", "Snow Peaks", "Northern Lights",
            "Waterfall", "Tokyo", "Amsterdam", "Nyhavn", "Honfleur"};
    private static final int PHOTO_COUNT = 10;        // indexes 0..9 above are photos
    private static final String[] PHOTO_RES = {"wallpaper_nature", "wallpaper_mist_trees", "wallpaper_mist_valley",
            "wallpaper_snow", "wallpaper_aurora", "wallpaper_waterfall", "wallpaper_tokyo", "wallpaper_amsterdam",
            "wallpaper_nyhavn", "wallpaper_honfleur"};
    // LOCAL CHANGE (7 Nov): the photos in res/drawable-nodpi are BAKED: already cropped to 1920x1080 and darkened at the top (clock),
    // the bottom (apps) and overall, so the TV only decodes them. The crop and darkness per photo (the old PHOTO_BIAS, PHOTO_TOP,
    // PHOTO_BOTTOM, PHOTO_DIM arrays) now live in tools/BakeWallpapers.java, which makes them from wallpaper-originals/.
    // Each also has a 96 px picture for the wallpaper list: wallpaper_*_thumb.jpg.
    private static final int[][] WALLPAPERS = {
        {0xFF2C5C7C, 0xFF1C4A5C, 0xFF0C2A30, 0x6070B0D8, 0x604C8C6C},
        {0xFF6A5230, 0xFFB08040, 0xFF201810, 0xFFFFE0A0, 0xFF3A2C18}, // Misty Trees
        {0xFF5A7A3C, 0xFFE06830, 0xFF304028, 0xFFE8E4E0, 0xFF5A7A3C}, // Misty Valley
        {0xFF8FA6C8, 0xFFA8BCD8, 0xFF405070, 0xFFFFFFFF, 0xFF5870A0}, // Snow Peaks
        {0xFF103A40, 0xFF1A5A70, 0xFF050E14, 0xFF40E0A0, 0xFF0A1830}, // Northern Lights
        {0xFF3A5A48, 0xFF6A8A3C, 0xFF0C1814, 0xFFE8E0D0, 0xFF3A2C20}, // Waterfall
        {0xFF1C3A44, 0xFF1C3A44, 0xFF0A1418, 0xFFFFB030, 0xFF0A1418}, // Tokyo
        {0xFF4A4038, 0xFF4A4038, 0xFF2A3038, 0xFFC86040, 0xFF2A3038}, // Amsterdam
        {0xFF4A6A90, 0xFF4A6A90, 0xFF2A3A50, 0xFFFFA040, 0xFFD04838}, // Nyhavn
        {0xFF5080C0, 0xFF5080C0, 0xFF3A5A88, 0xFFF0F4FF, 0xFF6A5A48}, // Honfleur
        {0xFF15100F, 0xFF0A0809, 0xFF050405, 0x36FF8048, 0x2EAA3C8C},
        {0xFF0E1220, 0xFF080A13, 0xFF040509, 0x384A6CFF, 0x2E5A3CC8},
        {0xFF170E19, 0xFF0C070D, 0xFF050306, 0x38C050B4, 0x2E6440D2},
        {0xFF0C150F, 0xFF070C09, 0xFF030604, 0x3050B478, 0x28289682},
        {0xFF151617, 0xFF0B0C0D, 0xFF050506, 0x1CFFFFFF, 0x16A0B4C8},
        {0xFF0A1517, 0xFF060C0E, 0xFF030607, 0x3426B4C8, 0x2E2A64C0},
        {0xFF190D08, 0xFF0D0605, 0xFF060303, 0x40FF5A24, 0x32C8283C},
        {0xFF8C3A5E, 0xFF5E2348, 0xFF22101E, 0xA0FF8A4C, 0x80E0457A},
        {0xFF14588E, 0xFF15366E, 0xFF0A1632, 0x9030C8E0, 0x807A4CE0},
        {0xFF0F4048, 0xFF0F2E3A, 0xFF08141C, 0x9038E0A0, 0x90A050F0},
        {0xFFC0604E, 0xFF8A3456, 0xFF2E1230, 0x90FFB070, 0x70E04090},
        {0xFF5A3CAE, 0xFF3A2A84, 0xFF16123A, 0x90C080FF, 0x804A7CFF},
        {0xFF2E6E4C, 0xFF1C4A40, 0xFF0A201E, 0x90A8E070, 0x6030A8A8},
        {0xFFB07624, 0xFF86401E, 0xFF2A1410, 0x90FFD060, 0x70FF5040},
    };
    private float scale = 1f;

    private static Typeface regular, semibold;

    private FrameLayout root; // the page, inside a vertical scroller
    private int tileW, tileH, gridTop; // app grid geometry, set by buildTiles
    private int rowStep;   // LOCAL CHANGE: distance between rows, tile height + ROW_GAP
    private LinearLayout iconBar;                  // LOCAL CHANGE: bottom right: Play Store, Add app, Settings icons (the clock is on its own, top right)
    private FrameLayout.LayoutParams iconBarLp;
    private float tileK = 1f; // LOCAL CHANGE: how much smaller than upstream's 258 px tile the tiles are (1 = same size)
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
    // key-up to the tile still focused here, so a key-up only opens the app it went down on (#30).
    private String okDownKey;

    // Apps installed, removed or updated. This often lands once Home is already back in front (an app
    // uninstalled from the menu is gone only after Android's prompt has closed), so catch up then.
    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            dirty = true;
            refreshApps();
        }
    };

    // The network tile follows the connection live: Wi-Fi bars, Ethernet or Offline. Only its icon and
    // label change, so nothing is rebuilt.
    private ImageView netIcon;
    private int netState = -1;
    private final ConnectivityManager.NetworkCallback networkChanged = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network n) { runOnUiThread(MainActivity.this::refreshNetwork); }
        @Override public void onLost(Network n) { runOnUiThread(MainActivity.this::refreshNetwork); }
        // Also fires when the Wi-Fi signal gets stronger or weaker.
        @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) {
            runOnUiThread(MainActivity.this::refreshNetwork);
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
        clock.setGravity(Gravity.END); // LOCAL CHANGE: date and time right-aligned
        shownLocale = java.util.Locale.getDefault();
        String day = Strings.datePattern();
        TextClock date = clock(day, day, px(26), 0xA8FFFFFF, regular); // LOCAL CHANGE: was 30
        // The clock follows the TV unless --es clock 12 or 24 says otherwise.
        String clockPref = prefs().getString(PREF_CLOCK, null);
        TextClock time = clock("24".equals(clockPref) ? "H:mm" : "h:mm", "12".equals(clockPref) ? "h:mm" : "H:mm",
                px(88), Color.WHITE, semibold); // LOCAL CHANGE: was 176
        time.setLetterSpacing(-0.028f);
        time.setFontFeatureSettings("tnum");
        time.setIncludeFontPadding(false);
        // Wrap, not the default match_parent: the column must be as wide as the date, not just the time,
        // or "0:00" to "9:59" (one digit less) cuts a long date off.
        clock.addView(date, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.topMargin = px(-8); // pull the digits up under the date (LOCAL CHANGE: was -20 for the bigger digits)
        clock.addView(time, tp);
        // LOCAL CHANGE: the clock sits top right on its own (48 px from the top, EDGE from the right), as it did when it
        // was the last child of a bar. buildTiles puts the Play Store, Add app and Settings icons in a separate bar at the
        // bottom right, level with the bottom row of apps. That bar is added to the page by buildTiles, after the apps,
        // so the first app keeps first focus.
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
        root.addView(moveHint, at(px(EDGE), px(ROW1_TOP - 64), -2, -2)); // LOCAL CHANGE: EDGE instead of MARGIN

        setContentView(scroller);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(packagesChanged, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(packagesChanged, f);

        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) try {
            if (android.os.Build.VERSION.SDK_INT >= 24) cm.registerDefaultNetworkCallback(networkChanged);
            else cm.registerNetworkCallback(new NetworkRequest.Builder().build(), networkChanged);
        } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packagesChanged);
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) try { cm.unregisterNetworkCallback(networkChanged); } catch (Exception ignored) {}
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
        String fake = intent == null ? null : intent.getStringExtra("fakeinputs");
        if (apps == null && quick == null && clock == null && fake == null) return false;
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
        // Set only by install.sh's FAKE_INPUT=1, which also installs the throwaway input itself.
        // It is for an emulator, which has no HDMI ports, and never for a real TV.
        if (fake != null) {
            if (fake.trim().equals("1")) {
                e.putString(PREF_FAKE_INPUTS, "1");
                // An input taken off the home screen stays off, even when FAKE_INPUT=1 adds it back
                // (undo.sh uninstalls it, but running install.sh again does not). Left alone, the
                // emulator would come back with no input tile at all, so put the test input back.
                Set<String> hidden = hiddenInputs();
                for (java.util.Iterator<String> it = hidden.iterator(); it.hasNext(); )
                    if (it.next().startsWith(FAKE_INPUT_PKG + "/")) it.remove();
                e.putString(PREF_HIDDEN_INPUTS, android.text.TextUtils.join("\n", new TreeSet<>(hidden)));
            } else e.remove(PREF_FAKE_INPUTS);
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
        refreshNetwork();
        View f = getCurrentFocus();
        if (f == null && !tiles.isEmpty()) tiles.get(0).requestFocus();
        HomeButton.onHomeResumed(this); // back on this home screen: Home comes here again
        HomeButton.setHomeVisible(true); // LOCAL CHANGE: lets the Home button service see whether this home is in front
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
        HomeButton.setHomeVisible(false); // LOCAL CHANGE: see onResume
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
    // move, and those frames take two to six times as long (#69). On Android 8+ the picture moves to
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

    // The saved wallpaper, as an index into WALLPAPERS (Dusk if none is saved).
    private int wallpaper() {
        String saved = getSharedPreferences(PREFS, 0).getString("wallpaper", null);
        return Math.max(0, Arrays.asList(WALLPAPER_NAMES).indexOf(saved));
    }

    private Drawable photoBg; // LOCAL CHANGE: the photo, drawn once and kept while it is the wallpaper
    private int photoIndex = -1; // and which photo that is

    // LOCAL CHANGE: drawing a photo wallpaper takes about a second on this beamer (full-size JPEG decode). Done in the focus
    // listener of the wallpaper list that froze the whole screen for that second at every step, and with a real remote the
    // presses that queued up in the meantime moved the highlight past the wanted entry (Misty Trees jumped to Misty Valley).
    // So the list's preview only starts the decode on a worker thread once the highlight has rested for 200 ms, and a result
    // that arrives after the highlight moved on is dropped. Saving (OK), Back and starting up still use showWallpaper() below.
    private volatile int wallpaperRequest; // bumped on every change; an async result is only used while it is still the latest
    private android.os.Handler photoWorker; // a thread of its own, made on first use
    private final android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    private void previewWallpaper(final int i) {
        final int req = ++wallpaperRequest;
        if (i >= PHOTO_COUNT || (photoBg != null && photoIndex == i)) { // a gradient, or the photo already on screen: instant
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
                getWindow().setBackgroundDrawable(d != null ? d : background(WALLPAPERS[PHOTO_COUNT]));
            });
        }, 200);
    }

    private void showWallpaper(int i) {
        wallpaperRequest++; // LOCAL CHANGE: whatever a worker is still drawing is out of date now
        // LOCAL CHANGE: the photo wallpapers; if one can't be drawn for any reason, fall back to Dusk's gradient.
        Drawable d = null;
        boolean photo = i < PHOTO_COUNT;
        if (photo) {
            if (photoBg == null || photoIndex != i) {
                photoBg = null; // let go of the previous photo first, so two never sit in memory together
                photoBg = photoBackground(i);
                photoIndex = i;
            }
            d = photoBg;
        } else {
            photoBg = null; // let go of the picture's memory once a gradient is chosen
            photoIndex = -1;
        }
        getWindow().setBackgroundDrawable(d != null ? d : background(WALLPAPERS[photo ? PHOTO_COUNT : i]));
    }

    // LOCAL CHANGE: the baked photo (see PHOTO_RES): decoded straight into GPU memory on Android 8+, so a focus move costs nothing
    // extra and the home screen uses no more memory than with a gradient. It used to be cropped, darkened and copied here at every
    // choice (~240 ms, Misty Trees 740 ms). Null if anything fails.
    private Drawable photoBackground(int p) {
        try {
            final long t0 = android.os.SystemClock.uptimeMillis(); // LOCAL CHANGE: timed, to see which photo is slow
            int id = getResources().getIdentifier(PHOTO_RES[p], "drawable", getPackageName());
            if (id == 0) {
                android.util.Log.w("TvHomePhoto", PHOTO_RES[p] + ": resource not found"); // LOCAL CHANGE: a failure was silent (fell back to Dusk)
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
        // LOCAL CHANGE: the same small glass menu as the Settings menu and Add app (it scrolls: 24 entries), no longer a side
        // panel. Each name has a rounded 40 px picture like the app icons in Add app: the photo itself, or the colours of a gradient.
        dropdown(tile(SETTINGS), WALLPAPER_NAMES, wallpaper(), this::previewWallpaper, pos -> wallpaperThumb(pos, size), size, which -> {
            getSharedPreferences(PREFS, 0).edit().putString("wallpaper", WALLPAPER_NAMES[which]).apply();
            showWallpaper(which);
        }, () -> backToSettings("Wallpaper"));
    }

    private final Drawable[] wallpaperThumbs = new Drawable[WALLPAPER_NAMES.length]; // made on first use, a few KB each

    // LOCAL CHANGE: the picker's picture of wallpaper p: a rounded square. A photo is shown as the photo (centre square, decoded
    // at a fraction of its size so ten of them cost next to nothing); a gradient as its colours, glows made stronger so the dark
    // ones can be told apart (as the old dots did). A photo that can't be read falls back to the colours in its WALLPAPERS row.
    private Drawable wallpaperThumb(int p, int size) {
        if (wallpaperThumbs[p] != null) return wallpaperThumbs[p];
        float corner = px(8);
        int edge = Math.max(1, px(2));
        Drawable d = null;
        Bitmap photo = p < PHOTO_COUNT ? photoThumb(p, size) : null;
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
            int[] c = WALLPAPERS[p];
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

    // LOCAL CHANGE: the list picture of photo p, size x size px: the small ready-made square wallpaper_*_thumb.jpg (96 px, the
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
        root.removeView(iconBar); // LOCAL CHANGE: re-added at the end, so it comes after the apps in focus order
        rowApps.clear();
        movingKey = null;
        moveHint.setVisibility(View.GONE);

        PackageManager pm = getPackageManager();
        final int style = tileStyle(); // LOCAL CHANGE: how the app tiles look (see TILE_STYLES)
        int m = px(MARGIN), g = px(GAP);
        int w = px(TILE_W); // LOCAL CHANGE: fixed tile width instead of filling the screen width
        // LOCAL CHANGE: the two text styles have no banner to show, so their blocks are half as tall and sit closer together.
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
        // LOCAL CHANGE: the apps sit in the bottom left corner: the last row ends EDGE above the bottom of the
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
            // LOCAL CHANGE: the two text styles ignore the banners: just the name, no icon
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

        // LOCAL CHANGE: no bottom row of tiles, no HDMI tile and no network icon. Settings is a plain icon in a bar at the
        // bottom right, level with the bottom row of apps (then any settings shortcuts picked with --es quick). Add app and
        // Play Store are entries of the Settings menu now (see open()). To get an HDMI tile back, restore the upstream code
        // that listed hdmiInputs() here.
        addIcon(SETTINGS, Strings.t("Settings"), new LineIcon(LineIcon.SETTINGS), 0);
        for (int q : quickList())
            addIcon(QUICK + QUICK_NAMES[q], Strings.t(QUICK_LABELS[q]), new LineIcon(QUICK_ICONS[q]), 0);
        netIcon = null; // no network icon any more; refreshNetwork() does nothing without it
        netState = -1;
        // The bar goes in last, so the first app keeps first focus; Right from the last app of a row reaches the icons.
        // Its centre is the centre of the bottom row of apps (the bottom row ends EDGE above the screen's bottom edge).
        iconBarLp.bottomMargin = px(EDGE) + (h - px(ICON_BUTTON)) / 2;
        root.addView(iconBar, iconBarLp);

        root.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        // An app that was uninstalled hands focus to the one now in its place, as Remove does.
        if (wantAt >= 0 && !rowApps.contains(want))
            want = rowApps.isEmpty() ? SETTINGS : rowApps.get(Math.min(wantAt, rowApps.size() - 1)); // LOCAL CHANGE: no Add tile any more
        View restore = null;
        for (View t : tiles) if (t.getTag().equals(want)) restore = t;
        if (restore != null) restore.requestFocus();
    }

    private void addTile(ViewGroup parent, final String key, String label, Drawable banner, int[] lineIcon, Drawable appIcon,
                         int x, int y, int w, int h) {
        FrameLayout card = new FrameLayout(this);
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(RADIUS));
        final int st = tileStyle(); // LOCAL CHANGE: see TILE_STYLES
        final boolean textOnly = banner == null && (st == STYLE_TEXT || st == STYLE_PLAIN); // just the name, no icon
        final boolean plain = banner == null && st == STYLE_PLAIN; // and no tile until it is focused
        // LOCAL CHANGE: in the filtered styles the banner is drawn through a colour matrix until the tile is focused.
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
            // LOCAL CHANGE: the text styles have no icon: just the name, in a glass tile (Text on glass) or on nothing (Text only,
            // where the glass only shows while the tile is focused).
            bg.setColor(plain ? 0x00FFFFFF : 0x13FFFFFF);
            if (!plain) bg.setStroke(Math.max(1, px(2)), 0x17FFFFFF);
            LinearLayout inner = new LinearLayout(this);
            inner.setOrientation(LinearLayout.VERTICAL);
            inner.setGravity(Gravity.CENTER);
            // LOCAL CHANGE: the inside of a glass tile shrinks with the tile (but keeps the text readable).
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

        // LOCAL CHANGE: no name underneath a focused banner tile (upstream: banner == null ? null : text(px(24), Color.WHITE, regular)).
        // All uses below check for null, so this alone switches the caption off. Put the upstream expression back to restore it,
        // and set ROW_GAP back to 79 so the caption has room again.
        final TextView caption = null;
        if (caption != null) {
            caption.setText(label);
            caption.setGravity(Gravity.CENTER_HORIZONTAL);
            caption.setVisibility(View.INVISIBLE);
        }

        final boolean glass = banner == null;
        card.setOnFocusChangeListener((v, has) -> {
            if (has) focusedKey = key;
            lift(v, bg, has, glass ? (plain ? 0x00FFFFFF : 0x13FFFFFF) : 0xFF1E1A1C, glass ? 0x38FFFFFF : 0xFF1E1A1C,
                    px(RADIUS), px(RADIUS + RADIUS_UP));
            if (bannerView != null) fadeLook(bannerView, rest, has);
            if (caption != null) {
                caption.animate().cancel();
                if (has) {
                    caption.setVisibility(View.VISIBLE);
                    caption.setAlpha(0f);
                    caption.animate().alpha(1f).setDuration(FOCUS_MS).setInterpolator(FOCUS_EASE).start();
                } else {
                    caption.animate().alpha(0f).setDuration(FOCUS_MS / 2).withEndAction(
                            () -> caption.setVisibility(View.INVISIBLE)).start();
                }
            }
        });
        final boolean editable = rowApps.contains(key);
        card.setOnClickListener(v -> open(key));
        if (editable) card.setOnLongClickListener(v -> { showEditMenu(key); return true; });
        // What a long OK (or Menu) on this tile opens: the app row's menu, an input's menu, or, on
        // the + tile, the inputs taken off the home screen.
        final Runnable menu = editable ? () -> showEditMenu(key)
                : isInput(key) ? () -> showInputMenu(key)
                : ADD.equals(key) && !hiddenInputs().isEmpty() ? this::showHiddenInputs : null;
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
        if (caption != null) {
            FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(w, -2);
            cp.topMargin = h + px(22);
            wrap.addView(caption, cp);
        }
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

    // LOCAL CHANGE: tile styles ---------------------------------------------------------------------------------------

    // The style in use: the saved one, unless the panel is previewing another.
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

    // The style names, one row each. Moving through them rebuilds the tiles behind the panel (the panel's
    // preview callback); OK saves the one shown, Back goes back to the one before.
    private void showTileStyles() {
        List<String> names = new ArrayList<>();
        for (String s : TILE_STYLES) names.add(Strings.t(s));
        // LOCAL CHANGE: the same small glass menu as the Settings menu, no longer a side panel
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

    // LOCAL CHANGE: apps per row, PER_ROW_MIN..PER_ROW_MAX, as the same small glass menu as the Settings menu (it opens in
    // its place). Live preview: moving through the list re-lays the apps, OK saves the number shown (pref "per_row"),
    // Back goes back to the one before. (It was a wide side panel first; the user found that a different style.)
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
        // LOCAL CHANGE: icons go into the bar at the bottom right, one after the other, not at a fixed x.
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(b, b);
        ilp.leftMargin = iconBar.getChildCount() == 0 ? 0 : px(ICON_GAP);
        iconBar.addView(wrap, ilp);
        tiles.add(card);
        return iv;
    }

    // The Settings icon's menu, starting on focusItem (an entry of SETTINGS_ITEMS) or on the first entry for null.
    // LOCAL CHANGE: split out of open() so the pages of this menu can come back to it with Back (see backToSettings).
    private void showSettingsMenu(String focusItem) {
        List<String> list = new ArrayList<>(Arrays.asList(SETTINGS_ITEMS));
        // LOCAL CHANGE: Play Store only when it is installed and not already one of the apps on the home screen.
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

    // LOCAL CHANGE: Back in one of the pages of the Settings menu (Wallpaper, Tile style, Apps per row, Add app) goes back to the
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
        Intent i;
        if (ADD.equals(key)) {
            showAddMenu(-1);
            return;
        } else if (SETTINGS.equals(key)) {
            showSettingsMenu(null);
            return;
        } else if (isInput(key)) {
            openInput(key);
            return;
        } else if (NETWORK.equals(key)) {
            openNetworkSettings();
            return;
        } else if (key.startsWith(QUICK)) {
            openQuick(key.substring(QUICK.length()));
            return;
        } else {
            i = launchIntent(getPackageManager(), key);
            if (i == null) return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (Exception ignored) {}
    }

    // Live TV apps an HDMI tile can open, in order. Droidlogic (Amlogic) TVs ship Live TV under their
    // own package, with TvActivity as its entry point.
    private static final String[][] LIVE_TV = {{"com.android.tv", "com.android.tv.MainActivity"},
            {"com.droidlogic.android.tv", "com.android.tv.TvActivity"}};
    private static final String INPUT_PLAYER = "com.google.android.tv.inputplayer";

    // Opens an HDMI input: the stock Live TV app first, then Droidlogic's, then whatever the TV picks.
    // Each one is tried only when the one before it can't start, so a TV where the stock app already
    // worked takes exactly the same path as before. The one exception: when the TV itself sends HDMI to
    // Google TV's input player, that goes first, because some Google TVs ship com.android.tv as a stub
    // that starts fine but only says "Hello World!" (issue #5).
    private void openInput(String id) {
        Intent view = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id));
        List<Intent> tries = new ArrayList<>();
        ResolveInfo def = getPackageManager().resolveActivity(view, PackageManager.MATCH_DEFAULT_ONLY);
        if (def != null && def.activityInfo != null && INPUT_PLAYER.equals(def.activityInfo.packageName))
            tries.add(new Intent(view).setClassName(INPUT_PLAYER, def.activityInfo.name));
        for (String[] live : LIVE_TV) {
            Intent t = new Intent(view).setClassName(live[0], live[1]);
            if (t.resolveActivity(getPackageManager()) != null) tries.add(t);
        }
        tries.add(view);
        for (Intent t : tries) {
            t.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(t);
                return;
            } catch (Exception ignored) {}
        }
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

    // OFFLINE, 1 to 3 for Wi-Fi with that many bars, or ETHERNET (any other link, which on a TV is a cable).
    private int networkState() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return OFFLINE;
        try {
            int rssi = Integer.MIN_VALUE;
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                NetworkCapabilities c = cm.getNetworkCapabilities(cm.getActiveNetwork());
                if (c == null) return OFFLINE;
                if (!c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return ETHERNET;
                if (android.os.Build.VERSION.SDK_INT >= 29) rssi = c.getSignalStrength();
            } else {
                android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
                if (ni == null || !ni.isConnected()) return OFFLINE;
                if (ni.getType() != ConnectivityManager.TYPE_WIFI) return ETHERNET;
            }
            if (rssi == Integer.MIN_VALUE) { // Android 9 and older: ask the Wi-Fi service
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null && wm.getConnectionInfo() != null) rssi = wm.getConnectionInfo().getRssi();
            }
            return 1 + WifiManager.calculateSignalLevel(rssi, 3);
        } catch (Exception e) {
            return OFFLINE;
        }
    }

    private void refreshNetwork() {
        if (netIcon == null) return;
        int state = networkState();
        if (state == netState) return;
        netState = state;
        if (state == ETHERNET) {
            netIcon.setImageDrawable(new LineIcon(LineIcon.ETHERNET));
        } else {
            // The whole Wi-Fi fan dim, with the bars it has bright on top. Offline is a slash instead.
            LineIcon fan = new LineIcon(LineIcon.WIFI);
            fan.setAlpha(0x47);
            int[] top = state == OFFLINE ? LineIcon.SLASH : state == 1 ? LineIcon.WIFI_1
                    : state == 2 ? LineIcon.WIFI_2 : LineIcon.WIFI;
            netIcon.setImageDrawable(new LayerDrawable(new Drawable[] {fan, new LineIcon(top)}));
        }
        ((View) netIcon.getParent()).setContentDescription(
                state == OFFLINE ? Strings.t("Offline") : state == ETHERNET ? "Ethernet" : "Wi-Fi");
    }

    // Wi-Fi settings (on Android TV that page lists Ethernet too), else the TV's network page, else Settings.
    private void openNetworkSettings() {
        for (String a : new String[] {Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS,
                Settings.ACTION_SETTINGS}) {
            try {
                startActivity(new Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (Exception ignored) {}
        }
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

    // The TV's own picture screen, where brightness lives: the Picture shortcut's screens first (TCL's
    // panel, then Android's Display page), then Android TV's Display & Sound page, else TV settings.
    // Only system screens are started, so it needs no permission and changes nothing by itself.
    private void openBrightness() {
        if (openQuick("picture")) return;
        Intent i = new Intent().setClassName(TV_DISPLAY_SOUND[0], TV_DISPLAY_SOUND[1]);
        if (i.resolveActivity(getPackageManager()) != null) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (Exception ignored) {}
        }
        openTvSettings();
    }

    private void openTvSettings() {
        try { startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception ignored) {}
    }

    interface Pick { void at(int which); }
    interface Icon { Drawable at(int which) throws Exception; }

    private void panel(String title, List<String> labels, Pick pick) {
        panel(title, labels, null, 0, -1, null, pick);
    }

    // A side panel in the home screen's own look: a title, then one row per choice, with the same
    // glass focus as the tiles. Back closes it. icon (or null) puts an icon of that size by each name.
    // With preview, the list starts on selected and moving through it calls preview; Back calls it
    // with selected again, to put things back.
    private void panel(String title, final List<String> labels, final Icon icon, final int size,
                       final int selected, final Pick preview, final Pick pick) {
        final Dialog d = new Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(48), px(92), px(48), px(48));

        TextView head = text(px(40), Color.WHITE, semibold);
        head.setText(title);
        head.setSingleLine(true);
        head.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.setPadding(px(24), 0, px(24), 0);
        box.addView(head);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setVerticalScrollBarEnabled(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        GradientDrawable focus = new GradientDrawable();
        focus.setCornerRadius(px(RADIUS));
        focus.setColor(0x38FFFFFF);
        list.setSelector(focus);
        // Icons load as rows appear, drawn small to save RAM.
        final Drawable[] icons = new Drawable[labels.size()];
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return labels.size(); }
            @Override public Object getItem(int pos) { return labels.get(pos); }
            @Override public long getItemId(int pos) { return pos; }
            @Override public View getView(int pos, View convert, ViewGroup parent) {
                TextView t = convert != null ? (TextView) convert : text(px(30), 0xE6FFFFFF, regular);
                t.setText(labels.get(pos));
                t.setSingleLine(true);
                t.setEllipsize(android.text.TextUtils.TruncateAt.END);
                t.setGravity(Gravity.CENTER_VERTICAL);
                t.setMinHeight(px(88));
                t.setPadding(px(24), 0, px(24), 0);
                if (icon != null && icons[pos] == null) {
                    try { icons[pos] = icon.at(pos); }
                    catch (Exception e) { icons[pos] = new ColorDrawable(Color.TRANSPARENT); }
                    icons[pos].setBounds(0, 0, size, size);
                }
                t.setCompoundDrawablesRelative(icons[pos], null, null, null);
                t.setCompoundDrawablePadding(px(24));
                return t;
            }
        });
        list.setOnItemClickListener((parent, v, pos, id) -> {
            d.dismiss();
            pick.at(pos);
        });
        if (preview != null) {
            list.setSelection(selected);
            list.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                    preview.at(pos);
                }
                @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
            });
            d.setOnCancelListener(x -> preview.at(selected));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1f);
        lp.topMargin = px(36);
        box.addView(list, lp);

        d.setContentView(box);
        Window w = d.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(preview != null ? 0xFF161113 : 0xF7161113));
        w.setLayout(px(640), -1);
        w.setGravity(Gravity.END);
        w.setDimAmount(preview != null ? 0f : 0.5f); // a preview needs the home screen undimmed
        d.show();
    }

    // A small menu that drops down under a tile, in the same glass as the tiles. Opens above the
    // tile when there's no room below. Back closes it.
    private void dropdown(View anchor, String[] items, final Pick pick) {
        dropdown(anchor, items, -1, null, null, 0, pick, null);
    }

    private void dropdown(View anchor, String[] items, final int selected, final Pick preview, final Icon icon,
                          final int iconSize, final Pick pick) {
        dropdown(anchor, items, selected, preview, icon, iconSize, pick, null);
    }

    // LOCAL CHANGE: with a preview, the list starts on item `selected`, focusing an item calls preview with it, and Back calls
    // it with `selected` again, to put things back (the same contract as panel()). With icon (or null), each item gets an icon
    // of iconSize px. A list taller than the room on screen scrolls inside the menu. Used by the Settings menu's own pages
    // (Wallpaper, Tile style, Apps per row), so they all look like the Settings menu.
    // LOCAL CHANGE: back (or null) runs after Back (not after a pick), once the preview has been put back: a page of the
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
        int y = tileBottom + (anchor.getHeight() < px(100) ? px(12) : px(64)); // LOCAL CHANGE: an icon has no name underneath, so less gap
        int roomBelow = getResources().getDisplayMetrics().heightPixels - px(24) - y;
        if (h > roomBelow) {
            // LOCAL CHANGE: no room below: on the side with the most room, no taller than that room (a longer list scrolls).
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
        // LOCAL CHANGE: an icon at the right edge of the screen: line the menu's right edge up with the icon's instead of running off the screen.
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
                focusedKey = rowApps.isEmpty() ? SETTINGS : rowApps.get(Math.min(index, rowApps.size() - 1)); // LOCAL CHANGE: no Add tile any more
                buildTiles();
            }
        });
    }

    // Hold OK on an HDMI tile: name it after the box plugged into it, or take it off the home
    // screen. Hiding keeps the input in the TV's list, so nothing about the TV changes.
    private void showInputMenu(final String id) {
        View anchor = tile(id);
        if (anchor == null) return;
        dropdown(anchor, new String[] {"Rename", "Remove from home"}, which -> {
            if (which == 0) showInputNames(id);
            else hideInput(id);
        });
    }

    // The TV's own name comes first, and picking it resets the custom one; the rest are the usual
    // things people plug in, followed by an option to type a name with the TV's keyboard.
    private void showInputNames(final String id) {
        TvInputInfo in = inputById(id);
        if (in == null) return;
        String own = tvInputLabel(in);
        List<String> names = new ArrayList<>();
        names.add(own);
        for (String p : INPUT_PRESETS) if (!p.equals(own)) names.add(p);
        // Keep canonical preset names in storage so a language change also updates the tile.
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) labels.add(i == 0 ? own : Strings.t(names.get(i)));
        labels.add(Strings.t("Type a name…"));
        panel(Strings.t("Rename"), labels, which -> {
            if (which == names.size()) {
                showInputNameEditor(id, own);
                return;
            }
            setInputName(id, which == 0 ? null : names.get(which));
            focusedKey = id;
            buildTiles();
        });
    }

    private void showInputNameEditor(final String id, String own) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert);
        EditText name = new EditText(builder.getContext());
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        name.setImeOptions(EditorInfo.IME_ACTION_DONE);
        name.setFilters(new InputFilter[] {new InputFilter.LengthFilter(24)});
        String custom = inputNames().get(id);
        name.setText(custom != null ? custom : own);
        final String initialName = name.getText().toString();
        name.setSelectAllOnFocus(true);
        name.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(32));
        name.setTextColor(Color.WHITE);
        FrameLayout box = new FrameLayout(builder.getContext());
        box.setPadding(px(40), px(16), px(40), 0);
        box.addView(name, new FrameLayout.LayoutParams(-1, -2));
        final AlertDialog dialog = builder.setTitle(Strings.t("Rename"))
                .setView(box)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        Runnable save = () -> {
            setInputName(id, name.getText().toString());
            dialog.dismiss();
            focusedKey = id;
            buildTiles();
        };
        name.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_DONE) {
                save.run();
                return true;
            }
            return false;
        });
        Window window = dialog.getWindow();
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(RADIUS));
        bg.setColor(0xFF231D1F);
        bg.setStroke(Math.max(1, px(2)), 0x17FFFFFF);
        window.setBackgroundDrawable(bg);
        dialog.show();
        window.setLayout(px(800), ViewGroup.LayoutParams.WRAP_CONTENT);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> save.run());
        name.requestFocus();
        name.selectAll();
        name.post(() -> {
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .showSoftInput(name, InputMethodManager.SHOW_IMPLICIT);
            // Some TV keyboards move the cursor on startup; reselect after their opening animation.
            name.postDelayed(() -> {
                if (dialog.isShowing() && name.hasFocus()
                        && initialName.equals(name.getText().toString())) name.selectAll();
            }, 500);
        });
    }

    private void hideInput(String id) {
        Set<String> hidden = hiddenInputs();
        hidden.add(id);
        saveHiddenInputs(hidden);
        focusedKey = SETTINGS; // LOCAL CHANGE: no Add tile any more
        buildTiles();
    }

    // Long-press on the + tile: the inputs that were taken off the home screen, to put back.
    private void showHiddenInputs() {
        final List<String> ids = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        Set<String> hidden = hiddenInputs();
        for (TvInputInfo in : allInputs()) {
            if (!hidden.contains(in.getId())) continue;
            ids.add(in.getId());
            labels.add(inputLabel(in));
        }
        if (ids.isEmpty()) return;
        panel(Strings.t("Show hidden inputs"), labels, which -> {
            Set<String> now = hiddenInputs();
            now.remove(ids.get(which));
            saveHiddenInputs(now);
            focusedKey = ids.get(which);
            buildTiles();
        });
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, 0);
    }

    // Custom input names, keyed by the input's id. Split only at the first '=', so names can contain '='.
    private Map<String, String> inputNames() {
        Map<String, String> out = new HashMap<>();
        for (String line : prefs().getString(PREF_INPUT_NAMES, "").split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) out.put(line.substring(0, eq), line.substring(eq + 1));
        }
        return out;
    }

    // A null or blank name goes back to the TV's own label. Keep names on one storage line.
    private void setInputName(String id, String name) {
        if (name != null) {
            name = name.replace("\r", "").replace("\n", "").trim();
            if (name.isEmpty()) name = null;
        }
        Map<String, String> names = inputNames();
        if (name == null) names.remove(id);
        else names.put(id, name);
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> e : names.entrySet()) {
            if (out.length() > 0) out.append('\n');
            out.append(e.getKey()).append('=').append(e.getValue());
        }
        prefs().edit().putString(PREF_INPUT_NAMES, out.toString()).apply();
    }

    private Set<String> hiddenInputs() {
        Set<String> out = new HashSet<>();
        for (String id : prefs().getString(PREF_HIDDEN_INPUTS, "").split("\n")) if (!id.isEmpty()) out.add(id);
        return out;
    }

    private void saveHiddenInputs(Set<String> ids) {
        prefs().edit().putString(PREF_HIDDEN_INPUTS, android.text.TextUtils.join("\n", new TreeSet<>(ids))).apply();
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
        // LOCAL CHANGE: the same small glass menu as the rest of the Settings menu, under the Settings icon (add) or the app being
        // replaced. It has no title: its place on screen says which one it is.
        View anchor = replaceIndex < 0 ? null : tile(rowApps.get(replaceIndex));
        if (anchor == null) anchor = tile(SETTINGS);
        if (anchor == null) return;
        // LOCAL CHANGE: Back goes back to where this was opened from: the Settings menu (add) or the app's own menu (replace).
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
        return px(EDGE) + (i % perRow()) * (tileW + colGap); // LOCAL CHANGE: EDGE (64) instead of MARGIN (96); colGap = GAP, or TEXT_GAP in the text styles
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

    private static boolean isInput(String key) {
        return key.indexOf('/') > 0;
    }

    // The TV's own HDMI inputs (not devices behind them), in order, skipping the ones taken off
    // the home screen from the tile menu.
    private List<TvInputInfo> hdmiInputs() {
        List<TvInputInfo> out = new ArrayList<>();
        Set<String> hidden = hiddenInputs();
        for (TvInputInfo in : allInputs()) if (!hidden.contains(in.getId())) out.add(in);
        return out.size() > MAX_INPUTS ? out.subList(0, MAX_INPUTS) : out;
    }

    // HDMI inputs are what a home screen shows. install.sh's FAKE_INPUT=1 takes every TV input
    // instead, so the tiles and their menus can be tested on an emulator that has no HDMI of its own.
    private boolean countsAsInput(TvInputInfo in) {
        boolean fake = "1".equals(prefs().getString(PREF_FAKE_INPUTS, null));
        return (fake || in.getType() == TvInputInfo.TYPE_HDMI) && in.getParentId() == null && !in.isHidden(this);
    }

    // Every HDMI input the TV reports that isn't switched off in its own settings, hidden or not.
    private List<TvInputInfo> allInputs() {
        List<TvInputInfo> out = new ArrayList<>();
        TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return out;
        for (TvInputInfo in : tim.getTvInputList()) if (countsAsInput(in)) out.add(in);
        Collections.sort(out, (a, b) -> a.getId().compareTo(b.getId()));
        return out;
    }

    // A TV has inputs of its own (HDMI, AV); a box that plugs into one, like NVIDIA Shield, Chromecast
    // or a Mi Box, has none. Some TVs register their HDMI inputs a while after starting (#52), so with
    // none yet it's a box only if HDMI-CEC says it's a playback device and not a TV. When unsure, it's
    // a TV, so TVs keep their menu.
    static boolean isBox(Context c) {
        TvInputManager tim = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim != null) for (TvInputInfo in : tim.getTvInputList()) if (in.isPassthroughInput()) return false;
        List<String> types = cecTypes("ro.hdmi.device_type"), named = cecTypes("ro.hdmi.cec_device_types");
        if (types.contains("0") || named.contains("tv")) return false;
        return types.contains("4") || named.contains("playback_device");
    }

    private static List<String> cecTypes(String prop) {
        try {
            String v = String.valueOf(Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class).invoke(null, prop)).trim();
            if (!v.isEmpty()) return Arrays.asList(v.split("\\s*,\\s*"));
        } catch (Throwable ignored) {}
        return Collections.emptyList();
    }

    private TvInputInfo inputById(String id) {
        for (TvInputInfo in : allInputs()) if (in.getId().equals(id)) return in;
        return null;
    }

    // The name picked on the TV, else the one set in Settings > Inputs (like "Bedroom"), else the
    // TV's own name (like "HDMI 1").
    private String inputLabel(TvInputInfo in) {
        String custom = inputNames().get(in.getId());
        return custom != null ? Strings.t(custom) : tvInputLabel(in);
    }

    private String tvInputLabel(TvInputInfo in) {
        String custom = inputLabelOrNull(in.loadCustomLabel(this));
        if (custom != null) return custom;
        String label = inputLabelOrNull(in.loadLabel(this));
        return label != null ? label : "HDMI";
    }

    private static String inputLabelOrNull(CharSequence raw) {
        if (raw == null || raw.length() == 0) return null;
        String label = withoutDeviceType(raw.toString());
        return "Philips".equalsIgnoreCase(android.os.Build.MANUFACTURER)
                && (label.isEmpty() || "null".equals(label)) ? null : label;
    }

    // Philips stores a name as "name&DEVICE_TYPE" (like "Computer&COMPUTER"), so show the name only.
    // Missing Philips names/types can be the literal "null"; a missing name uses the next fallback.
    // Only on Philips TVs: elsewhere a name like "AUDIO&VIDEO" is the name itself.
    private static String withoutDeviceType(String label) {
        if (!"Philips".equalsIgnoreCase(android.os.Build.MANUFACTURER)) return label;
        int amp = label.lastIndexOf('&');
        if (amp >= 0 && (label.endsWith("&") || label.endsWith("&null"))) return label.substring(0, amp).trim();
        return amp > 0 && label.substring(amp + 1).matches("[A-Z][A-Z0-9_]{2,}") ? label.substring(0, amp).trim() : label;
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
