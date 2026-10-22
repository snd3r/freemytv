package io.freemytv;

/**
 * The screensaver on Android 11. There the system's DreamActivity runs in the app's default process and only works
 * with the DreamService in that same process, which can't also hold a home activity (#10). So Android 11 gets this
 * copy, in the main process; every other version keeps Screensaver in :home (app/res/values-v30/bools.xml).
 */
public class ScreensaverAlone extends Screensaver {
}
