package com.byxiaopeng.dytiao;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 抖音 (com.ss.android.ugc.aweme) 小程序激励视频广告跳过。
 *
 * 逆向结论（基于 40.6.0 / versionCode 400601）：
 *
 *   小程序进程(:miniapp0 / :minigame*) 通过 IPC 请求主进程的 AdIpcMainService，
 *   主进程 AbsMiniAppRewardAdIpcMainRouterHandler.handle() 解析 ad_json / router_params，
 *   再 IRouterDepend.open() 打开 ExcitingVideoActivity，
 *   由 ExcitingVideoFragment 承载界面，最终把 RewardAdNativeFragment 作为子 Fragment 播放激励视频。
 *   播放结束走 RewardAdNativeFragment#zM()（内部日志 "executeOnComplete()"），
 *   依次回调 ExcitingVideoListener#onComplete / IRewardCompleteListener#onRewardComplete / #onClose。
 *
 *   因此最小干预点就是：广告页一出现，直接按官方语义回调
 *   IRewardCompleteListener#onRewardComplete(rewardType=2) + #onClose()，
 *   奖励结果会顺着原链条回传给小程序进程；zM() 只作兜底。
 *   下游用 RewardCompleteParams.getWatchTime() >= getInspireTime() 判定是否发奖，
 *   提前结束时 watchTime 很小，所以还要补一个 getWatchTime() 的兜底。
 */
public class AdSkipModule implements IXposedHookLoadPackage {

    private static final String TAG = "DouyinAdSkip";
    private static final String PKG = "com.ss.android.ugc.aweme";

    private static final String CLS_REWARD_FRAGMENT =
            "com.ss.android.excitingvideo.sdk.RewardAdNativeFragment";
    /**
     * 小游戏广告模型类（负责把 close 事件发给 JS）在不同抖音版本里混淆名不同：
     *   40.6.0 -> X.0jsw
     *   40.9.0 -> X.0XOk
     * 按顺序尝试，命中一个即可。
     */
    private static final String[] CLS_GAME_AD_MODELS = {"X.0XOk", "X.0jsw"};
    private static final String CLS_MINIAPP_AD_LISTENER = "X.0jye";
    private static final String CLS_MINIAPP_AD_STATE = "X.0jyd";
    private static final String CLS_EV_FRAGMENT =
            "com.ss.android.excitingvideo.sdk.ExcitingVideoFragment";
    private static final String CLS_DRAW_FRAGMENT =
            "com.bytedance.android.ad.rewarded.draw.RewardAdDrawFragment";
    private static final String CLS_PARAMS =
            "com.ss.android.excitingvideo.IRewardCompleteListener$RewardCompleteParams";
    private static final String CLS_LISTENER =
            "com.ss.android.excitingvideo.IRewardCompleteListener";

