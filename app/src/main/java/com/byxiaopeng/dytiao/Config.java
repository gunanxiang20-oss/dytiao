package com.byxiaopeng.dytiao;

/**
 * 模块配置。改完重新编译安装即可生效（无需重启抖音，LSPosed 会热更新模块）。
 *
 * 说明：本模块只作用于本机自己的抖音客户端，用于跳过小程序里的激励视频广告。
 */
public final class Config {

    /** 总开关：跳过激励视频（小游戏/小程序里“看广告得奖励”那种） */
    public static final boolean SKIP_REWARD_VIDEO = true;

    /** 顺带处理“抽奖类”激励广告（RewardAdDrawFragment）。默认关闭，避免误伤其它场景。 */
    public static final boolean SKIP_DRAW_REWARD = false;

    /** 触发跳过的延时（毫秒）。太短可能素材/埋点还没准备好，建议 600~1500。 */
    public static final long SKIP_DELAY_MS = 800L;

    /** 广告 Activity 在跳过逻辑执行后仍然存在时，再等多久强制关闭（毫秒）。0 = 不强制关闭 */
    public static final long FORCE_FINISH_DELAY_MS = 2500L;

    /**
     * 兜底补丁：把 RewardCompleteParams.getWatchTime() 提升到 >= getInspireTime()。
     * 抖音下游是用「观看时长 >= 激励时长」来判断是否发奖，补上这个判断跳过后才能拿到奖励。
     */
    public static final boolean PATCH_WATCH_TIME = true;

    /** 兜底时伪造的观看时长（秒），仅在直接调用 listener 时使用 */
    public static final int FAKE_WATCH_TIME = 999;

    /** 隐藏小程序页面内的广告组件：<ad> banner / 原生 / 抽奖 */
    public static final boolean HIDE_IN_PAGE_ADS = true;

    /** 附加兜底：把 <ad> 节点自己的 View 也收起来（GONE + 0 高），避免页面留空白块 */
    public static final boolean HIDE_AD_COMPONENT_VIEW = true;

    /** 结算后是否主动关掉广告 Fragment（false = 只回调 onClose，让 SDK 自己收尾） */
    public static final boolean EV_CLOSE_FRAGMENT = true;

    /** 是否往抖音设置页注入「跳广告设置」入口 */
    public static final boolean INJECT_SETTINGS_ENTRY = true;

    /** GitHub 项目主页（设置页「关于」里展示） */
    public static final String GITHUB_URL = "https://github.com/gunanxiang20-oss/dytiao";

    /** 诊断日志：定位广告链路，确认可用后关掉 */
    public static final boolean DIAG = false;

    /** 详细日志（Xposed/LSPosed 日志里可查） */
    public static final boolean VERBOSE = true;

    private Config() {
    }
}
