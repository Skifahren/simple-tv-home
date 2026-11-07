package com.ansub.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

// Opt-in (Settings > Accessibility > Home button): makes Home open this home screen on TVs where the
// TV's own home screen has to stay on, like Google TV and Chromecast. Nothing gets turned off, so
// switching it off puts Home straight back to the TV's own.
//
// Google TV handles the Home key before accessibility services see it, so Home itself is not watched.
// It watches for Google TV's main screen (not its other pages) to open, after Home or a restart,
// and opens this one over it.
//
// LOCAL CHANGE (2026-11-06): the code can ALSO filter key events so the remote's YouTube and Prime buttons open
// apps you pick. That is OFF in this public build: res/xml/home_button.xml does not ask for key events, and
// PRIME_KEY / YOUTUBE_KEY are -1, so onKeyEvent is never called (see the note at those constants to turn it on).
// When on, onKeyEvent only acts on those two key codes and returns false for every other key. Unusual key codes
// (never letters, digits or the D-pad) are written to logcat tag "TvHomeKeys" to find a button's code
// (e.g. the Live TV button, which the user changes themselves).
// The app has no INTERNET permission, so nothing can leave the TV.
public class HomeButton extends AccessibilityService {
    // LOCAL CHANGE: remote buttons -> apps. A key code of -1 means "not set yet".
    private static final String PRIME_TARGET = "org.jellyfin.androidtv";
    private static final String YOUTUBE_TARGET = "org.smarttube.stable";
    // Measured on the Epson remote ("RemoteB064") on 2026-11-06: Prime = keyCode 249 (scanCode 63),
    // YouTube = KEYCODE_BUTTON_3 (190, scanCode 240). Netflix (265) is deliberately left alone.
    // OFF in this public build (-1). To use them: put the codes back (249 and KeyEvent.KEYCODE_BUTTON_3), and add
    // android:canRequestFilterKeyEvents="true" and android:accessibilityFlags="flagRequestFilterKeyEvents" back to
    // res/xml/home_button.xml (without them Android never calls onKeyEvent). Then build and install again.
    private static final int PRIME_KEY = -1;
    private static final int YOUTUBE_KEY = -1;

    private static final ComponentName GOOGLE_TV_HOME = new ComponentName(
            "com.google.android.apps.tv.launcherx", "com.google.android.apps.tv.launcherx.home.HomeActivity");
    // Debounce duplicate events until the activity resumes.
    private static long lastOpened = -500;

    // LOCAL CHANGE (Beamer, 2026-11-07): Google TV starts its home a SECOND time 0.3-0.5 s after every Home press
    // (an intent with NEW_TASK|CLEAR_TOP, seen in the system log). When that second start lands after ours, Google TV
    // ends up on top, and every later Home press only re-delivers an intent to a home that is already in front:
    // no new window, no event, so this service did nothing and the user could not get to this home any more.
    // So after every Google TV home event we look again shortly after, and open this home again if it is not in front.
    private static final long VERIFY_MS = 900;
    private static final android.os.Handler HANDLER = new android.os.Handler(android.os.Looper.getMainLooper());
    private static volatile boolean homeVisible; // set by MainActivity.onResume / onPause
    private boolean googleTvHomeLast; // the last Google TV window that opened was its home screen (not e.g. an account page)
    private final Runnable verify = this::reopenIfBehind;

    static void setHomeVisible(boolean visible) { homeVisible = visible; }

    @Override
    public void onDestroy() {
        HANDLER.removeCallbacks(verify);
        super.onDestroy();
    }