    /** 已经安排过跳过的 Fragment，避免 onResume 多次触发 */
    private static final Map<Object, Boolean> sScheduled =
            Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());

    private static ClassLoader sClassLoader;

    /**
     * 注意：模块类初始化发生在 zygote 特化阶段，那时 Looper.getMainLooper() 还是 null，
     * 所以 Handler 不能在静态初始化里 new，必须用的时候再建（否则 ExceptionInInitializerError）。
     */
    static void postDelayed(Runnable action, long delayMs) {
        try {
            Looper main = Looper.getMainLooper();
            if (main == null) {
                action.run();
                return;
            }
            new Handler(main).postDelayed(action, delayMs);
        } catch (Throwable t) {
            log("postDelayed failed, run inline: " + t);
            action.run();
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || !PKG.equals(lpparam.packageName)) {
            return;
        }
        sClassLoader = lpparam.classLoader;
        log("module loaded in process=" + lpparam.processName);

        if (Config.PATCH_WATCH_TIME) {
            hookWatchTime();
        }
        if (Config.SKIP_REWARD_VIDEO) {
            hookExcitingVideoFragment();
            hookGameAdEvent();
            hookRewardFragment(CLS_REWARD_FRAGMENT, "zM");
            if (Config.SKIP_DRAW_REWARD) {
                hookRewardFragment(CLS_DRAW_FRAGMENT, "zM");
            }
        }
        if (Config.DIAG) {
            Diag.install(sClassLoader);
        }
        if (Config.INJECT_SETTINGS_ENTRY) {
            try {
                DouyinSettingsInjector.install(sClassLoader);
            } catch (Throwable t) {
                log("settings injector failed: " + t);
            }
        }
        if (Config.HIDE_IN_PAGE_ADS) {
            try {
                InPageAdHooks.install(sClassLoader);
            } catch (Throwable t) {
                log("in-page hooks failed: " + t);
            }
        }
    }

    // ---------------------------------------------------------------- hooks

    /**
     * 真机实测（40.6.0 / 小游戏）：
     * 激励视频不是独立 Activity，而是宿主把 com.ss.android.excitingvideo.sdk.ExcitingVideoFragment
     * 直接塞进小游戏 Activity（MiniGameActivity0）里；广告内容本身是 Lynx 模板
     * (com.ss.android.ad.lynx.* + com.bytedance.android.ad.rewarded.*)。
     * 这个 Fragment 一 onResume 就说明广告已经展示，直接调它的 IRewardCompleteListener 结算即可。
     */
    private void hookExcitingVideoFragment() {
        Class<?> clazz = XposedHelpers.findClassIfExists(CLS_EV_FRAGMENT, sClassLoader);
        if (clazz == null) {
            log("ev fragment not found: " + CLS_EV_FRAGMENT);
            return;
        }
        try {
            XposedBridge.hookAllMethods(clazz, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!Prefs.enabled()) {
                        return;
                    }
                    final Object fragment = param.thisObject;
                    if (fragment == null || sScheduled.put(fragment, Boolean.TRUE) != null) {
                        return;
                    }
                    log("ev fragment resumed -> complete in " + Config.SKIP_DELAY_MS + "ms");
                    postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            AdSkipModule.log("skip runnable fired");
                            try {
                                completeExcitingVideo(fragment);
                            } catch (Throwable t) {
                                AdSkipModule.log("completeExcitingVideo crashed: " + t);
                            }
                        }
                    }, Config.SKIP_DELAY_MS);
                }
            });
            log("hooked " + CLS_EV_FRAGMENT + "#onResume");
        } catch (Throwable t) {
            log("hook ev fragment failed: " + t);
        }
    }

    /**
     * 小游戏真正的发奖开关（真机确认）：
     *   com.ss.android.excitingvideo.IRewardCompleteListener
     *     └ X.0jyi (匿名) extends X.0jyj
     *         字段: LIZJ=playTime, LIZLLL=effectTime, LJ=playCount, LJFF=completeCount
     *         onDestroy() -> X.0jsw#LIZIZ("close", {"count": LJFF})
     * 小游戏拿到 count>0 才认为广告看完并加奖励。提前关闭时 LJFF 还是 0，所以没有奖励。
     *
     * 这里把 LJFF 补成 1 再调 onDestroy()，等价于「广告播完了」。
     */
    private void flushMiniAppAdClose(Object outerListener) {
        // 1) 最常见：o 本身就是 X.0jyi（继承 X.0jyj / IRewardCompleteListener），直接改它自己
        if (patchGameState(outerListener, true)) {
            return;
        }
        // 2) 兜底：o 是包装类，里面还有一层 listener
        for (java.lang.reflect.Field f : outerListener.getClass().getDeclaredFields()) {
            Object value;
            try {
                f.setAccessible(true);
                value = f.get(outerListener);
            } catch (Throwable t) {
                continue;
            }
            if (value != null && patchGameState(value, true)) {
                return;
            }
        }
        log("no ad state found on " + outerListener.getClass().getName());
    }

    /** 把「播完次数」补成 1 并触发 close 事件；不是奖励状态对象就返回 false */
    private boolean patchGameState(Object state, boolean flush) {
        int complete;
        try {
            complete = XposedHelpers.getIntField(state, "LJFF");
        } catch (Throwable t) {
            return false;
        }
        try {
            int play = XposedHelpers.getIntField(state, "LJ");
            int playTime = XposedHelpers.getIntField(state, "LIZJ");
            int effectTime = XposedHelpers.getIntField(state, "LIZLLL");
            XposedHelpers.setIntField(state, "LJFF", Math.max(1, complete));
            if (play < 1) {
                XposedHelpers.setIntField(state, "LJ", 1);
            }
            if (effectTime > 0 && playTime < effectTime) {
                XposedHelpers.setIntField(state, "LIZJ", effectTime);
            }
            log("game ad state(" + state.getClass().getName() + "): complete " + complete
                    + " -> " + Math.max(1, complete) + ", play " + play);
            if (flush) {
                try {
                    XposedHelpers.callMethod(state, "onDestroy");
                } catch (Throwable t) {
                    log("onDestroy threw: " + t + ", fallback to super");
                    java.lang.reflect.Method superMethod =
                            state.getClass().getSuperclass().getDeclaredMethod("onDestroy");
                    superMethod.setAccessible(true);
                    superMethod.invoke(state);
                }
                log("game close event flushed: count=" + Math.max(1, complete));
            }
            return true;
        } catch (Throwable t) {
            log("patch game state failed: " + t);
            return false;
        }
    }

    /**
     * 小游戏 close 事件的出口：X.0jsw#LIZIZ(event, json) -> 插件 -> JS 的 onClose。
     * 小游戏 JS 判断「看完」用的是 payload 里的字段，这里把 isEnded/count 一次性补全。
     */
    private void hookGameAdEvent() {
        for (final String className : CLS_GAME_AD_MODELS) {
            Class<?> cls = XposedHelpers.findClassIfExists(className, sClassLoader);
            if (cls == null) {
                log("game ad model not found: " + className);
                continue;
            }
            try {
                XposedBridge.hookAllMethods(cls, "LIZIZ", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object[] args = param.args;
                        if (args == null || args.length < 2) {
                            return;
                        }
                        Object event = args[0];
                        Object payload = args[1];
                        if (!(payload instanceof org.json.JSONObject)) {
                            return;
                        }
                        org.json.JSONObject json = (org.json.JSONObject) payload;
                        if ("close".equals(event)) {
                            try {
                                json.put("isEnded", true);
                                if (json.optInt("count", 0) < 1) {
                                    json.put("count", 1);
                                }
                                log("game close payload patched(" + className + "): " + json);
                            } catch (Throwable t) {
                                log("patch game close failed: " + t);
                            }
                        }
                    }
                });
                log("hooked " + className + "#LIZIZ");
            } catch (Throwable t) {
                log("hook game ad event failed(" + className + "): " + t);
            }
            break;
        }
    }

    /** 直接结算：onRewardComplete(2) + onClose() + closeFragment() */
    private void completeExcitingVideo(Object fragment) {
        if (!Prefs.enabled()) {
            log("skip disabled by user, do nothing");
            return;
        }
        Object listener = null;
        try {
            listener = XposedHelpers.getObjectField(fragment, "o");
        } catch (Throwable t) {
            log("read listener field failed: " + t);
        }
        if (listener == null) {
            log("ev fragment listener is null, give up");
            return;
        }
        Object params = null;
        try {
            Class<?> paramsClass = XposedHelpers.findClass(CLS_PARAMS, sClassLoader);
            params = XposedHelpers.newInstance(paramsClass, Config.FAKE_WATCH_TIME, 1);
            XposedHelpers.callMethod(listener, "onRewardComplete", 2, params);
            log("ev: onRewardComplete(2) sent");
        } catch (Throwable t) {
            log("ev: onRewardComplete failed " + t);
        }
        try {
            XposedHelpers.callMethod(listener, "onRewardReceiving", 2, params);
        } catch (Throwable ignored) {
        }
        try {
            XposedHelpers.callMethod(listener, "onClose");
            log("ev: onClose sent");
        } catch (Throwable t) {
            log("ev: onClose failed " + t);
        }
        if (Config.EV_CLOSE_FRAGMENT) {
            try {
                XposedHelpers.callMethod(fragment, "closeFragment");
                log("ev: closeFragment called");
            } catch (Throwable t) {
                log("ev: closeFragment failed " + t);
            }
        }
        flushMiniAppAdClose(listener);
    }

    private void hookRewardFragment(final String className, final String completeMethod) {
        final Class<?> clazz = XposedHelpers.findClassIfExists(className, sClassLoader);
        if (clazz == null) {
            log("class not found: " + className);
            return;
        }
        try {
            XposedBridge.hookAllMethods(clazz, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!Prefs.enabled()) {
                        return;
                    }
                    final Object fragment = param.thisObject;
                    if (fragment == null) {
                        return;
                    }
                    if (sScheduled.put(fragment, Boolean.TRUE) != null) {
                        return;
                    }
                    log("ad fragment resumed: " + fragment.getClass().getName()
                            + " -> skip in " + Config.SKIP_DELAY_MS + "ms");
                    postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            performSkip(fragment, completeMethod);
                        }
                    }, Config.SKIP_DELAY_MS);
                }
            });
            log("hooked " + className + "#onResume");
        } catch (Throwable t) {
            log("hook fragment failed: " + className + " " + t);
        }
    }

    /**
     * 优先直接给 listener 回“看完了(rewardType=2) + 关闭”，这条路径结果最确定：
     * 官方 zM() 会先看视频控件是否真的播完，没播完时 rewardType 传 1，
     * 下游会当成“用户没看完”拒发奖励。listener 拿不到时再退回 zM()。
     */
    private void performSkip(Object fragment, String completeMethod) {
        boolean faked = fakeReward(fragment);
        if (!faked) {
            try {
                XposedHelpers.callMethod(fragment, completeMethod);
                log("fallback: called " + completeMethod + "() on "
                        + fragment.getClass().getSimpleName());
            } catch (Throwable t) {
                log("call " + completeMethod + "() failed: " + t);
            }
        }
        if (Config.FORCE_FINISH_DELAY_MS > 0) {
            final Object frag = fragment;
            postDelayed(new Runnable() {
                @Override
                public void run() {
                    Activity activity = activityOf(frag);
                    if (activity != null && !activity.isFinishing()) {
                        log("force finish " + activity.getClass().getName());
                        activity.finish();
                    }
                }
            }, Config.FORCE_FINISH_DELAY_MS);
        }
    }

    /** 直接给 IRewardCompleteListener 回调“看完 / 发奖 / 关闭”，成功返回 true */
    private boolean fakeReward(Object fragment) {
        try {
            Object listener = findListener(fragment);
            if (listener == null) {
                log("listener not found");
                return false;
            }
            Class<?> paramsClass = XposedHelpers.findClass(CLS_PARAMS, sClassLoader);
            Object params = XposedHelpers.newInstance(paramsClass, Config.FAKE_WATCH_TIME, 1);
            XposedHelpers.callMethod(listener, "onRewardComplete", 2, params);
            XposedHelpers.callMethod(listener, "onClose");
            log("fake reward delivered via " + listener.getClass().getName());
            return true;
        } catch (Throwable t) {
            log("fake reward failed: " + t);
            return false;
        }
    }

    private Object findListener(Object fragment) {
        // 1) 字段类型是 IRewardCompleteListener
        for (Field f : fragment.getClass().getDeclaredFields()) {
            if (CLS_LISTENER.equals(f.getType().getName())) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(fragment);
                    if (v != null) {
                        return v;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        // 2) RewardAdNativeFragment.m / RewardAdDrawFragment 里藏在 VideoCacheModel.k.LJ
        try {
            Object v = XposedHelpers.getObjectField(fragment, "m");
            if (v != null) {
                return v;
            }
        } catch (Throwable ignored) {
        }
        try {
            Object cache = XposedHelpers.getObjectField(fragment, "k");
            if (cache != null) {
                Object v = XposedHelpers.getObjectField(cache, "LJ");
                if (v != null) {
                    return v;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Activity activityOf(Object fragment) {
        try {
            Object a = XposedHelpers.callMethod(fragment, "getActivity");
            return a instanceof Activity ? (Activity) a : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 抖音下游判断是否发奖：watchTime >= inspireTime。
     * 提前结束时 watchTime 只有零点几秒，这里直接把观看时长抬到激励时长。
     */
    private void hookWatchTime() {
        final Class<?> params = XposedHelpers.findClassIfExists(CLS_PARAMS, sClassLoader);
        if (params == null) {
            log("params class not found: " + CLS_PARAMS);
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(params, "getWatchTime", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object self = param.thisObject;
                        int watch = (Integer) param.getResult();
                        int inspire = (Integer) XposedHelpers.callMethod(self, "getInspireTime");
                        if (watch < inspire) {
                            param.setResult(inspire);
                            log("watchTime patched: " + watch + " -> " + inspire);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            log("hooked " + CLS_PARAMS + "#getWatchTime");
        } catch (Throwable t) {
            log("hook getWatchTime failed: " + t);
        }
    }

    // ---------------------------------------------------------------- util

    static void log(String msg) {
        if (!Config.VERBOSE) {
            return;
        }
        XposedBridge.log(TAG + ": " + msg);
    }
}
