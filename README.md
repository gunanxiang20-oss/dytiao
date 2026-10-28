# 抖音小程序 / 小游戏广告模块（LSPosed）

针对 `base.apk`（**抖音 `com.ss.android.ugc.aweme` 40.6.0 / versionCode 400601**）逆向 + 真机（PKR110 / Android 16 / KernelSU + LSPosed）验证过的模块：

1. **小游戏激励视频秒过并照常发奖**：广告弹出后约 1 秒自动关闭，奖励正常到账；
2. **小程序页面内广告组件隐藏**：`<ad>` banner / 原生 / 抽奖，连带占位块一起收掉。

产物：`DouyinMiniAppAdSkip-2.2.apk`（debug 签名，包名 `com.byxiaopeng.dytiao`）

---

## 一、真机实测结果（v2.0）

| 场景 | 操作 | 结果 |
|------|------|------|
| 小游戏商店「x200 看广告获得」 | 点「获得」→ 广告 1 秒关闭 | 金币 465 → 666（**+201**）✅ |
| 小游戏抽奖机「看广告摇奖」 | 点拉杆 → 广告 1 秒关闭 | 金币 666 → 806（**+140**，弹「奖励x2」）✅ |
| 重复同一入口 | 反复点 | 每次都能拿到 ✅ |

日志特征：

```
ev fragment resumed -> complete in 800ms
ev: onRewardComplete(2) sent
ev: onClose sent
ev: closeFragment called
game ad state(X.0jyi): complete 0 -> 1, play 0
game close payload patched: {"count":1,"isEnded":true}
game close event flushed: count=1
watchTime patched: 0 -> 10
```

## 二、逆向链路（关键结论）

### 激励视频

真机上小游戏**不是**开独立 Activity：宿主把 `com.ss.android.excitingvideo.sdk.ExcitingVideoFragment` 直接塞进
`MiniGameActivity0`，广告内容本身是 Lynx 模板（`com.ss.android.ad.lynx.*` + `com.bytedance.android.ad.rewarded.*`）。

发奖判定分两层，缺一不可：

```
ExcitingVideoFragment#onResume                  ← 广告已展示
   └ IRewardCompleteListener（字段 o）= X.0jyi extends X.0jyj
        LIZJ=playTime  LIZLLL=effectTime  LJ=playCount  LJFF=completeCount
        X.0jyi#onDestroy()
          └ X.0jsw#LIZIZ("close", {"count": LJFF})   ← 小游戏拿这个判断发不发奖
                └ 插件 → JS 的 ad.onClose(res)
```

- `LJFF`（completeCount）只有在 `onComplete(playTime, effectTime, duration)` 且 `playTime >= effectTime` 时才 +1；
- 提前关闭时它还是 0 → 小游戏提示「观看完视频才能获得奖励」；
- JS 侧还要 `res.isEnded = true`。

所以模块做三件事：把 `LJFF` 补成 1、主动调 `onDestroy()` 把 close 事件发出去、在 `X.0jsw#LIZIZ` 里给 payload 补
`isEnded=true`（`count` 兜到 1）。

### 页面内广告组件

```
<ad> 节点 → com.tt.miniapphost.ad.mannor.draw.DrawAdComponent
   服务接口 com.tt.miniapphost.ad.ctrl.draw.BdpMannorAdService
   实现 com.ss.android.ugc.aweme.miniapp.ad.BdpMannorAdServiceImpl
     ├ dealMannorMethod(method,...)  method ∈ shouldShow / hideComponentOnClose / ...
     └ renderBizComponentAndMannor(...)  inflate + addView 到 container
```

模块在 `dealMannorMethod` 的 `shouldShow` 回 false，并让 `renderBizComponentAndMannor` 直接返回 null + 折叠容器。

## 三、设置入口

抖音 **设置页顶部**会多出一行「⏭ 跳广告设置」（样式跟着设置页现有卡片走），点开是模块自己的弹窗：

- **启用广告跳过**（总开关，**默认关闭**）
- 第一次打开开关会弹**免责声明**：列出可能被风控、奖励不发放、账号受限、更新后失效/闪退、违反用户协议等后果，
  点「同意并开启」才会真正开启；点「不同意」保持关闭
- **关于 → GitHub 项目主页**
- 红色加粗提示：**免费模块，禁止倒卖，如果你是付费下载的请联系退款**

配置写在抖音自己的 `shared_prefs/dytiao_config.xml`（同 UID 各进程共享），改完**重启小程序/小游戏**生效。

## 四、hook 清单

| 类 / 方法 | 作用 |
|---|---|
| `com.ss.android.excitingvideo.sdk.ExcitingVideoFragment#onResume` | 广告出现后延时 `SKIP_DELAY_MS` 触发跳过 |
| `X.0jyi`（`IRewardCompleteListener`）`LJFF` / `onDestroy` | 补 completeCount 并主动发 close 事件 |
| `X.0jsw#LIZIZ(String, JSONObject)` | close payload 补 `isEnded=true` / `count>=1` |
| `IRewardCompleteListener$RewardCompleteParams#getWatchTime` | `max(watchTime, inspireTime)` 兜底 |
| `ExcitingVideoFragment` 关闭 | `onRewardComplete(2)` + `onClose()` + `closeFragment()` |
| `BdpMannorAdServiceImpl#dealMannorMethod` / `#renderBizComponentAndMannor` | 页面内广告组件不展示 |
| `DrawAdComponent#LJIIIZ` | 节点 View 兜底 GONE + 0 高 |

## 五、安装

```bash
adb install -r DouyinMiniAppAdSkip-2.2.apk
# LSPosed → 模块 → 启用 → 作用域勾选“抖音” → 强行停止抖音再打开
```

编译：

```bash
export JAVA_HOME=~/Library/Java/JavaVirtualMachines/jbr-17.0.14/Contents/Home
~/.gradle/wrapper/dists/gradle-8.13-all/*/gradle-8.13/bin/gradle :app:assembleRelease
```

## 六、调参（`Config.java`）

| 常量 | 默认 | 说明 |
|------|------|------|
| `SKIP_REWARD_VIDEO` | true | 编译期总开关（运行期开关在抖音设置里） |
| `SKIP_DELAY_MS` | 800 | 广告出现到自动关闭的延时 |
| `EV_CLOSE_FRAGMENT` | true | 跳过时主动关掉广告 Fragment |
| `PATCH_WATCH_TIME` | true | `getWatchTime` 兜底 |
| `HIDE_IN_PAGE_ADS` | true | 页面内广告组件总开关 |
| `HIDE_AD_COMPONENT_VIEW` | true | 节点 View 兜底隐藏 |
| `INJECT_SETTINGS_ENTRY` | true | 是否注入抖音设置入口 |
| `GITHUB_URL` | github.com/byxiaopeng/dytiao | 「关于」里的链接 |
| `DIAG` | false | 链路诊断日志（排查用） |
| `VERBOSE` | true | 常规日志 |

## 七、局限

- **版本绑定**：`X.0jyi` / `X.0jsw` 是混淆名，抖音升级后可能变；`com.ss.android.excitingvideo.*` 相对稳定。
  失效后按同样思路找 `IRewardCompleteListener` 实现 + `LIZIZ("close", ...)` 即可。
- **只覆盖宿主广告**：不处理第三方小程序自己用 WebView/H5 画的广告、开屏广告、信息流广告。
- **风控**：跳过广告仍会向服务端上报“看完”，属于绕过广告变现，别拿主号长时间刷。
