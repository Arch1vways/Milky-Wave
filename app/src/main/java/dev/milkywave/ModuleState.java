package dev.milkywave;

/**
 * Set to true by {@link HookEntry} when LSPosed loads the module into this app itself
 * (the app is part of the module scope). Used to tell "enabled but not yet loaded in the
 * hooked process" apart from "not enabled at all": if this is true while System UI / the
 * launcher does not answer, the module was just enabled and a soft reboot is needed.
 */
public final class ModuleState {
    public static volatile boolean loaded;

    private ModuleState() { }

    public static boolean loadedHere() {
        return loaded;
    }

    /**
     * Called from the Xposed side for a loaded package. Only the module's own app contains this class, so the
     * lookup finds the app itself regardless of its package name (no hardcoded or build-time id needed).
     *
     * @return true if {@code loader} belongs to the module's own app
     */
    static boolean markLoaded(ClassLoader loader) {
        if (loader == null) return false;
        try {
            Class.forName(ModuleState.class.getName(), true, loader).getField("loaded").setBoolean(null, true);
            return true;
        } catch (Throwable notOurApp) {
            return false;
        }
    }
}
