package io.github.fix.audio;

import android.app.Activity;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class AudioFixHook implements IXposedHookLoadPackage {

    private static final String TAG = "[AudioFix] ";

    // Client-side state tracking for instant focus & volume recovery
    private static volatile WeakReference<Object> sUnityPlayerInstance = null;
    private static final Set<AudioManager.OnAudioFocusChangeListener> sFocusListeners =
            Collections.newSetFromMap(new ConcurrentHashMap<AudioManager.OnAudioFocusChangeListener, Boolean>());
    private static final Set<WeakReference<AudioTrack>> sActiveTracks =
            Collections.newSetFromMap(new ConcurrentHashMap<WeakReference<AudioTrack>, Boolean>());

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) throws Throwable {
        if ("android".equals(lpparam.packageName)) {
            hookSystemServer(lpparam);
        } else if (!isSystemPackage(lpparam.packageName)) {
            hookClientApp(lpparam);
        }
    }

    private static boolean isSystemPackage(String pkg) {
        if (pkg == null) return true;
        return pkg.equals("android") || pkg.equals("system")
                || pkg.startsWith("com.android.")
                || pkg.startsWith("org.lineageos.")
                || pkg.startsWith("com.google.android.providers.");
    }

    private static boolean isGamePackage(String pkg) {
        if (pkg == null) return false;
        String lower = pkg.toLowerCase();
        return lower.contains("game")
                || lower.contains("subway")
                || lower.contains("sybo")
                || lower.contains("kiloo")
                || lower.contains("unity")
                || lower.contains("unreal")
                || lower.contains("roblox")
                || lower.contains("pryshedko")
                || lower.contains("genshin")
                || lower.contains("hoyoverse")
                || lower.contains("mojang")
                || lower.contains("minecraft")
                || lower.contains("pubg")
                || lower.contains("mobilelegends")
                || lower.contains("riotgames")
                || lower.contains("supercell")
                || lower.contains("pebble");
    }

    private static boolean isAdCaller() {
        try {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            for (StackTraceElement elem : stack) {
                String cls = elem.getClassName().toLowerCase();
                if (cls.contains("com.google.android.gms.ads")
                        || cls.contains("com.google.ads")
                        || cls.contains("com.applovin")
                        || cls.contains("com.unity3d.services")
                        || cls.contains("com.unity3d.ads")
                        || cls.contains("com.ironsource")
                        || cls.contains("com.inmobi")
                        || cls.contains("com.moloco")
                        || cls.contains("com.vungle")
                        || cls.contains("com.bytedance.sdk")
                        || cls.contains("com.pangle")
                        || cls.contains("com.chartboost")
                        || cls.contains("org.chromium")
                        || cls.contains("android.webkit")
                        || cls.contains("com.adcolony")
                        || cls.contains("com.fyber")
                        || cls.contains("com.hyprmx")
                        || cls.contains("com.facebook.ads")
                        || cls.contains("sybo.ads")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean isAdString(String str) {
        if (str == null) return false;
        String lower = str.toLowerCase();
        return lower.contains("chromium")
                || lower.contains("audiofocusdelegate")
                || lower.contains("google.android.gms.ads")
                || lower.contains("applovin")
                || lower.contains("ironsource")
                || lower.contains("inmobi")
                || lower.contains("moloco")
                || lower.contains("unity3d.services")
                || lower.contains("unity3d.ads")
                || lower.contains("vungle")
                || lower.contains("pangle")
                || lower.contains("chartboost")
                || lower.contains("adcolony")
                || lower.contains("fyber")
                || lower.contains("sybo.ads");
    }

    private static boolean isGameRequester(Object focusRequester) {
        if (focusRequester == null) return false;
        try {
            String pkg = (String) XposedHelpers.getObjectField(focusRequester, "mPackageName");
            if (isGamePackage(pkg)) return true;
            Object attr = XposedHelpers.getObjectField(focusRequester, "mAttributes");
            if (attr != null) {
                int usage = XposedHelpers.getIntField(attr, "mUsage");
                if (usage == AudioAttributes.USAGE_GAME) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void hookSystemServer(LoadPackageParam lpparam) {
        XposedBridge.log(TAG + "Initializing in system_server (android)...");

        // 1. Hook PlaybackActivityMonitor to prevent server-side volume ducking and fading
        try {
            Class<?> pamClass = XposedHelpers.findClassIfExists(
                    "com.android.server.audio.PlaybackActivityMonitor", lpparam.classLoader);
            if (pamClass != null) {
                XposedBridge.hookAllMethods(pamClass, "duckPlayers", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        XposedBridge.log(TAG + "Suppressed PlaybackActivityMonitor.duckPlayers");
                        param.setResult(true);
                    }
                });

                XposedBridge.hookAllMethods(pamClass, "fadeOutPlayers", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        XposedBridge.log(TAG + "Suppressed PlaybackActivityMonitor.fadeOutPlayers");
                        param.setResult(false);
                    }
                });

                XposedBridge.hookAllMethods(pamClass, "reqCausesStrongDuck", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        param.setResult(false);
                    }
                });

                XposedBridge.log(TAG + "Hooked PlaybackActivityMonitor successfully.");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Error hooking PlaybackActivityMonitor: " + t.getMessage());
        }

        // 2. Hook PlaybackActivityMonitor$DuckingManager to prevent tracking ducked UIDs
        try {
            Class<?> duckingManagerClass = XposedHelpers.findClassIfExists(
                    "com.android.server.audio.PlaybackActivityMonitor$DuckingManager", lpparam.classLoader);
            if (duckingManagerClass != null) {
                XposedBridge.hookAllMethods(duckingManagerClass, "duckUid", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Object uid = param.args.length > 0 ? param.args[0] : "unknown";
                        XposedBridge.log(TAG + "Suppressed DuckingManager.duckUid for uid=" + uid);
                        param.setResult(null);
                    }
                });

                XposedBridge.hookAllMethods(duckingManagerClass, "checkDuck", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        param.setResult(null);
                    }
                });

                XposedBridge.log(TAG + "Hooked DuckingManager successfully.");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Error hooking DuckingManager: " + t.getMessage());
        }

        // 3. Hook MediaFocusControl to shield games and prevent duck notifications
        try {
            Class<?> mfcClass = XposedHelpers.findClassIfExists(
                    "com.android.server.audio.MediaFocusControl", lpparam.classLoader);
            if (mfcClass != null) {
                XposedBridge.hookAllMethods(mfcClass, "duckPlayers", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        XposedBridge.log(TAG + "Suppressed MediaFocusControl.duckPlayers");
                        param.setResult(true);
                    }
                });

                XposedBridge.hookAllMethods(mfcClass, "fadeOutPlayers", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        XposedBridge.log(TAG + "Suppressed MediaFocusControl.fadeOutPlayers");
                        param.setResult(false);
                    }
                });

                XposedBridge.hookAllMethods(mfcClass, "mustNotifyFocusOwnerOnDuck", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        param.setResult(false);
                    }
                });

                // Isolate in-game ad focus requests by granting them directly without touching mFocusStack
                XposedBridge.hookAllMethods(mfcClass, "requestAudioFocus", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        String clientId = null;
                        String callingPkg = null;
                        for (Object arg : param.args) {
                            if (arg instanceof String) {
                                String str = (String) arg;
                                if (clientId == null && (str.contains("@") || str.contains("android.media") || str.contains("chromium"))) {
                                    clientId = str;
                                } else if (callingPkg == null && str.contains(".")) {
                                    callingPkg = str;
                                }
                            }
                        }
                        if (clientId != null && (isAdString(clientId) || (callingPkg != null && isGamePackage(callingPkg) && isAdString(clientId)))) {
                            XposedBridge.log(TAG + "Isolated ad requestAudioFocus (granted locally without stack mutation, pkg="
                                    + callingPkg + ", client=" + clientId + ")");
                            param.setResult(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
                        }
                    }
                });

                XposedBridge.log(TAG + "Hooked MediaFocusControl successfully.");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Error hooking MediaFocusControl: " + t.getMessage());
        }

        // 4. Hook FocusRequester to shield games from focus loss eviction and negative focus changes
        try {
            Class<?> focusRequesterClass = XposedHelpers.findClassIfExists(
                    "com.android.server.audio.FocusRequester", lpparam.classLoader);
            if (focusRequesterClass != null) {
                // Prevent game FocusRequester from ever being evicted from mFocusStack
                XposedBridge.hookAllMethods(focusRequesterClass, "handleFocusLossFromGain", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (isGameRequester(param.thisObject)) {
                            String pkg = (String) XposedHelpers.getObjectField(param.thisObject, "mPackageName");
                            XposedBridge.log(TAG + "Shielded game FocusRequester (" + pkg
                                    + ") from focus loss eviction (gain=" + param.args[0] + ")");
                            param.setResult(false); // Never evicted from mFocusStack
                        }
                    }
                });

                // Prevent dispatching negative focus changes (loss or duck) to games
                XposedBridge.hookAllMethods(focusRequesterClass, "dispatchFocusChange", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (isGameRequester(param.thisObject)) {
                            for (int i = 0; i < param.args.length; i++) {
                                if (param.args[i] instanceof Integer) {
                                    int focusChange = (Integer) param.args[i];
                                    if (focusChange < 0) {
                                        String pkg = (String) XposedHelpers.getObjectField(param.thisObject, "mPackageName");
                                        XposedBridge.log(TAG + "Blocked dispatchFocusChange(" + focusChange + ") to game " + pkg);
                                        param.setResult(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
                                        return;
                                    }
                                }
                            }
                        }
                    }
                });

                XposedBridge.hookAllMethods(focusRequesterClass, "dispatchFocusChangeWithFadeLocked", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (isGameRequester(param.thisObject)) {
                            if (param.args.length > 0 && param.args[0] instanceof Integer) {
                                int focusChange = (Integer) param.args[0];
                                if (focusChange < 0) {
                                    param.setResult(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
                                }
                            }
                        }
                    }
                });

                XposedBridge.log(TAG + "Hooked FocusRequester successfully.");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Error hooking FocusRequester: " + t.getMessage());
        }

        // 5. Hook AudioService.setMode to prevent game engines from setting MODE_IN_COMMUNICATION
        try {
            Class<?> audioServiceClass = XposedHelpers.findClassIfExists(
                    "com.android.server.audio.AudioService", lpparam.classLoader);
            if (audioServiceClass != null) {
                XposedBridge.hookAllMethods(audioServiceClass, "setMode", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length > 0 && param.args[0] instanceof Integer) {
                            int mode = (Integer) param.args[0];
                            String pkg = "";
                            for (Object arg : param.args) {
                                if (arg instanceof String) {
                                    pkg = (String) arg;
                                    break;
                                }
                            }
                            // 3 = MODE_IN_COMMUNICATION, 2 = MODE_IN_CALL
                            if (mode == 3 || mode == 2) {
                                if (isGamePackage(pkg)) {
                                    XposedBridge.log(TAG + "Blocked game '" + pkg + "' from setting communication audio mode (" + mode + ")");
                                    param.setResult(null);
                                }
                            }
                        }
                    }
                });
                XposedBridge.log(TAG + "Hooked AudioService.setMode successfully.");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Error hooking AudioService: " + t.getMessage());
        }
    }

    private void hookClientApp(final LoadPackageParam lpparam) {
        XposedBridge.log(TAG + "Initializing low-latency and anti-ducking hooks in client app: " + lpparam.packageName);

        // 1. Hook VolumeShaper.Configuration.Builder to sanitize all duck curves at creation time
        try {
            Class<?> vsBuilderClass = XposedHelpers.findClassIfExists(
                    "android.media.VolumeShaper$Configuration$Builder", lpparam.classLoader);
            if (vsBuilderClass != null) {
                XposedBridge.hookAllMethods(vsBuilderClass, "setCurve", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length >= 2 && param.args[1] instanceof float[]) {
                            float[] vols = (float[]) param.args[1];
                            for (int i = 0; i < vols.length; i++) {
                                if (vols[i] < 1.0f && vols[i] > 0.0f) {
                                    vols[i] = 1.0f;
                                }
                            }
                        }
                    }
                });

                XposedBridge.hookAllMethods(vsBuilderClass, "build", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object config = param.getResult();
                        if (config != null) {
                            try {
                                float[] vols = (float[]) XposedHelpers.getObjectField(config, "mVolumes");
                                if (vols != null) {
                                    for (int i = 0; i < vols.length; i++) {
                                        if (vols[i] < 1.0f && vols[i] > 0.0f) {
                                            vols[i] = 1.0f;
                                        }
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                });
                XposedBridge.log(TAG + "Hooked VolumeShaper$Configuration$Builder in " + lpparam.packageName);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Error hooking VolumeShaper$Configuration$Builder: " + t.getMessage());
        }

        // 2. Hook PlayerBase to block client-side ducking & volume shaper attenuation
        try {
            Class<?> playerBaseClass = XposedHelpers.findClassIfExists(
                    "android.media.PlayerBase", lpparam.classLoader);
            if (playerBaseClass != null) {
                // Hook playerApplyVolumeShaper (internal PlayerBase implementation called by IPC)
                XposedBridge.hookAllMethods(playerBaseClass, "playerApplyVolumeShaper", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length > 0 && param.args[0] != null) {
                            Object config = param.args[0];
                            try {
                                float[] vols = (float[]) XposedHelpers.getObjectField(config, "mVolumes");
                                if (vols != null) {
                                    for (int i = 0; i < vols.length; i++) {
                                        if (vols[i] < 1.0f && vols[i] > 0.0f) {
                                            vols[i] = 1.0f;
                                        }
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                        // Return STATUS_OK (1) immediately to suppress volume attenuation
                        param.setResult(1);
                    }
                });

                // In case applyVolumeShaper exists directly on PlayerBase
                try {
                    XposedBridge.hookAllMethods(playerBaseClass, "applyVolumeShaper", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            param.setResult(1);
                        }
                    });
                } catch (Throwable ignored) {
                }

                // Prevent ducking volume drops via baseSetVolume
                XposedBridge.hookAllMethods(playerBaseClass, "baseSetVolume", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length >= 2 && param.args[0] instanceof Float && param.args[1] instanceof Float) {
                            float left = (Float) param.args[0];
                            float right = (Float) param.args[1];
                            if (left < 1.0f && left > 0.0f) {
                                param.args[0] = 1.0f;
                            }
                            if (right < 1.0f && right > 0.0f) {
                                param.args[1] = 1.0f;
                            }
                        }
                    }
                });

                // Prevent ducking volume drops via playerSetVolume
                XposedBridge.hookAllMethods(playerBaseClass, "playerSetVolume", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        for (int i = 0; i < param.args.length; i++) {
                            if (param.args[i] instanceof Float) {
                                float val = (Float) param.args[i];
                                if (val < 1.0f && val > 0.0f) {
                                    param.args[i] = 1.0f;
                                }
                            }
                        }
                    }
                });

                // Prevent ducking volume drops via setVolume
                XposedBridge.hookAllMethods(playerBaseClass, "setVolume", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length > 0 && param.args[0] instanceof Float) {
                            float vol = (Float) param.args[0];
                            if (vol < 1.0f && vol > 0.0f) {
                                param.args[0] = 1.0f;
                            }
                        }
                    }
                });
                XposedBridge.log(TAG + "Hooked PlayerBase in " + lpparam.packageName);
            }
        } catch (Throwable ignored) {
        }

        // 3. Hook AudioTrack to eliminate latency, prevent ducking volume drops, and register tracks
        try {
            Class<?> audioTrackClass = XposedHelpers.findClassIfExists(
                    "android.media.AudioTrack", lpparam.classLoader);
            if (audioTrackClass != null) {
                // Prevent volume multiplier drops
                XposedBridge.hookAllMethods(audioTrackClass, "setVolumeMultiplier", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length > 0 && param.args[0] instanceof Float) {
                            float mult = (Float) param.args[0];
                            if (mult < 1.0f && mult > 0.0f) {
                                param.args[0] = 1.0f;
                            }
                        }
                    }
                });

                // Prevent setVolume attenuation (< 1.0f and > 0.0f)
                XposedBridge.hookAllMethods(audioTrackClass, "setVolume", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length == 1 && param.args[0] instanceof Float) {
                            float gain = (Float) param.args[0];
                            if (gain < 1.0f && gain > 0.0f) {
                                param.args[0] = 1.0f;
                            }
                        }
                    }
                });

                // Hook setStereoVolume (critical: 2-arg volume method in AudioTrack)
                XposedBridge.hookAllMethods(audioTrackClass, "setStereoVolume", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length >= 2 && param.args[0] instanceof Float && param.args[1] instanceof Float) {
                            float left = (Float) param.args[0];
                            float right = (Float) param.args[1];
                            if (left < 1.0f && left > 0.0f) {
                                param.args[0] = 1.0f;
                            }
                            if (right < 1.0f && right > 0.0f) {
                                param.args[1] = 1.0f;
                            }
                        }
                    }
                });

                // Hook createVolumeShaper to sanitize any AudioTrack volume shapers
                XposedBridge.hookAllMethods(audioTrackClass, "createVolumeShaper", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length > 0 && param.args[0] != null) {
                            Object config = param.args[0];
                            try {
                                float[] vols = (float[]) XposedHelpers.getObjectField(config, "mVolumes");
                                if (vols != null) {
                                    for (int i = 0; i < vols.length; i++) {
                                        if (vols[i] < 1.0f && vols[i] > 0.0f) {
                                            vols[i] = 1.0f;
                                        }
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                });

                // Suppress shouldEnablePowerSaving so system never injects FLAG_DEEP_BUFFER
                XposedBridge.hookAllMethods(audioTrackClass, "shouldEnablePowerSaving", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        param.setResult(false);
                    }
                });

                // Sanitize AudioAttributes in all AudioTrack constructors
                XposedBridge.hookAllConstructors(audioTrackClass, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        for (int i = 0; i < param.args.length; i++) {
                            Object arg = param.args[i];
                            if (arg != null && "android.media.AudioAttributes".equals(arg.getClass().getName())) {
                                optimizeAudioAttributes(arg, "AudioTrack.<init>", lpparam.packageName);
                            }
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.thisObject instanceof AudioTrack) {
                            sActiveTracks.add(new WeakReference<>((AudioTrack) param.thisObject));
                        }
                    }
                });

                // Sanitize AudioAttributes in native_setup
                XposedBridge.hookAllMethods(audioTrackClass, "native_setup", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length > 1 && param.args[1] != null) {
                            optimizeAudioAttributes(param.args[1], "AudioTrack.native_setup", lpparam.packageName);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.thisObject instanceof AudioTrack) {
                            AudioTrack track = (AudioTrack) param.thisObject;
                            sActiveTracks.add(new WeakReference<>(track));
                            applyGlitchFreeBuffer(track, "native_setup", lpparam.packageName);
                        }
                    }
                });

                // Clamp active buffer size upon play() and ensure registration
                XposedBridge.hookAllMethods(audioTrackClass, "play", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.thisObject instanceof AudioTrack) {
                            AudioTrack track = (AudioTrack) param.thisObject;
                            sActiveTracks.add(new WeakReference<>(track));
                            applyGlitchFreeBuffer(track, "play", lpparam.packageName);
                        }
                    }
                });

                XposedBridge.log(TAG + "Hooked AudioTrack for low-latency & full volume in " + lpparam.packageName);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Error hooking AudioTrack: " + t.getMessage());
        }

        // 4. Hook AudioManager to prevent ad SDKs from stealing focus or lowering stream volume
        try {
            Class<?> audioManagerClass = XposedHelpers.findClassIfExists(
                    "android.media.AudioManager", lpparam.classLoader);
            if (audioManagerClass != null) {
                // Intercept requestAudioFocus: if called by an in-app ad SDK, grant locally without stealing focus from the game
                XposedBridge.hookAllMethods(audioManagerClass, "requestAudioFocus", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (isAdCaller()) {
                            XposedBridge.log(TAG + "Shielded game by granting ad requestAudioFocus locally in " + lpparam.packageName);
                            param.setResult(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
                            return;
                        }

                        // For normal requests, shield listeners from negative focus changes
                        for (int i = 0; i < param.args.length; i++) {
                            if (param.args[i] instanceof AudioManager.OnAudioFocusChangeListener) {
                                final AudioManager.OnAudioFocusChangeListener orig =
                                        (AudioManager.OnAudioFocusChangeListener) param.args[i];
                                sFocusListeners.add(orig);
                                param.args[i] = new AudioManager.OnAudioFocusChangeListener() {
                                    @Override
                                    public void onAudioFocusChange(int focusChange) {
                                        if (focusChange < 0) {
                                            XposedBridge.log(TAG + "Shielded OnAudioFocusChangeListener from negative focus ("
                                                    + focusChange + " -> 1) in " + lpparam.packageName);
                                            orig.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
                                        } else {
                                            orig.onAudioFocusChange(focusChange);
                                        }
                                    }
                                };
                            }
                        }
                    }
                });

                // Intercept abandonAudioFocus: if called by an ad SDK, consume immediately
                XposedBridge.hookAllMethods(audioManagerClass, "abandonAudioFocus", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (isAdCaller()) {
                            param.setResult(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
                        }
                    }
                });

                // Hook dispatchAudioFocusChange to block any incoming negative focus notifications
                XposedBridge.hookAllMethods(audioManagerClass, "dispatchAudioFocusChange", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length > 0 && param.args[0] instanceof Integer) {
                            int focusChange = (Integer) param.args[0];
                            if (focusChange < 0) {
                                XposedBridge.log(TAG + "Blocked dispatchAudioFocusChange(" + focusChange + " -> 1) in " + lpparam.packageName);
                                param.args[0] = AudioManager.AUDIOFOCUS_GAIN;
                            }
                        }
                    }
                });

                // Prevent ads and background events from lowering STREAM_MUSIC volume
                XposedBridge.hookAllMethods(audioManagerClass, "adjustStreamVolume", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length > 1 && param.args[0] instanceof Integer && param.args[1] instanceof Integer) {
                            int stream = (Integer) param.args[0];
                            int direction = (Integer) param.args[1];
                            if (stream == AudioManager.STREAM_MUSIC && (direction < 0 || direction == AudioManager.ADJUST_MUTE)) {
                                if (isAdCaller() || isGamePackage(lpparam.packageName)) {
                                    XposedBridge.log(TAG + "Blocked lowering STREAM_MUSIC in " + lpparam.packageName);
                                    param.setResult(null);
                                }
                            }
                        }
                    }
                });

                XposedBridge.hookAllMethods(audioManagerClass, "adjustSuggestedStreamVolume", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length > 1 && param.args[0] instanceof Integer && param.args[1] instanceof Integer) {
                            int direction = (Integer) param.args[0];
                            int stream = (Integer) param.args[1];
                            if (stream == AudioManager.STREAM_MUSIC && (direction < 0 || direction == AudioManager.ADJUST_MUTE)) {
                                if (isAdCaller() || isGamePackage(lpparam.packageName)) {
                                    param.setResult(null);
                                }
                            }
                        }
                    }
                });

                XposedBridge.hookAllMethods(audioManagerClass, "setStreamVolume", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length > 1 && param.args[0] instanceof Integer && param.args[1] instanceof Integer) {
                            int stream = (Integer) param.args[0];
                            if (stream == AudioManager.STREAM_MUSIC && isAdCaller()) {
                                XposedBridge.log(TAG + "Blocked ad from changing STREAM_MUSIC volume in " + lpparam.packageName);
                                param.setResult(null);
                            }
                        }
                    }
                });

                // Enforce low-latency output properties
                XposedBridge.hookAllMethods(audioManagerClass, "getProperty", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length > 0 && param.args[0] instanceof String) {
                            String prop = (String) param.args[0];
                            if ("android.media.property.OUTPUT_SAMPLE_RATE".equals(prop)) {
                                param.setResult("48000");
                            } else if ("android.media.property.OUTPUT_FRAMES_PER_BUFFER".equals(prop)) {
                                param.setResult("512");
                            }
                        }
                    }
                });
                XposedBridge.log(TAG + "Hooked AudioManager in " + lpparam.packageName);
            }

            Class<?> afrBuilderClass = XposedHelpers.findClassIfExists(
                    "android.media.AudioFocusRequest$Builder", lpparam.classLoader);
            if (afrBuilderClass != null) {
                XposedBridge.hookAllMethods(afrBuilderClass, "setOnAudioFocusChangeListener", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length > 0 && param.args[0] instanceof AudioManager.OnAudioFocusChangeListener) {
                            final AudioManager.OnAudioFocusChangeListener orig =
                                    (AudioManager.OnAudioFocusChangeListener) param.args[0];
                            sFocusListeners.add(orig);
                            param.args[0] = new AudioManager.OnAudioFocusChangeListener() {
                                @Override
                                public void onAudioFocusChange(int focusChange) {
                                    if (focusChange < 0) {
                                        XposedBridge.log(TAG + "Shielded AudioFocusRequest listener from negative focus ("
                                                + focusChange + " -> 1) in " + lpparam.packageName);
                                        orig.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
                                    } else {
                                        orig.onAudioFocusChange(focusChange);
                                    }
                                }
                            };
                        }
                    }
                });
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Error hooking AudioManager focus listeners: " + t.getMessage());
        }

        // 5. Hook AudioAttributes.Builder.build to enforce low-latency flags and game usage
        try {
            Class<?> aaBuilderClass = XposedHelpers.findClassIfExists(
                    "android.media.AudioAttributes$Builder", lpparam.classLoader);
            if (aaBuilderClass != null) {
                XposedBridge.hookAllMethods(aaBuilderClass, "build", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        optimizeAudioAttributes(param.getResult(), "AudioAttributes$Builder.build", lpparam.packageName);
                    }
                });
                XposedBridge.log(TAG + "Hooked AudioAttributes$Builder.build in " + lpparam.packageName);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Error hooking AudioAttributes$Builder: " + t.getMessage());
        }

        // 6. Hook UnityPlayer to prevent native audio muting & auto-restore master volume
        try {
            Class<?> unityClass = XposedHelpers.findClassIfExists(
                    "com.unity3d.player.UnityPlayer", lpparam.classLoader);
            if (unityClass != null) {
                // Capture UnityPlayer instance
                XposedBridge.hookAllConstructors(unityClass, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        sUnityPlayerInstance = new WeakReference<>(param.thisObject);
                        XposedBridge.log(TAG + "Captured UnityPlayer instance in " + lpparam.packageName);
                    }
                });

                // Block native master audio mute
                XposedBridge.hookAllMethods(unityClass, "nativeMuteMasterAudio", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length > 0 && Boolean.TRUE.equals(param.args[0])) {
                            XposedBridge.log(TAG + "Blocked UnityPlayer.nativeMuteMasterAudio(true) in " + lpparam.packageName);
                            param.args[0] = false;
                        }
                    }
                });

                // Shield UnityPlayer onAudioFocusChange from negative focus changes
                XposedBridge.hookAllMethods(unityClass, "onAudioFocusChange", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length > 0 && param.args[0] instanceof Integer) {
                            int focus = (Integer) param.args[0];
                            if (focus < 0) {
                                XposedBridge.log(TAG + "Overrode UnityPlayer.onAudioFocusChange(" + focus + " -> 1) in " + lpparam.packageName);
                                param.args[0] = AudioManager.AUDIOFOCUS_GAIN;
                            }
                        }
                    }
                });

                // Auto un-mute upon window focus regain and restore volume
                XposedBridge.hookAllMethods(unityClass, "windowFocusChanged", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length > 0 && Boolean.TRUE.equals(param.args[0])) {
                            try {
                                XposedHelpers.callMethod(param.thisObject, "nativeMuteMasterAudio", false);
                                XposedHelpers.callMethod(param.thisObject, "onAudioFocusChange", AudioManager.AUDIOFOCUS_GAIN);
                                XposedBridge.log(TAG + "Forced nativeMuteMasterAudio(false) on windowFocusChanged(true) in " + lpparam.packageName);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                });

                XposedBridge.log(TAG + "Hooked UnityPlayer successfully in " + lpparam.packageName);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "UnityPlayer not present or error hooking: " + t.getMessage());
        }

        // 7. Hook Activity lifecycle (onResume / onWindowFocusChanged) to auto-restore full volume & audio focus
        try {
            Class<?> activityClass = XposedHelpers.findClassIfExists(
                    "android.app.Activity", lpparam.classLoader);
            if (activityClass != null) {
                XposedBridge.hookAllMethods(activityClass, "onResume", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.thisObject instanceof Activity) {
                            restoreAudioForPackage(lpparam.packageName, (Activity) param.thisObject);
                        }
                    }
                });

                XposedBridge.hookAllMethods(activityClass, "onWindowFocusChanged", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length > 0 && Boolean.TRUE.equals(param.args[0])) {
                            if (param.thisObject instanceof Activity) {
                                restoreAudioForPackage(lpparam.packageName, (Activity) param.thisObject);
                            }
                        }
                    }
                });
                XposedBridge.log(TAG + "Hooked Activity lifecycle in " + lpparam.packageName);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Error hooking Activity lifecycle: " + t.getMessage());
        }
    }

    private static void restoreAudioForPackage(String packageName, Context context) {
        try {
            // 1. Force un-mute Unity master audio and restore volume handler
            if (sUnityPlayerInstance != null) {
                Object unityPlayer = sUnityPlayerInstance.get();
                if (unityPlayer != null) {
                    try {
                        XposedHelpers.callMethod(unityPlayer, "nativeMuteMasterAudio", false);
                        XposedBridge.log(TAG + "Restored Unity master audio (unmuted) in " + packageName);
                    } catch (Throwable ignored) {
                    }
                    try {
                        XposedHelpers.callMethod(unityPlayer, "onAudioFocusChange", AudioManager.AUDIOFOCUS_GAIN);
                    } catch (Throwable ignored) {
                    }
                    try {
                        Object volHandler = XposedHelpers.getObjectField(unityPlayer, "m_AudioVolumeHandler");
                        if (volHandler != null && context != null) {
                            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                            if (am != null) {
                                int vol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                                XposedHelpers.callMethod(volHandler, "onAudioVolumeChanged", vol);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }

            // 2. Re-acquire Audio Focus from AudioManager for the resumed activity
            if (context != null) {
                try {
                    AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                    if (am != null) {
                        am.setMode(AudioManager.MODE_NORMAL);
                        am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
                    }
                } catch (Throwable ignored) {
                }
            }

            // 3. Dispatch AUDIOFOCUS_GAIN to all tracked listeners
            for (AudioManager.OnAudioFocusChangeListener listener : sFocusListeners) {
                try {
                    listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
                } catch (Throwable ignored) {
                }
            }

            // 4. Restore any active AudioTrack volume to full (1.0f)
            Iterator<WeakReference<AudioTrack>> it = sActiveTracks.iterator();
            while (it.hasNext()) {
                AudioTrack track = it.next().get();
                if (track != null) {
                    try {
                        track.setVolume(1.0f);
                        track.setStereoVolume(1.0f, 1.0f);
                    } catch (Throwable ignored) {
                    }
                } else {
                    it.remove();
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Error in restoreAudioForPackage: " + t.getMessage());
        }
    }

    private static void applyGlitchFreeBuffer(AudioTrack track, String hookPoint, String packageName) {
        if (track == null) return;
        try {
            int capacity = track.getBufferCapacityInFrames();
            if (capacity <= 0) return;
            int sampleRate = track.getSampleRate();
            // 24000 Hz resampled to 48000 Hz needs at least 1024-1280 frames to avoid buffer underrun/glitches.
            // 48000 Hz fast mixer runs at 256 frames, so 1024 frames is ~21ms (4 bursts) with zero underrun.
            int target = (sampleRate <= 24000) ? 1280 : 1024;
            int targetFrames = Math.min(capacity, target);
            int res = track.setBufferSizeInFrames(targetFrames);
            XposedBridge.log(TAG + "[" + hookPoint + "] Set buffer size to " + res + " frames (capacity="
                    + capacity + ", sr=" + sampleRate + ") in " + packageName);
        } catch (Throwable ignored) {
        }
    }

    private static void optimizeAudioAttributes(Object attr, String context, String packageName) {
        if (attr == null) return;
        try {
            int flags = XposedHelpers.getIntField(attr, "mFlags");
            int oldFlags = flags;
            // Clear FLAG_DEEP_BUFFER (0x200), set FLAG_LOW_LATENCY (0x100)
            flags = (flags & ~0x200) | 0x100;
            XposedHelpers.setIntField(attr, "mFlags", flags);

            int usage = XposedHelpers.getIntField(attr, "mUsage");
            // If USAGE_MEDIA (1) or USAGE_UNKNOWN (0), upgrade to USAGE_GAME (14)
            if (usage == AudioAttributes.USAGE_MEDIA || usage == AudioAttributes.USAGE_UNKNOWN) {
                XposedHelpers.setIntField(attr, "mUsage", AudioAttributes.USAGE_GAME); // USAGE_GAME
            }
            if (oldFlags != flags || usage != XposedHelpers.getIntField(attr, "mUsage")) {
                XposedBridge.log(TAG + "[" + context + "] Optimized AudioAttributes for " + packageName
                        + " (flags: 0x" + Integer.toHexString(oldFlags) + " -> 0x" + Integer.toHexString(flags)
                        + ", usage: " + usage + " -> " + XposedHelpers.getIntField(attr, "mUsage") + ")");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Error optimizing AudioAttributes: " + t.getMessage());
        }
    }
}
