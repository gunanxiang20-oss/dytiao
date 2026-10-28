package com.byxiaopeng.dytiao;

import android.content.Context;

/**
 * 配置存在抖音自己的私有 SharedPreferences 里（同一 UID 的各个进程都能读），
 * 这样设置弹窗和 hook 天然共享，不需要跨应用读写，也不用 XSharedPreferences。
 */
final class Prefs {

    static final String FILE = "dytiao_config";
    static final String KEY_ENABLED = "enabled";
    static final String KEY_AGREED = "agreed";

    private Prefs() {
    }

    /** hook 里用：拿当前进程的 Application 上下文 */
    static boolean enabled() {
        Context ctx = currentApp();
        return ctx != null && enabled(ctx);
    }

    static boolean enabled(Context ctx) {
        try {
            return ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .getBoolean(KEY_ENABLED, false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 是否已经同意过免责协议 */
    static boolean agreed(Context ctx) {
        try {
            return ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .getBoolean(KEY_AGREED, false);
        } catch (Throwable t) {
            return false;
        }
    }

    static void setAgreed(Context ctx, boolean value) {
        try {
            ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_AGREED, value).apply();
        } catch (Throwable ignored) {
        }
    }

    static void setEnabled(Context ctx, boolean value) {
        try {
            ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_ENABLED, value).apply();
        } catch (Throwable ignored) {
        }
    }

    private static Context currentApp() {
        // AndroidAppHelper 不在 api-82 stub 里，反射拿
        try {
            Class<?> cls = Class.forName("android.app.AndroidAppHelper");
            Object app = cls.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                return (Context) app;
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> cls = Class.forName("android.app.ActivityThread");
            Object app = cls.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                return (Context) app;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
