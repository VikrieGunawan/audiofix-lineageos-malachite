package io.github.fix.audio;

import android.media.AudioTrack;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class AudioFixHook implements IXposedHookLoadPackage {

    private static final String TAG = "[AudioFix] ";

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) throws Throwable {
        if ("android".equals(lpparam.packageName)) {
            hookSystemServer(lpparam);
        } else if (!isSystemPackage(lpparam.packageName)) {
            hookClientApp(lpparam);
        }
    }

    private boolean isSystemPackage(String pkg) {
        if (pkg == null) return true;
        return pkg.equals("android") || pkg.equals("system")
                || pkg.startsWith("com.android.")
                || pkg.startsWith("org.lineageos.")
                || pkg.startsWith("com.google.android.providers.");
    }

    private void hookSystemServer(LoadPackageParam lpparam) {
        XposedBridge.log(TAG + "Initializing in system_server (android)...");

        try {
            Class<?> pamClass = XposedHelpers.findClassIfExists(
                    "com.android.server.audio.PlaybackActivityMonitor", lpparam.classLoader);
            if (pamClass != null) {
                XposedBridge.hookAllMethods(pamClass, "duckPlayers", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        XposedBridge.log(TAG + "Suppressed PlaybackActivityMonitor.duckPlayers (winner="
                                + param.args[0] + ", loser=" + param.args[1] + ")");
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

                XposedBridge.log(TAG + "Hooked MediaFocusControl successfully.");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Error hooking MediaFocusControl: " + t.getMessage());
        }

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
                            if (mode == 3 || mode == 2) {
                                String lower = pkg.toLowerCase();
                                if (lower.contains("game") || lower.contains("subway") || lower.contains("unity")
                                        || lower.contains("unreal") || lower.contains("sybo") || lower.contains("kiloo")
                                        || lower.contains("roblox") || lower.contains("pryshedko")) {
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

        try {
            Class<?> playerBaseClass = XposedHelpers.findClassIfExists(
                    "android.media.PlayerBase", lpparam.classLoader);
            if (playerBaseClass != null) {
                XposedBridge.hookAllMethods(playerBaseClass, "applyVolumeShaper", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length > 0 && param.args[0] != null) {
                            String configStr = param.args[0].toString();
                            if (configStr.contains("duck") || configStr.contains("Duck")
                                    || configStr.contains("fade") || configStr.contains("Fade")) {
                                XposedBridge.log(TAG + "Blocked applyVolumeShaper (" + configStr + ") in " + lpparam.packageName);
                                param.setResult(1);
                            }
                        }
                    }
                });
            }
        } catch (Throwable ignored) {
        }

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

        try {
            Class<?> audioTrackClass = XposedHelpers.findClassIfExists(
                    "android.media.AudioTrack", lpparam.classLoader);
            if (audioTrackClass != null) {
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

                XposedBridge.hookAllMethods(audioTrackClass, "shouldEnablePowerSaving", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        param.setResult(false);
                    }
                });

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
                });

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
                            applyGlitchFreeBuffer((AudioTrack) param.thisObject, "native_setup", lpparam.packageName);
                        }
                    }
                });

                XposedBridge.hookAllMethods(audioTrackClass, "play", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.thisObject instanceof AudioTrack) {
                            applyGlitchFreeBuffer((AudioTrack) param.thisObject, "play", lpparam.packageName);
                        }
                    }
                });

                XposedBridge.log(TAG + "Hooked AudioTrack for low-latency in " + lpparam.packageName);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Error hooking AudioTrack: " + t.getMessage());
        }

        try {
            Class<?> audioManagerClass = XposedHelpers.findClassIfExists(
                    "android.media.AudioManager", lpparam.classLoader);
            if (audioManagerClass != null) {
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
                XposedBridge.log(TAG + "Hooked AudioManager.getProperty for low-latency properties in " + lpparam.packageName);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Error hooking AudioManager.getProperty: " + t.getMessage());
        }
    }

    private static void applyGlitchFreeBuffer(AudioTrack track, String hookPoint, String packageName) {
        if (track == null) return;
        try {
            int capacity = track.getBufferCapacityInFrames();
            if (capacity <= 0) return;
            int sampleRate = track.getSampleRate();
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
            flags = (flags & ~0x200) | 0x100;
            XposedHelpers.setIntField(attr, "mFlags", flags);

            int usage = XposedHelpers.getIntField(attr, "mUsage");
            if (usage == 1 || usage == 0) {
                XposedHelpers.setIntField(attr, "mUsage", 14);
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
