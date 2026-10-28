# XiaoAiBrain · 小爱通话接入大模型

把小米「AI 通话 / 小爱通话」接到任意 OpenAI 兼容大模型（千问 DashScope、DeepSeek、OpenAI 等）上的
**Xposed 模块**。对方说话 → 端上 ASR 拿到话术 → 送给大模型生成回复 → 回填给小爱念出来，
通话内容同时以微信式气泡展示、并落库成可检索的历史通话。

> 仅用于个人学习与研究。通话内容属于隐私数据，请自行确认合法合规后再使用。

---

## 功能

| 模块 | 说明 |
| --- | --- |
| 通话接管 | Hook `com.miui.voiceassist` 端侧应答构造点（`PhoneCallSolver#provideInternalFor3_0`）与 NLP 回填点（`NlpManager#notifyPresenterOnNlpBack`），把 LLM 回复塞进播报链路 |
| 上下文 | 按轮次携带历史（默认 50 轮），同一通电话内保持连贯；换人/换通话自动清空 |
| 人设 | 可编辑 System Prompt，默认「推销/骚扰电话拖字诀」人设 |
| 实时聊天 | 微信式气泡（对方左 / AI 右）、头像、时间 chip，通话中实时刷新 |
| 历史通话 | SQLite 落库（通话人、号码、时间、逐条消息），按通话人 + 时间范围筛选检索 |
| 模型选择 | 模型名支持**从服务器拉取清单（GET /models）+ 可编辑下拉 + 输入即搜索**，也可手打任意模型名 |
| 通话切换 | 新电话接入瞬间清空「最新通话」面板（上一通已完整保存在历史通话里） |
| 诊断 | TTS 音频抓取、网络计时（DNS / 连接 / 模型生成）、hook 状态广播回显 |

---

## 快速开始

1. **装模块**：安装 `apk/xiaoaibrain-v1.0.28.apk`，在 Xposed / LSPosed 里启用，作用域勾选「小爱同学（超级小爱）」。
2. **给权限**：打开 App，按顶部琥珀色提示授予「所有文件访问」（Android 11+ 需要，模块要把配置写到共享目录给小爱进程读）。
3. **填配置**：Base URL、API Key、模型名（点模型名右侧 ⟳ 可从服务器拉取清单）、超时、轮次。
   - 千问（DashScope 兼容模式）示例：`https://dashscope.aliyuncs.com/compatible-mode/v1`，模型 `qwen-plus`
4. **重启小爱**（或重启手机），使 hook 生效；App 里「Hook 状态」显示 ✅ 即成功。
5. 来一通电话（或让小爱进入 AI 通话），即可看到气泡与 AI 接管。

---

## 构建

本仓库**不含** `gradlew` 脚本（只在 `gradle/wrapper` 保留了 wrapper 配置），用本机 Gradle 构建：

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk    # 需 platform 34 + build-tools 34.0.0
gradle assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

要点：

- **JDK 17**、`compileSdk 34`、`minSdk 24`。
- Xposed API 已 vendored 到 `app/libs/api-82.jar`，用 `compileOnly(files("libs/api-82.jar"))` 引用。
  这是刻意的：`de.robv.android.xposed:api:82` 不在 google/mavenCentral 仓库，远程坐标在受限网络下必然解析失败。
- 低配机器：`gradle.properties` 里已限制 1GB 堆 + 单 worker + 禁 daemon。
- `local.properties`（SDK 路径）不入库，需自行创建或由 IDE 生成。

---

## 目录结构

```
app/src/main/java/com/xiaoaibrain/
├── AiaasstHook.kt      # 主 hook：ASR 捕获 / LLM 回填 / TTS / 通话对象 / 新通话判定（A1~A6）
├── PhoneCallHook.kt    # 端侧应答构造点 hook
├── LlmClient.kt        # OpenAI 兼容 /chat/completions 客户端（含网络计时诊断）
├── ModelCatalog.kt     # 模型清单拉取 + 缓存（v1.0.28）
├── CallLogDb.kt        # 历史通话 SQLite（calls / messages）
├── ChatBus.kt          # 跨进程聊天事件总线（广播）
├── ChatStore.kt        # 聊天私有 JSON 兜底（无需权限）
├── ChatReceiver.kt     # 广播接收 → 内存 / 落盘 / 落库
├── MainActivity.kt     # 配置页（聊天 / 历史通话 / 日志 / 接口设置）
├── Config.kt / ConfigFile.kt  # 配置模型与共享文件写入
└── ...
app/src/main/res/       # 布局、图标、配色（Material 3 浅色主题，品牌色 #4F6EF7）
```

---

## 版本

- **v1.0.28** 模型名可编辑下拉（服务器拉取 + 搜索选择）；新电话接入即清空「最新通话」面板，上一通完整保留在历史通话
- **v1.0.27** 修复新通话界面不刷新（会话序号 + 气泡容器重置）
- **v1.0.26** 历史通话 SQLite 落库与检索、所有文件访问权限、AI 气泡靠右
- **v1.0.21** 修复轮询循环泄漏导致的整页卡死（onResume 重复投递）

---

## 免责声明

本项目通过 Hook 修改第三方应用的行为，可能随小爱同学版本更新而失效；作者不对通话质量、
数据丢失、账号风险等承担任何责任。请勿用于骚扰、诈骗等违法场景。