    private void reopenIfBehind() {
        if (homeVisible || !googleTvHomeLast || !setupDone()) return;
        SharedPreferences prefs = prefs(this);
        if (stayingOnGoogleTv(prefs) || prefs.getInt("failures", 0) >= 3) return;
        lastOpened = SystemClock.elapsedRealtime();
        Log.i("TvHomeKeys", "Google TV was in front again after Home: opening this home again");
        try {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {}
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || !GOOGLE_TV_HOME.getPackageName().equals(String.valueOf(event.getPackageName()))) return;
        // The service only hears Google TV's windows (see home_button.xml): note whether the newest one is its home.
        googleTvHomeLast = GOOGLE_TV_HOME.getClassName().equals(String.valueOf(event.getClassName()));
        if (!googleTvHomeLast || !setupDone()) return;
        SharedPreferences prefs = prefs(this);
        if (stayingOnGoogleTv(prefs)) return;
        HANDLER.removeCallbacks(verify); // LOCAL CHANGE: look again shortly, whatever happens below
        HANDLER.postDelayed(verify, VERIFY_MS);
        long now = SystemClock.elapsedRealtime();
        if (now - lastOpened < 500) return;

        // Give an unfinished launch time to resume. Successful launches don't delay the next Home.
        long pending = prefs.getLong("pending", -1);
        if (pending >= 0 && now >= pending && now - pending < 3000) return;
        int failures = prefs.getInt("failures", 0);
        if (pending >= 0) failures++;
        if (failures >= 3) {
            prefs.edit().remove("pending").putInt("failures", failures).commit();
            return;
        }
        // Save before launching: a crash can take the service down with the activity.
        if (!prefs.edit().putLong("pending", now).putInt("failures", failures).commit()) return;
        lastOpened = now;
        try {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {}
    }

    @Override
    public void onInterrupt() {}

    // LOCAL CHANGE: the remote's YouTube / Prime buttons. Everything else goes on to the TV untouched.
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        String target = code == PRIME_KEY ? PRIME_TARGET : code == YOUTUBE_KEY ? YOUTUBE_TARGET : null;
        if (target == null) {
            // LOCAL CHANGE: observation only. Does the remote's Home key reach this filter before the TV's own handling?
            if (code == KeyEvent.KEYCODE_HOME && event.getAction() == KeyEvent.ACTION_DOWN)
                Log.i("TvHomeKeys", "HOME key reached the filter (device=" + event.getDeviceId() + ")");
            if (event.getAction() == KeyEvent.ACTION_DOWN && !isCommonKey(code)) {
                Log.i("TvHomeKeys", "unusual key: keyCode=" + code + " scanCode=" + event.getScanCode()
                        + " device=" + event.getDeviceId());
            }
            return false;
        }
        Intent launch = launchIntent(target);
        if (launch == null) return false; // app not installed: leave the key to the TV
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            try { startActivity(launch); } catch (Exception ignored) {}
        }
        return true;
    }

    private Intent launchIntent(String pkg) {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
        return i == null ? null : i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    // Letters, digits, D-pad, volume, Back/Home/Menu/OK and media keys are never logged.
    private static boolean isCommonKey(int c) {
        return (c >= 7 && c <= 54) || c == 3 || c == 4 || c == 62 || c == 66 || c == 67 || c == 82
                || (c >= 85 && c <= 91) || c == 126 || c == 127 || c == 130 || c == 164;
    }

    // During the TV's first setup, Home is left alone.
    private boolean setupDone() {
        return Settings.Secure.getInt(getContentResolver(), "user_setup_complete", 1) == 1;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("home_button", 0);
    }

    // Back on this home screen: Home comes here again.
    static void onHomeResumed(Context c) {
        prefs(c).edit().remove("google_tv_boot").remove("google_tv_boot_count").commit();
    }

    // This home screen opened and didn't crash. Also resets a stopped redirect loop.
    static void onHomeOk(Context c) {
        prefs(c).edit().remove("pending").remove("failures").commit();
        lastOpened = -500;
    }

    private static int bootCount(Context c) {
        return Build.VERSION.SDK_INT >= 24
                ? Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, -1) : -1;
    }

    private static long bootTime() {
        return System.currentTimeMillis() - SystemClock.elapsedRealtime();
    }

    private boolean stayingOnGoogleTv(SharedPreferences prefs) {
        if (!prefs.contains("google_tv_boot")) return false;
        int boot = bootCount(this);
        int saved = prefs.getInt("google_tv_boot_count", -1);
        if (boot >= 0 && saved >= 0) return boot == saved;
        return Math.abs(bootTime() - prefs.getLong("google_tv_boot", 0)) < 5000;
    }

    // Opens Google TV's own home screen and keeps it there. False if this TV doesn't have it.
    static boolean openGoogleTv(Context c) {
        SharedPreferences prefs = prefs(c);
        // Keep the choice through process death and updates, but not a TV restart.
        if (!prefs.edit().putLong("google_tv_boot", bootTime())
                .putInt("google_tv_boot_count", bootCount(c)).commit()) return false;
        try {
            c.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setComponent(GOOGLE_TV_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Exception e) {
            prefs.edit().remove("google_tv_boot").remove("google_tv_boot_count").commit();
            return false;
        }
    }
}
