# Simple TV Home

A simple, fast home screen for Android TV and Google TV: rows of app tiles, a clock and a Settings menu to add, move,
replace and remove apps, pick a tile style, a wallpaper and how many apps fit in a row.

Made and tested on an **Epson EF-61W** projector (Google TV, Android 14). It should work on other Android TV / Google TV
devices (Android 5.0+), but that is not tested.

> Nederlandse installatiegids: [INSTALLEREN.md](INSTALLEREN.md)

## Features

- Default app list for the Netherlands/Europe (`apps.txt`); change it before building, or use Settings > Add app on the TV.
- **Ten photo wallpapers** (see below), with a wallpaper list that shows thumbnails.
- Photos are cropped and darkened **once, on the computer** (`tools/BakeWallpapers.java`) and stored as WebP, so the TV
  only has to show a ready-made picture and the APK stays small (about 3 MB).
- Just apps, the clock and a Settings icon (bottom right): no HDMI-input tiles, no network tile.
- Five tile styles (Original, Muted, Black & white, Text on glass, Text only) and 2 to 7 apps per row.
- Pressing **Back** in a Settings page (Wallpaper, Tile style, Apps per row, Add app) returns to the Settings menu.
- **Add app** and **Replace** open a menu with the app icons, in the same style as the rest of Settings.
- On Google TV, pressing **Home** in an app, and starting the TV after it was off, reliably brings this home screen
  (the "Home button" service checks again after Google TV's own home screen has started, and once right after a TV start).
- A **Settings > Remote buttons** line that appears only when the author's separate add-on app is installed; see "Remote buttons" below.

## Privacy and permissions

- The app has **no INTERNET permission**: it cannot send anything off the TV.
- It reads the list of installed apps. It can open the uninstall prompt for an app, and nothing else. It asks for no
  other permissions.
- On Google TV the home screen needs the **"Home button" accessibility service** to take over Home. You switch it on
  yourself in Settings > Accessibility; switching it off gives you Google TV's own home screen back. It is configured to
  see only when Google TV's own home screen (`com.google.android.apps.tv.launcherx`) comes to the front, and it cannot
  read the screen content (`canRetrieveWindowContent="false"`).
- The service **does not see key presses**: `res/xml/home_button.xml` does not ask for key events and `HomeButton.java`
  has no key handling. You can check this yourself: `HomeButton.java` is one short file and the XML is ten lines.

## Remote buttons (a separate add-on, not part of this app)

This home screen never sees the remote's key presses. Making a remote button (such as YouTube or Prime) open an app needs
a key filter, and that lives in a **separate add-on app** with its own package name, `local.beamer.remotebuttons`. The author
keeps that add-on private; it is **not** in this repository, and this APK works fully without it.

If an app with that package name is installed, **Settings > Remote buttons** shows up in the Settings menu and opens the
add-on's own settings screen. Without the add-on that line is hidden.

## Install

- Friends / without building: see [INSTALLEREN.md](INSTALLEREN.md) (download the APK from the Releases page).
- With a computer and `adb`: put `tvhome.apk` (from Releases) next to `install.sh`, or build it yourself, then run

  ```sh
  sh supports.sh <TV_IP>     # looks at the TV first, changes nothing
  sh install.sh <TV_IP>      # installs; on Google TV the TV's own home screen stays on
  sh undo.sh <TV_IP>         # removes this home screen again
  ```

  On Google TV, install.sh leaves Google TV's own home screen on (turning it off has left some devices stuck restarting).
  Switch on Settings > Accessibility > **Home button** to make Home open this home screen.
  When you reinstall, do **not** pass an apps argument, so the list you set on the TV is kept.

## Build

Needs Java 17 and the Android SDK (platform 34, build-tools 34.0.0); see the top of `build.sh`.

```sh
sh build.sh        # writes out/tvhome.apk, signed with key.jks
```

A signing key (`key.jks`) is created on the first build. **Keep it:** an update only installs over an existing install if it
is signed with the same key. Never commit `key.jks` (it is in `.gitignore`).

To change the wallpapers, replace the originals in `wallpaper-originals/`, adjust `tools/BakeWallpapers.java` and run it
(the command is in the file header); it writes the finished WebP pictures into `res/drawable-nodpi/`. It needs Google's
`cwebp` (macOS: `brew install webp`).

## Licence and credits

- Code: MIT, see `LICENSE`.
- Wallpaper photos: from Wikimedia Commons, **CC BY / CC BY-SA** (not MIT). Names, authors, licences and the changes made
  to them are in [WALLPAPER_CREDITS.txt](WALLPAPER_CREDITS.txt). The originals are in `wallpaper-originals/`.
- Not affiliated with Epson, Google or Android. "Google TV" and other names belong to their owners.
