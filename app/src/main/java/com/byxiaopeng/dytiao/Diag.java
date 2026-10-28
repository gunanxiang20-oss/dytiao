package com.byxiaopeng.dytiao;

import android.app.Activity;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** 诊断用：只打日志，不改行为。定位激励视频到底走哪条链路。 */
final class Diag {

    private Diag() {
    }

    static void install(ClassLoader cl) {
        hookClassLoad();
        hookActivityCreate(cl);
        hookResume("com.ss.android.excitingvideo.sdk.ExcitingVideoFragment", cl, "ExcitingVideoFragment");
        hookResume("com.bytedance.android.ad.rewarded.draw.RewardAdDrawFragment", cl, "RewardAdDrawFragment");
        hookHandle("com.bytedance.android.ad.rewarded.miniapp.AbsMiniAppRewardAdIpcMainRouterHandler", cl);
        hookDialog();
        hookStatic("com.ss.android.excitingvideo.ExcitingVideoAd", "requestExcitingVideo", cl);
        AdSkipModule.log("diag installed");
    }

    private static void hookDialog() {
        try {
            XposedHelpers.findAndHookMethod(android.app.Dialog.class, "show", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String n = param.thisObject.getClass().getName();
                    if (!n.startsWith("android.") && !n.startsWith("androidx.")) {
                        AdSkipModule.log("diag: Dialog.show " + n);
                    }
                }
            });
            AdSkipModule.log("diag: hooked Dialog.show");
        } catch (Throwable t) {
            AdSkipModule.log("diag: dialog hook failed " + t);
        }
    }

    /** 记录广告相关类的加载，用来定位这条链到底用了哪些类 */
    private static void hookClassLoad() {
        try {
            XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String n = (String) param.args[0];
                            if (n == null) {
                                return;
                            }
                            String lower = n.toLowerCase();
                            boolean interesting = lower.startsWith("com.ss.android.ad")
                                    || lower.startsWith("com.bytedance.android.ad")
                                    || lower.startsWith("com.ss.android.excitingvideo")
                                    || lower.startsWith("com.ss.android.ugc.aweme.ad")
                                    || lower.startsWith("com.ss.android.ugc.aweme.rewarded_ad");
                            if (interesting && !lower.contains("adapter") && !lower.contains("read")
                                    && !lower.contains("shadow") && !lower.contains("thread")
                                    && !lower.contains("download")) {
                                AdSkipModule.log("diag: loadClass " + n);
                            }
                        }
                    });
            AdSkipModule.log("diag: hooked ClassLoader.loadClass");
        } catch (Throwable t) {
            AdSkipModule.log("diag: classload hook failed " + t);
        }
    }

    /** 任何名字像广告的 Activity 被创建都记一笔 */
    private static void hookActivityCreate(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onCreate", android.os.Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String name = param.thisObject.getClass().getName().toLowerCase();
                            if (!name.contains("ad") && !name.contains("reward")) {
                                return;
                            }
                            if (name.contains("adapter") || name.contains("loading")
                                    || name.contains("read") || name.contains("download")
                                    || name.contains("header") || name.contains("broadcast")
                                    || name.contains("shadow") || name.contains("thread")
                                    || name.contains("badge") || name.contains("upload")) {
                                return;
                            }
                            AdSkipModule.log("diag: Activity.onCreate "
                                    + param.thisObject.getClass().getName());
                        }
                    });
        } catch (Throwable t) {
            AdSkipModule.log("diag: activity hook failed " + t);
        }
    }

    private static void hookResume(String className, ClassLoader cl, final String label) {
        Class<?> c = XposedHelpers.findClassIfExists(className, cl);
        if (c == null) {
            AdSkipModule.log("diag: class missing " + className);
            return;
        }
        try {
            XposedBridge.hookAllMethods(c, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    AdSkipModule.log("diag: " + label + "#onResume");
                }
            });
            AdSkipModule.log("diag: hooked " + label + "#onResume");
        } catch (Throwable t) {
            AdSkipModule.log("diag: hook " + label + " failed " + t);
        }
    }

    private static void hookHandle(String className, ClassLoader cl) {
        Class<?> c = XposedHelpers.findClassIfExists(className, cl);
        if (c == null) {
            AdSkipModule.log("diag: class missing " + className);
            return;
        }
        try {
            XposedBridge.hookAllMethods(c, "handle", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    StringBuilder sb = new StringBuilder("diag: miniapp reward router handle args=");
                    Object[] args = param.args;
                    if (args != null) {
                        for (Object a : args) {
                            sb.append(a == null ? "null" : a.getClass().getSimpleName()).append(' ');
                        }
                    }
                    AdSkipModule.log(sb.toString());
                }
            });
            AdSkipModule.log("diag: hooked miniapp reward router");
        } catch (Throwable t) {
            AdSkipModule.log("diag: hook router failed " + t);
        }
    }

    private static void hookStatic(String className, String method, ClassLoader cl) {
        Class<?> c = XposedHelpers.findClassIfExists(className, cl);
        if (c == null) {
            AdSkipModule.log("diag: class missing " + className);
            return;
        }
        try {
            XposedBridge.hookAllMethods(c, method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    AdSkipModule.log("diag: ExcitingVideoAd#" + method + " called");
                }
            });
            AdSkipModule.log("diag: hooked " + className + "#" + method);
        } catch (Throwable t) {
            AdSkipModule.log("diag: hook " + method + " failed " + t);
        }
    }
}
