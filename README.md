# 狗头军师 · 微信聊天副驾（LSPosed 模块）v0.2

在微信聊天页顶部悬浮一张卡片：读最近几条消息 → 调 LLM → 给 3 条风格不同的候选回复 → **点一下填入输入框**（本模块不会自动发送）。

这是对 v0.1 的重写版（原版源码以 `bundle*.txt` 形式存放，本版改成正常源码树）。改了什么、为什么改，见 **[IMPROVEMENTS.md](IMPROVEMENTS.md)**。

## 构建

```bash
# 用 Android Studio 直接打开本目录也行
gradle :app:testDebugUnitTest   # 纯逻辑层单测，几秒钟，不需要 Android 设备
gradle :app:assembleDebug       # 产出 app/build/outputs/apk/debug/app-debug.apk
```

推上去后 GitHub Actions 会自动跑测试 + 出 APK（见 `.github/workflows/build.yml`）。

## 安装

1. 装 APK → LSPosed「模块」里启用「狗头军师」→ **作用域勾选微信** → 强杀微信重开。
2. 打开本 App：「设置」填接口地址（OpenAI 兼容，写到 `/v1`）、API Key、模型。
3. 回首页点「接口自检」——真发一次最小请求，确认地址/Key/模型都对。
4. 到微信里打开一个聊天，顶部会出现候选回复卡片。

**改配置不需要重启微信**：模块每次识别前会检查配置文件有没有被改过，改了就地重载。
只有「作用域」这类 LSPosed 层面的改动才需要强杀微信。

## 目录结构

```
app/src/main/java/dev/goutou/wingman/
├── MainActivity.kt / ModuleStatus.kt / HeartbeatReceiver.kt   App 入口、模块探针、心跳
├── config/Config.kt        配置读写（App 与注入代码共用一套 key）
├── llm/
│   ├── Json.kt             手写的最小 JSON（解析 + 生成），核心逻辑因此零依赖
│   ├── Suggestion.kt       模型输出的解析与容错
│   ├── Prompt.kt           内置提示词
│   └── LlmClient.kt        OpenAI 兼容接口（HttpURLConnection，刻意不用 OkHttp）
├── wechat/
│   ├── Snapshot.kt         纯数据中间表示（View 树 → 快照）
│   ├── ChatParser.kt       ★ 快照 → 聊天记录：纯函数，可单测
│   ├── Chrome.kt           时间戳/未读数/群标签等「非聊天内容」规则，两边共用
│   ├── Sensitive.kt        发送前的本地隐私自检
│   ├── ViewReader.kt       ★ 唯一依赖 Android 的部分：View 树 → 快照
│   ├── Overlay.kt          悬浮卡片 + 生命周期
│   └── WeChatHook.kt       Xposed 入口
└── ui/                     Compose 界面（含深色模式）
app/src/test/java/…         纯逻辑层单元测试（22 个用例）
tools/pack-bundle.py        可选：把源码树重新打包成 v0.1 那种 bundle 格式
```

★ 这两文件的分界就是本版最重要的结构改动：**「读 View」和「判断消息是谁发的」被拆开了**，
后者是纯 Kotlin，可以在电脑上跑单元测试，不用每次都装到手机上肉眼试。

## 工作原理

```
Activity.onResume ──► Panel（每个 Activity 一个）
      │
      └─ 每 900ms（不在聊天页 2.6s）：
           ① 屏幕下半部分找得到 EditText？→ 是聊天页，否则收起来
           ② 读消息列表最后一行算「指纹」，没变就什么都不做
           ③ 变了 → ViewReader 把可见行读成 RowSnapshot（气泡底色有缓存，不重复重画）
           ④ ChatParser 判定方向/昵称/过滤噪音 → 最近 N 条 ChatMsg
           ⑤ 最后一条是对方发的？没被关掉？没在最短间隔内？→ 调 LLM
           ⑥ 渲染卡片；点候选 = 写进输入框；长按 = 复制
```

## 调试

- LSPosed 日志里过滤 `[Goutou]`。
- **「读取」里全是 `[图片/表情/语音]`**：说明消息列表选错了（比如选中了表情/更多功能宫格），
  或者你的微信版本里消息文字不是普通 `TextView`。
  **长按卡片标题** → App 首页会出现「诊断」卡片，复制出来即可定位。选列表的逻辑在 `ViewReader.findList()`。
- **读不到消息**：多半是气泡容器识别失败。到 `ViewReader.bubbleOf()` 里看那三个条件
  （`widthRatio < 0.92`、背景像不像气泡、和页面底色差多少），把日志加上打印实际值即可。
- **认不出图片/表情**：`ViewReader.readRow()` 里头像尺寸范围 `dp(24)..dp(84)`。
- **卡片位置不合适**：`Panel.place()`，正常应该是自动跟着消息列表顶部，不需要调。

## 已知限制

- 只对单聊可靠；群聊会尽量带出昵称，但微信的群聊行结构在版本间变化较大。
- 依赖微信当前的消息列表控件类型（`AbsListView` / `RecyclerView`），改了就得跟着改。
- API Key 明文存放在本应用私有目录（原因见「设置」页说明），别把 `cfg.xml` 到处备份。
- 本模块只读消息、只填输入框、不自动发送；但仍属于修改微信客户端行为，有风控风险，建议先用小号。
