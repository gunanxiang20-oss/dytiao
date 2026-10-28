package com.byxiaopeng.dytiao;

import android.view.View;
import android.view.ViewGroup;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 小程序「页面内广告组件」(<ad> banner / 原生 / 抽奖) 处理。
 *
 * 逆向结论（40.6.0）：
 *
 *   <ad> 节点由 com.tt.miniapphost.ad.mannor.draw.DrawAdComponent 承载，
 *   真正的广告渲染走 host 服务接口 com.tt.miniapphost.ad.ctrl.draw.BdpMannorAdService，
 *   实现在 com.ss.android.ugc.aweme.miniapp.ad.BdpMannorAdServiceImpl：
 *
 *   - dealMannorMethod(method, ...)：method 取值来自枚举 X.19Zs
 *       shouldShow / hideComponentOnClose / hideComponentOnHide / OpenSchema
 *     其中 shouldShow 会问 IMannorBridgeService.shouldShowAdComponent()，
 *     回 false 则插件侧不展示该广告位。
 *
 *   - renderBizComponentAndMannor(appContext, componentData, adCtx, type, container, p6, p7)
 *     真正干活：inflate 布局 -> 往 container 里 addView(new 0N9m(...), -1/-1)
 *     -> 主线程跑 renderBizComponent.1 -> 返回组件对象。
 *     方法本身有多条 return null 的早退分支，说明调用方允许 null。
 *
 * 所以这里两层处理：
 *   A. dealMannorMethod 的 shouldShow 直接回 false —— 让插件自己别展示；
 *   B. renderBizComponentAndMannor 直接返回 null —— 兜底，不 inflate 不上报曝光；
 *      并把传进来的 container 折叠成 0 高，避免页面留一块空白。
 */
final class InPageAdHooks {

    private static final String CLS_MANNOR_IMPL =
            "com.ss.android.ugc.aweme.miniapp.ad.BdpMannorAdServiceImpl";
    private static final String CLS_DRAW_COMPONENT =
            "com.tt.miniapphost.ad.mannor.draw.DrawAdComponent";

    private static final String METHOD_SHOULD_SHOW = "shouldShow";

    private InPageAdHooks() {
    }

    static void install(ClassLoader cl) {
        if (!Config.HIDE_IN_PAGE_ADS) {
            return;
        }
        hookMannorImpl(cl);
        if (Config.HIDE_AD_COMPONENT_VIEW) {
            hookDrawComponent(cl);
        }
    }

    // ------------------------------------------------------------ host 服务

    private static void hookMannorImpl(ClassLoader cl) {
        Class<?> clazz = XposedHelpers.findClassIfExists(CLS_MANNOR_IMPL, cl);
        if (clazz == null) {
            AdSkipModule.log("in-page: class not found: " + CLS_MANNOR_IMPL);
            return;
        }

        // A. 广告位 shouldShow -> false
        try {
            XposedBridge.hookAllMethods(clazz, "dealMannorMethod", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Object[] args = param.args;
                    if (args == null || args.length < 6) {
                        return;
                    }
                    if (!(args[1] instanceof String)
                            || !METHOD_SHOULD_SHOW.equals(args[1])) {
                        return;
                    }
                    Object callback = args[5];
                    if (callback != null) {
                        try {
                            // 与官方实现同样的回调格式：code=8, msg="", data=Boolean
                            XposedHelpers.callMethod(callback, "onResponse",
                                    8, "", Boolean.FALSE);
                        } catch (Throwable t) {
                            AdSkipModule.log("in-page: shouldShow reply failed: " + t);
                        }
                    }
                    param.setResult(null);
                    AdSkipModule.log("in-page: shouldShow -> false");
                }
            });
            AdSkipModule.log("in-page: hooked " + CLS_MANNOR_IMPL + "#dealMannorMethod");
        } catch (Throwable t) {
            AdSkipModule.log("in-page: hook dealMannorMethod failed: " + t);
        }

        // B. 组件渲染 -> null，并折叠容器
        try {
            XposedBridge.hookAllMethods(clazz, "renderBizComponentAndMannor",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(null);
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object[] args = param.args;
                            if (args == null || args.length < 5) {
                                return;
                            }
                            collapse(args[4]);
                            AdSkipModule.log("in-page: render blocked");
                        }
                    });
            AdSkipModule.log("in-page: hooked " + CLS_MANNOR_IMPL
                    + "#renderBizComponentAndMannor");
        } catch (Throwable t) {
            AdSkipModule.log("in-page: hook render failed: " + t);
        }
    }

    // ------------------------------------------------ <ad> 节点的 View 兜底

    /**
     * DrawAdComponent#LJIIIZ(Context, Object, 0N4R, 0MYp) 是 <ad> 节点的 createView。
     * 即使渲染被拦，节点本身也可能被建出来占位，这里把返回的 View 收起来。
     */
    private static void hookDrawComponent(ClassLoader cl) {
        Class<?> clazz = XposedHelpers.findClassIfExists(CLS_DRAW_COMPONENT, cl);
        if (clazz == null) {
            AdSkipModule.log("in-page: class not found: " + CLS_DRAW_COMPONENT);
            return;
        }
        try {
            XposedBridge.hookAllMethods(clazz, "LJIIIZ", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    if (result instanceof View) {
                        collapse(result);
                        AdSkipModule.log("in-page: ad component view hidden");
                    }
                }
            });
            AdSkipModule.log("in-page: hooked " + CLS_DRAW_COMPONENT + "#LJIIIZ");
        } catch (Throwable t) {
            AdSkipModule.log("in-page: hook DrawAdComponent failed: " + t);
        }
    }

    // ---------------------------------------------------------------- util

    /** 把广告容器折叠掉：GONE + 高度 0，并清掉已加进去的子 View */
    private static void collapse(final Object target) {
        if (!(target instanceof View)) {
            return;
        }
        final View view = (View) target;
        Runnable action = new Runnable() {
            @Override
            public void run() {
                try {
                    view.setVisibility(View.GONE);
                    ViewGroup.LayoutParams lp = view.getLayoutParams();
                    if (lp != null && lp.height != 0) {
                        lp.height = 0;
                        view.setLayoutParams(lp);
                    }
                    if (view instanceof ViewGroup) {
                        ViewGroup group = (ViewGroup) view;
                        for (int i = 0; i < group.getChildCount(); i++) {
                            View child = group.getChildAt(i);
                            if (child != null) {
                                child.setVisibility(View.GONE);
                            }
                        }
                    }
                    view.requestLayout();
                } catch (Throwable t) {
                    AdSkipModule.log("in-page: collapse failed: " + t);
                }
            }
        };
        // View.post 在未 attach 时会先入队，attach 后再执行，两种情况都覆盖
        view.post(action);
    }
}
