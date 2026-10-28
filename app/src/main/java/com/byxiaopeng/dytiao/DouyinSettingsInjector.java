package com.byxiaopeng.dytiao;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 在抖音「设置」页插入一行「跳广告设置」，点开是模块自己的设置弹窗
 * （开关 + 关于/GitHub），全程在抖音进程内完成，不会触发「前往第三方应用」的风险提示。
 *
 * 真机确认设置页是 com.ss.android.ugc.aweme.setting.ui.DouYinSettingNewVersionActivity，
 * 内容是 SettingNestedScrollView 里一个 LinearLayout（里面按「分组标题 + 卡片」堆）。
 */
final class DouyinSettingsInjector {

    private static final String CLS_SETTINGS =
            "com.ss.android.ugc.aweme.setting.ui.DouYinSettingNewVersionActivity";
    private static final String CLS_SCROLL = "com.ss.android.ugc.aweme.setting.ui.SettingNestedScrollView";
    private static final String TAG_ENTRY = "dytiao_settings_entry";

    /** 防止 switch 回滚时递归触发监听 */
    private static boolean sIgnoreSwitch;

    private DouyinSettingsInjector() {
    }

    static void install(ClassLoader cl) {
        Class<?> cls = XposedHelpers.findClassIfExists(CLS_SETTINGS, cl);
        if (cls == null) {
            AdSkipModule.log("settings: class not found " + CLS_SETTINGS);
            return;
        }
        try {
            XposedBridge.hookAllMethods(cls, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof Activity)) {
                        return;
                    }
                    final WeakReference<Activity> ref =
                            new WeakReference<>((Activity) param.thisObject);
                    for (int i = 1; i <= 5; i++) {
                        AdSkipModule.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                Activity activity = ref.get();
                                if (activity != null && !activity.isFinishing()) {
                                    inject(activity);
                                }
                            }
                        }, 300L * i);
                    }
                }
            });
            AdSkipModule.log("settings: hooked " + CLS_SETTINGS);
        } catch (Throwable t) {
            AdSkipModule.log("settings: hook failed " + t);
        }
    }

    private static void inject(Activity activity) {
        try {
            View root = activity.getWindow().getDecorView();
            ViewGroup content = findContent(root);
            if (content == null) {
                return;
            }
            if (content.findViewWithTag(TAG_ENTRY) != null) {
                return;
            }
            View row = buildRow(activity, findCardBackground(content));
            row.setTag(TAG_ENTRY);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 56));
            lp.leftMargin = dp(activity, 16);
            lp.rightMargin = dp(activity, 16);
            lp.topMargin = dp(activity, 8);
            content.addView(row, 0, lp);
            AdSkipModule.log("settings: entry injected");
        } catch (Throwable t) {
            AdSkipModule.log("settings: inject failed " + t);
        }
    }

    private static ViewGroup findContent(View root) {
        View scroll = findByClass(root, CLS_SCROLL);
        if (!(scroll instanceof ViewGroup)) {
            return null;
        }
        ViewGroup sv = (ViewGroup) scroll;
        for (int i = 0; i < sv.getChildCount(); i++) {
            View child = sv.getChildAt(i);
            if (child instanceof ViewGroup && ((ViewGroup) child).getChildCount() > 2) {
                return (ViewGroup) child;
            }
        }
        return null;
    }

    /** 借用页面里已有卡片的背景，视觉上跟原生条目一致 */
    private static Drawable findCardBackground(ViewGroup content) {
        for (int i = 0; i < content.getChildCount(); i++) {
            View child = content.getChildAt(i);
            if (child instanceof ViewGroup && child.getBackground() != null
                    && ((ViewGroup) child).getChildCount() >= 2) {
                Drawable.ConstantState cs = child.getBackground().getConstantState();
                if (cs != null) {
                    return cs.newDrawable(content.getResources()).mutate();
                }
            }
        }
        return null;
    }

    private static View buildRow(Context ctx, Drawable bg) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 16), 0, dp(ctx, 16), 0);
        if (bg != null) {
            row.setBackground(bg);
        }
        row.setClickable(true);
        row.setFocusable(true);

        TextView icon = new TextView(ctx);
        icon.setText("⏭");
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        iconLp.rightMargin = dp(ctx, 12);
        row.addView(icon, iconLp);

        TextView title = new TextView(ctx);
        title.setText("跳广告设置");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTextColor(themeColor(ctx, android.R.attr.textColorPrimary));
        row.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = new TextView(ctx);
        arrow.setText("›");
        arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        arrow.setTextColor(themeColor(ctx, android.R.attr.textColorSecondary));
        row.addView(arrow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDialog(v.getContext());
            }
        });
        return row;
    }

    private static void showDialog(Context ctx) {
        try {
            LinearLayout box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), 0);

            final Switch sw = new Switch(ctx);
            sw.setText("启用广告跳过");
            sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            sw.setChecked(Prefs.enabled(ctx));
            sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, final boolean isChecked) {
                    if (sIgnoreSwitch) {
                        return;
                    }
                    final Context c = buttonView.getContext();
                    if (isChecked && !Prefs.agreed(c)) {
                        sIgnoreSwitch = true;
                        buttonView.setChecked(false);
                        sIgnoreSwitch = false;
                        showDisclaimer(c, new Runnable() {
                            @Override
                            public void run() {
                                Prefs.setAgreed(c, true);
                                Prefs.setEnabled(c, true);
                                sIgnoreSwitch = true;
                                sw.setChecked(true);
                                sIgnoreSwitch = false;
                                Toast.makeText(c, "已开启，重新进入小程序生效",
                                        Toast.LENGTH_SHORT).show();
                            }
                        });
                        return;
                    }
                    Prefs.setEnabled(c, isChecked);
                    Toast.makeText(c, isChecked ? "已开启，重新进入小程序生效" : "已关闭",
                            Toast.LENGTH_SHORT).show();
                }
            });
            box.addView(sw, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 52)));

            TextView desc = new TextView(ctx);
            desc.setText("小游戏激励视频自动关闭并照常发奖；小程序页面内广告组件隐藏。");
            desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            desc.setTextColor(themeColor(ctx, android.R.attr.textColorSecondary));
            desc.setPadding(0, 0, 0, dp(ctx, 8));
            box.addView(desc, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));


            View line = new View(ctx);
            line.setBackgroundColor(0x22888888);
            LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(ctx, 1)));
            lineLp.topMargin = dp(ctx, 8);
            box.addView(line, lineLp);

            TextView about = new TextView(ctx);
            about.setText("关于");
            about.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            about.setTypeface(null, Typeface.BOLD);
            about.setTextColor(themeColor(ctx, android.R.attr.textColorPrimary));
            about.setPadding(0, dp(ctx, 12), 0, dp(ctx, 4));
            box.addView(about, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            TextView link = new TextView(ctx);
            link.setText("GitHub 项目主页");
            link.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            link.setTextColor(0xFF3B7DD8);
            link.setPadding(0, dp(ctx, 6), 0, dp(ctx, 6));
            link.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(Config.GITHUB_URL));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        v.getContext().startActivity(intent);
                    } catch (Throwable t) {
                        Toast.makeText(v.getContext(), Config.GITHUB_URL, Toast.LENGTH_LONG).show();
                    }
                }
            });
            box.addView(link, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            TextView warn = new TextView(ctx);
            warn.setText("免费模块，禁止倒卖，如果你是付费下载的请联系退款");
            warn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            warn.setTypeface(null, Typeface.BOLD);
            warn.setTextColor(0xFFE53935);
            warn.setPadding(0, dp(ctx, 14), 0, dp(ctx, 4));
            box.addView(warn, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            TextView version = new TextView(ctx);
            version.setText("版本 2.2 · byxiaopeng");
            version.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            version.setTextColor(themeColor(ctx, android.R.attr.textColorSecondary));
            version.setPadding(0, dp(ctx, 10), 0, 0);
            box.addView(version, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            new AlertDialog.Builder(ctx)
                    .setTitle("跳广告设置")
                    .setView(box)
                    .setPositiveButton("关闭", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                        }
                    })
                    .show();
        } catch (Throwable t) {
            AdSkipModule.log("settings: dialog failed " + t);
        }
    }

    // --------------------------------------------------------------- 免责协议

    private static void showDisclaimer(final Context ctx, final Runnable onAgree) {
        try {
            ScrollView scroll = new ScrollView(ctx);
            TextView text = new TextView(ctx);
            text.setText(DISCLAIMER);
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            text.setTextColor(themeColor(ctx, android.R.attr.textColorPrimary));
            text.setPadding(dp(ctx, 20), dp(ctx, 12), dp(ctx, 20), dp(ctx, 12));
            text.setLineSpacing(dp(ctx, 4), 1f);
            scroll.addView(text);
            new AlertDialog.Builder(ctx)
                    .setTitle("免责声明")
                    .setView(scroll)
                    .setCancelable(false)
                    .setPositiveButton("同意并开启", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            onAgree.run();
                        }
                    })
                    .setNegativeButton("不同意", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            Prefs.setEnabled(ctx, false);
                            Toast.makeText(ctx, "未同意，功能保持关闭", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .show();
        } catch (Throwable t) {
            AdSkipModule.log("settings: disclaimer failed " + t);
        }
    }

    private static final String DISCLAIMER =
            "请在使用前完整阅读以下内容。\n\n"
                    + "1. 本模块仅供个人学习、研究与技术交流使用，修改的是你本机抖音客户端的行为。\n\n"
                    + "2. 跳过激励视频广告后，抖音服务端仍可能收到「广告已看完」的上报，可能被判定为异常行为，"
                    + "导致奖励不发放、活动资格取消、账号被限制功能甚至封禁。\n\n"
                    + "3. 抖音客户端更新后，模块可能失效、部分功能异常或引起闪退；请在更新后自行确认，"
                    + "必要时关闭模块或卸载。\n\n"
                    + "4. 使用本模块可能违反抖音的用户协议及相关平台规则，由此产生的一切后果（包括但不限于"
                    + "账号处罚、数据丢失、财产损失）由使用者自行承担。\n\n"
                    + "5. 作者不对任何直接或间接损失负责，也不提供任何形式的担保。\n\n"
                    + "6. 本模块完全免费，禁止任何形式的倒卖、二次销售或付费代装。\n\n"
                    + "点击「同意并开启」表示你已阅读、理解并接受以上全部内容。";

    private static View divider(Context ctx, int topMargin) {
        View line = new View(ctx);
        line.setBackgroundColor(0x22888888);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(ctx, 1)));
        lp.topMargin = topMargin;
        line.setLayoutParams(lp);
        return line;
    }

    private static View findByClass(View root, String className) {
        if (root.getClass().getName().equals(className)) {
            return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findByClass(group.getChildAt(i), className);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static int themeColor(Context ctx, int attr) {
        TypedValue tv = new TypedValue();
        if (ctx.getTheme().resolveAttribute(attr, tv, true)) {
            if (tv.resourceId != 0) {
                return ctx.getResources().getColor(tv.resourceId, ctx.getTheme());
            }
            return tv.data;
        }
        return 0xFF000000;
    }
}
