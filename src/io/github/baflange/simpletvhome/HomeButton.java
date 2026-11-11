package io.github.baflange.simpletvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

// Opt-in (Settings > Accessibility > Home button): makes Home open this home screen on TVs where the
// TV's own home screen has to stay on, like Google TV and Chromecast. Nothing gets turned off, so
// switching it off puts Home straight back to the TV's own.
//
// Google TV handles the Home key before accessibility services see it, so Home itself is not watched.
// It watches for Google TV's main screen (not its other pages) to open after Home, and opens this one over it.
// After a TV start that window opens before the service is running, so the service also opens this home screen
// once, right after it connects (see onServiceConnected).
//
// It sees no key presses: res/xml/home_button.xml does not ask for key events, and there is no onKeyEvent here.
// (Remote buttons that open apps are a separate add-on app; see the README.)
// The app has no INTERNET permission, so nothing can leave the TV.
public class HomeButton extends AccessibilityService {
    private static final ComponentName GOOGLE_TV_HOME = new ComponentName(
            "com.google.android.apps.tv.launcherx", "com.google.android.apps.tv.launcherx.home.HomeActivity");
    // Debounce duplicate events until the activity resumes.
    private static long lastOpened = -500;

    // Google TV starts its home a SECOND time 0.3-0.5 s after every Home press
    // (an intent with NEW_TASK|CLEAR_TOP, seen in the system log). When that second start lands after ours, Google TV
    // ends up on top, and every later Home press only re-delivers an intent to a home that is already in front:
    // no new window, no event, so this service did nothing and the user could not get to this home any more.
    // So after every Google TV home event we look again shortly after, and open this home again if it is not in front.
    private static final long VERIFY_MS = 900;
    // The service only counts as "just after a TV start" when it connects within this time of the TV starting.
    private static final long START_WINDOW_MS = 3 * 60 * 1000;
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
        Log.i("TvHome", "Google TV was in front again after Home: opening this home again");
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
        openOwnHome();
    }

    // A TV start brings Google TV's home up BEFORE this service has connected: Android only starts accessibility
    // services once the TV is unlocked, and then the app's process has to start first (about 0.1 s here, more on a busy
    // start). So the window event above is missed, and Google TV stays in front until its next window (the next Home).
    // Once per TV start, right after the service connects, we therefore treat Google TV's home as the screen in front.
    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        if (SystemClock.elapsedRealtime() > START_WINDOW_MS || !setupDone()) return;
        SharedPreferences prefs = prefs(this);
        if (startHandled(prefs)) return;
        prefs.edit().putInt("start_count", bootCount(this)).putLong("start_time", bootTime()).commit();
        googleTvHomeLast = true;
        openOwnHome();
    }

    // The TV start (see onServiceConnected) was already handled once: not again when the service reconnects.
    private boolean startHandled(SharedPreferences prefs) {
        int boot = bootCount(this);
        int saved = prefs.getInt("start_count", -1);
        if (boot >= 0 && saved >= 0) return boot == saved;
        return Math.abs(bootTime() - prefs.getLong("start_time", 0)) < 5000;
    }

    // Opens this home screen over Google TV's, and looks again shortly after (see VERIFY_MS).
    private void openOwnHome() {
        SharedPreferences prefs = prefs(this);
        if (stayingOnGoogleTv(prefs)) return;
        HANDLER.removeCallbacks(verify); // Look again shortly, whatever happens below
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
