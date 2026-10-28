package com.xiaoaibrain

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 配置页 v1.0.26 —— 存储权限申请 + 历史通话数据库检索。
 *
 * 相对 v1.0.25 的改动：
 *  1) 申请「所有文件访问」（MANAGE_EXTERNAL_STORAGE）：
 *     v1.0.25 日志里持续 `ConfigFile 写入失败 EACCES` —— Android 11+ 分区存储
 *     下模块 App 无权写公共 /Download，导致机主设的模型/Key 根本传不到小爱侧。
 *     该权限是 AppOps 特殊权限，系统不给运行时弹窗，只能跳设置页；
 *     未授权时顶部显示琥珀色提示条，点一下直达授权页，回到 App 自动复查。
 *  2) 聊天记录落 SQLite（CallLogDb）：长期留存通话元信息（号码/备注名/时间）
 *     + 全部对话内容，替代只能存"最近一通"的 ChatStore JSON。
 *  3) 新增「历史通话」板块：支持按通话人 + 时间范围筛选，点任一条展开看全文。
 *  4) 修复 AI 气泡不靠右：v1.0.25 靠代码 removeView/addView + gravity=END 换边，
 *     部分 ROM 上 gravity 未生效。改为左右各一份布局，用 weight=1 占位块对齐，
 *     行为确定。
 *
 * ★ 关键约束（v1.0.14 踩坑教训，必须保持）：
 *   实时聊天气泡【只追加、绝不 removeAllViews / 全量重建】——
 *   否则每 2 秒轮询重建会销毁选区与焦点，表现为光标闪烁、无法编辑。
 *   （历史通话列表不受此限：它只在用户点"刷新"/切筛选时重建，不在轮询路径上。）
 */
class MainActivity : AppCompatActivity() {

    private lateinit var llToggle: LinearLayout
    private lateinit var vToggleDot: View
    private lateinit var tvToggleText: TextView

    private lateinit var llPermWarn: LinearLayout

    private lateinit var etBaseUrl: EditText
    private lateinit var etApiKey: EditText
    private lateinit var btnEye: ImageButton
    private lateinit var etModel: AutoCompleteTextView
    private lateinit var btnFetchModels: ImageButton
    private lateinit var tvModelHint: TextView
    private lateinit var btnTest: Button
    private lateinit var sbTimeout: SeekBar
    private lateinit var tvTimeoutVal: TextView
    private lateinit var etTurns: EditText
    private lateinit var etMaxTokens: EditText
    private lateinit var etSystem: EditText
    private lateinit var btnSave: Button
    private lateinit var btnClearChat: Button
    private lateinit var tvCaller: TextView
    private lateinit var svRoot: NestedScrollView
    private lateinit var svChat: NestedScrollView
    private lateinit var llChat: LinearLayout
    private lateinit var tvChatEmpty: TextView
    private lateinit var llLogHeader: LinearLayout
    private lateinit var tvLogHint: TextView
    private lateinit var svLog: NestedScrollView
    private lateinit var tvLog: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvLogPath: TextView
    private lateinit var tvHookStatus: TextView
    private lateinit var tvVersion: TextView

    // ── 历史通话（CallLogDb） ──
    private lateinit var spCaller: Spinner
    private lateinit var spRange: Spinner
    private lateinit var llHistory: LinearLayout
    private lateinit var tvHistoryStat: TextView
    private lateinit var tvHistoryEmpty: TextView
    private lateinit var btnRefreshHistory: Button
    private lateinit var btnClearHistory: Button

    /** 通话人下拉的数据源（号码 + 展示名）；index 0 固定为"全部通话人" */
    private var callerOptions: List<CallRecord> = emptyList()
    private var rangeOptions: List<Pair<Long, String>> = CallLogDb.RANGES

    /** 当前展开显示详情的通话 id；null = 全部收起 */
    private var expandedCallId: Long? = null

    /** 历史列表正在加载中，防止连点刷新时并发查询 */
    private var historyLoading = false

    // 两个独立单线程：配置写 / 数据读，互不抢占
    private val cfgIo = Executors.newSingleThreadExecutor()
    private val dataIo = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val saveHandler = Handler(Looper.getMainLooper())

    // 输入停止 800ms 后统一落盘：打字期间主线程零写盘
    private val persistRunnable = Runnable { persistConfig() }

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val hmFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val callTimeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    private var logExpanded = false
    private var keyVisible = false

    /** 总开关状态（v1.0.25 起不再用平台 Switch，改自绘胶囊） */
    private var hookEnabled = true

    /** 上一次从日志重建出的消息（与广播/持久化消息合并用） */
    private var chatFromLog: List<ChatBus.ChatMsg> = emptyList()

    /** 日志文件指纹：未变化则跳过读盘 + 解析 */
    private var logStamp: Long = -1L
    private var logSize: Long = -1L

    /** tvCaller 上一次的文本（去重，避免相同文本重复 setText） */
    private var lastCallerText: String? = null

    /** tvHookStatus 上一次的文本（同上） */
    private var lastHookText: String? = null

    /** 用户点"清空"的时刻：早于它的消息不再显示（含日志兜底解析出的旧消息） */
    private var clearedAt = 0L

    /**
     * 已消费的会话序号（ChatBus.sessionSeq，v1.0.27）。
     * 新一通电话接入 → ChatReceiver 自增该序号 → 这里发现变化就把聊天面板整块重置：
     * 上一通的气泡全部清掉（数据早在 CallLogDb 的「历史通话」里），只显示这通的对话。
     */
    private var lastSessionSeq = 0L

    /** 本通电话开始时刻：早于它的消息属于上一通，一律不再显示（含日志兜底解析结果） */
    @Volatile private var sessionStartAt = 0L

    /** dataIo 判定出"要重置聊天面板"，置位后由主线程执行一次 resetChatView() */
    @Volatile private var needChatReset = false

    /**
     * 已渲染到 llChat 的气泡 key（role|text|time），用于增量追加去重。
     * ArrayDeque 保证先进先出，超上限时从头部淘汰最旧气泡。
     */
    private val renderedKeys = ArrayDeque<String>()

    /** 气泡上限：超过则移除最旧的（连同其 key），避免无限增长拖慢布局 */
    private val maxBubbles = 60

    /** 上一个气泡的时间戳：与新消息间隔 >3 分钟就插一个居中时间 chip（微信行为） */
    private var lastBubbleAt = 0L

    /** 气泡最大宽度（按屏宽 72% 计算），防止长句撑满整行 */
    private val bubbleMaxWidth by lazy {
        (resources.displayMetrics.widthPixels * 0.72f).toInt()
    }

    /**
     * 最近一次打字时间。刷新轮只在"最近 1.5s 内打过字"时跳过——
     * 旧实现用 currentFocus is EditText 判断，焦点只要停在输入框就整轮跳过，
     * 导致通话后回来盯着聊天框却永远不刷新（v1.0.22 症状）。
     */
    private var lastTypingAt = 0L

    // ★ v1.0.21：轮询循环【单实例保证】（onResume 泄漏循环 → 周期 2000/N ms 的教训）
    private var pollerRunning = false

    private val logRefresher = object : Runnable {
        override fun run() {
            val typing = SystemClock.uptimeMillis() - lastTypingAt < 1500
            if (!typing) runRefresh()
            if (pollerRunning) main.postDelayed(this, 2000)
        }
    }

    private fun startPoller() {
        if (pollerRunning) return
        pollerRunning = true
        main.postDelayed(logRefresher, 2000)
    }

    private fun stopPoller() {
        pollerRunning = false
        main.removeCallbacks(logRefresher)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val t0 = System.currentTimeMillis()
        setContentView(R.layout.activity_main)

        llToggle = findViewById(R.id.llToggle)
        vToggleDot = findViewById(R.id.vToggleDot)
        tvToggleText = findViewById(R.id.tvToggleText)
        llPermWarn = findViewById(R.id.llPermWarn)
        etBaseUrl = findViewById(R.id.etBaseUrl)
        etApiKey = findViewById(R.id.etApiKey)
        btnEye = findViewById(R.id.btnEye)
        etModel = findViewById(R.id.etModel)
        btnFetchModels = findViewById(R.id.btnFetchModels)
        tvModelHint = findViewById(R.id.tvModelHint)
        btnTest = findViewById(R.id.btnTest)
        sbTimeout = findViewById(R.id.sbTimeout)
        tvTimeoutVal = findViewById(R.id.tvTimeoutVal)
        etTurns = findViewById(R.id.etTurns)
        etMaxTokens = findViewById(R.id.etMaxTokens)
        etSystem = findViewById(R.id.etSystem)
        btnSave = findViewById(R.id.btnSave)
        btnClearChat = findViewById(R.id.btnClearChat)
        tvCaller = findViewById(R.id.tvCaller)
        svRoot = findViewById(R.id.svRoot)
        svChat = findViewById(R.id.svChat)
        llChat = findViewById(R.id.llChat)
        tvChatEmpty = findViewById(R.id.tvChatEmpty)
        llLogHeader = findViewById(R.id.llLogHeader)
        tvLogHint = findViewById(R.id.tvLogHint)
        svLog = findViewById(R.id.svLog)
        tvLog = findViewById(R.id.tvLog)
        tvStatus = findViewById(R.id.tvStatus)
        tvLogPath = findViewById(R.id.tvLogPath)
        tvHookStatus = findViewById(R.id.tvHookStatus)
        tvVersion = findViewById(R.id.tvVersion)
        spCaller = findViewById(R.id.spCaller)
        spRange = findViewById(R.id.spRange)
        llHistory = findViewById(R.id.llHistory)
        tvHistoryStat = findViewById(R.id.tvHistoryStat)
        tvHistoryEmpty = findViewById(R.id.tvHistoryEmpty)
        btnRefreshHistory = findViewById(R.id.btnRefreshHistory)
        btnClearHistory = findViewById(R.id.btnClearHistory)

        FileLogger.init(applicationContext)
        tvLogPath.text = "日志文件：\n${FileLogger.path()}"
        tvVersion.text = "版本 v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"
        // 先把上次已知的 hook 状态显示出来（广播可能还没到/小爱还没启动）
        applyHookStatus(HookStatus.load(applicationContext))

        // ── 总开关（自绘胶囊） ──
        llToggle.setOnClickListener {
            setHookEnabled(!hookEnabled)
            schedulePersist()
        }

        // ── 存储权限：点提示条去系统设置授权 ──
        llPermWarn.setOnClickListener { requestStoragePermission() }
        refreshPermBanner()

        // ── 历史通话筛选 ──
        setupHistoryFilters()
        btnRefreshHistory.setOnClickListener { loadHistory(force = true) }
        btnClearHistory.setOnClickListener { confirmClearHistory() }

        btnSave.setOnClickListener {
            // 立即落盘（取消未决防抖），并给用户明确反馈
            saveHandler.removeCallbacks(persistRunnable)
            persistConfig()
            Toast.makeText(this, "已保存（下次通话生效）", Toast.LENGTH_SHORT).show()
        }
        btnTest.setOnClickListener { testConnection() }
        btnClearChat.setOnClickListener {
            ChatBus.clearLocal()
            ChatStore.clear(applicationContext)
            chatFromLog = emptyList()
            clearedAt = System.currentTimeMillis()
            resetChatView()
            Toast.makeText(this, "已清空通话记录", Toast.LENGTH_SHORT).show()
        }
        llLogHeader.setOnClickListener {
            logExpanded = !logExpanded
            svLog.visibility = if (logExpanded) NestedScrollView.VISIBLE else NestedScrollView.GONE
            tvLogHint.text = if (logExpanded) "点击收起 ▴" else "点击展开 ▾"
            if (logExpanded) runRefresh()
        }

        // API Key 眼睛按钮：切换明文/密文（保持光标位置）
        btnEye.setOnClickListener {
            keyVisible = !keyVisible
            val pos = etApiKey.selectionStart.coerceAtLeast(0)
            etApiKey.inputType = if (keyVisible) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            etApiKey.setSelection(pos.coerceAtMost(etApiKey.text?.length ?: 0))
            btnEye.setImageResource(if (keyVisible) R.drawable.ic_visibility_off else R.drawable.ic_visibility)
        }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // 打字时主线程只登记防抖 + 刷新守卫时间戳，绝不在每键同步写盘
                lastTypingAt = SystemClock.uptimeMillis()
                schedulePersist()
            }
        }
        listOf(etBaseUrl, etApiKey, etModel, etTurns, etMaxTokens, etSystem)
            .forEach { it.addTextChangedListener(watcher) }
        sbTimeout.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvTimeoutVal.text = "${progress + 1} 秒"
                if (fromUser) schedulePersist() // 仅用户拖动才落盘，避免 loadConfigToUi 设值触发
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        loadConfigToUi()
        setupModelPicker()
        // 启动即把当前配置同步到外部共享文件（一次性，含首次 chmod），确保 hook 能读到
        cfgIo.execute { ConfigFile.write(buildConfig()) }

        XLog.i("[UI] onCreate 完成，耗时 ${System.currentTimeMillis() - t0}ms（v${BuildConfig.VERSION_NAME}）")
    }

    /**
     * 更新总开关胶囊的三处视觉：胶囊背景 / 圆点 / 文案。
     * 依赖 drawable 的 state_selected 选择器（bg_toggle_pill / bg_toggle_dot）。
     */
    private fun setHookEnabled(on: Boolean) {
        hookEnabled = on
        llToggle.isSelected = on
        vToggleDot.isSelected = on
        tvToggleText.isSelected = on
        tvToggleText.text = if (on) "已开启" else "已关闭"
        // 关闭态胶囊是半透明白底 → 文字用白；开启态胶囊纯白 → 文字用品牌紫，保证对比
        tvToggleText.setTextColor(
            resources.getColor(if (on) R.color.brand else R.color.white, null)
        )
    }

    override fun onResume() {
        super.onResume()
        // 从系统授权页返回时复查权限并立刻补写配置（此前因无权限一直没写成功）
        refreshPermBanner()
        if (StoragePerm.granted(this)) cfgIo.execute { ConfigFile.write(buildConfig()) }
        ensureLogPermission()
        refreshLogPathHint()
        chatFromLog = emptyList()
        logStamp = -1L; logSize = -1L // 复位指纹，强制重新读一次
        applyHookStatus(HookStatus.load(applicationContext))
        loadHistory(force = true)
        startPoller()
    }

    override fun onPause() {
        super.onPause()
        stopPoller()
        // 离开页面前把未决改动落盘（含外部配置），确保 hook 能读到最新
        saveHandler.removeCallbacks(persistRunnable)
        persistConfig()
    }

    private fun prefs() = getSharedPreferences(Config.PREFS_FILE, MODE_PRIVATE)

    /** 输入停止 800ms 后统一落盘：SharedPreferences + 外部配置文件（cfgIo 后台） */
    private fun schedulePersist() {
        saveHandler.removeCallbacks(persistRunnable)
        saveHandler.postDelayed(persistRunnable, 800)
    }

    /** 落盘：主线程写 SharedPreferences（极快），外部文件丢 cfgIo。可在主线程调用。 */
    private fun persistConfig() {
        applyPrefs()
        val cfg = buildConfig()
        cfgIo.execute { ConfigFile.write(cfg) }
    }

    /** 仅写 SharedPreferences（应用私有，非 FUSE，主线程也极快） */
    private fun applyPrefs() {
        prefs().edit()
            .putBoolean("enabled", hookEnabled)
            .putString("base_url", etBaseUrl.text.toString().trim())
            .putString("api_key", etApiKey.text.toString().trim())
            .putString("model", etModel.text.toString().trim())
            .putString("system_prompt", etSystem.text.toString())
            .putInt("timeout_sec", sbTimeout.progress + 1)
            .putInt("context_turns", etTurns.text.toString().toIntOrNull() ?: 50)
            .putInt("max_tokens", etMaxTokens.text.toString().toIntOrNull() ?: 200)
            .apply()
    }

    /** 从界面读取配置（在主线程调用，安全） */
    private fun buildConfig(): Config {
        return Config(
            enabled = hookEnabled,
            baseUrl = etBaseUrl.text.toString().trim(),
            apiKey = etApiKey.text.toString().trim(),
            model = etModel.text.toString().trim(),
            systemPrompt = etSystem.text.toString(),
            timeoutSec = sbTimeout.progress + 1,
            contextTurns = etTurns.text.toString().toIntOrNull() ?: 50,
            maxTokens = etMaxTokens.text.toString().toIntOrNull() ?: 200
        )
    }

    /**
     * 测试连接：放在接口设置板块，用当前配置发一条最小请求。
     * 网络请求在 dataIo 线程执行；结果回主线程更新 tvStatus 并弹 Toast。
     */
    private fun testConnection() {
        val cfg = buildConfig()
        if (!cfg.ready) {
            tvStatus.text = "测试失败：Base URL 或 API Key 为空"
            Toast.makeText(this, "请先填写 Base URL 和 API Key", Toast.LENGTH_SHORT).show()
            return
        }
        tvStatus.text = "正在测试连接 ${cfg.model} …"
        btnTest.isEnabled = false
        dataIo.execute {
            val t0 = SystemClock.elapsedRealtime()
            val r = try {
                LlmClient.chat(cfg, emptyList(), "请只回复四个字：连接成功")
            } catch (t: Throwable) {
                LlmClient.Result(false, null, "${t.javaClass.simpleName}: ${t.message}")
            }
            val cost = SystemClock.elapsedRealtime() - t0
            main.post {
                btnTest.isEnabled = true
                if (r.ok) {
                    val reply = r.text?.take(20)?.trim().orEmpty()
                    tvStatus.text = "✅ 连接成功（${cost}ms · ${cfg.model}）"
                    Toast.makeText(
                        this,
                        "连接成功（${cost}ms）${if (reply.isNotEmpty()) " · 回复：$reply" else ""}",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    tvStatus.text = "❌ 连接失败（${cost}ms）：${r.error}"
                    Toast.makeText(this, "连接失败：${r.error}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun ensureLogPermission() {
        if (permRequested) return
        permRequested = true
        // Android 10 及以下需要普通存储运行时权限；Android 11+ 走「所有文件访问」
        // （AppOps 特殊权限，只能跳系统设置页，见 requestStoragePermission）。
        val legacy = StoragePerm.legacyRuntimePermissions()
        if (legacy.isEmpty()) {
            // 高版本：直接检查并弹授权引导
            if (!StoragePerm.granted(this)) promptStoragePermission()
            return
        }
        val missing = legacy.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return
        try { requestPermissions(missing.toTypedArray(), 1001) } catch (_: Throwable) { }
    }

    private var permRequested = false
    private var permDialogShown = false

    /** 顶部提示条：已授权则隐藏 */
    private fun refreshPermBanner() {
        val need = !StoragePerm.granted(this)
        llPermWarn.visibility = if (need) View.VISIBLE else View.GONE
    }

    /** 点提示条：弹说明对话框 → 跳系统「所有文件访问」设置页 */
    private fun requestStoragePermission() {
        val i = StoragePerm.settingsIntent(this)
        if (i == null) {
            // Android 10 及以下不需要这个权限，提示已满足
            refreshPermBanner()
            Toast.makeText(this, "当前系统版本无需此权限", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(i)
        } catch (_: Throwable) {
            Toast.makeText(this, "无法打开系统授权页，请到「设置 - 应用 - 权限」手动开启", Toast.LENGTH_LONG).show()
        }
    }

    /** 首次进入且未授权时主动引导一次 */
    private fun promptStoragePermission() {
        if (permDialogShown) return
        permDialogShown = true
        AlertDialog.Builder(this)
            .setTitle("需要「所有文件访问」权限")
            .setMessage(
                "小爱大脑需要把模型配置写入共享目录（Download/xiaoaibrain），" +
                    "这样小爱同学才能读到你设置的模型和 API Key。\n\n" +
                    "Android 11 及以上需要在系统设置里手动开启「所有文件访问」。"
            )
            .setPositiveButton("去授权") { _, _ -> requestStoragePermission() }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun refreshLogPathHint() {
        val f = LogFileReader.locate(applicationContext)
        tvLogPath.text = if (f != null) {
            "日志文件（诊断日志直读此文件）：\n${f.absolutePath}\n大小 ${f.length() / 1024} KB"
        } else {
            "尚未找到日志文件：\n${FileLogger.path()}\n\n通话后 hook 侧会写入；聊天记录另有私有备份，不受影响。"
        }
    }

    private fun loadConfigToUi() {
        val p = prefs()
        setHookEnabled(p.getBoolean("enabled", true))
        etBaseUrl.setText(p.getString("base_url", Config.DEFAULT_BASE_URL))
        etApiKey.setText(p.getString("api_key", ""))
        // 模型名是可编辑下拉：setText(text, false) 不触发过滤/弹层
        etModel.setText(p.getString("model", Config.DEFAULT_MODEL) ?: Config.DEFAULT_MODEL, false)
        val t = p.getInt("timeout_sec", 10).coerceIn(1, 30)
        sbTimeout.progress = t - 1
        tvTimeoutVal.text = "$t 秒"
        var turns = p.getInt("context_turns", 50)
        if (turns == 3 && !p.getBoolean("turns_migrated_v50", false)) {
            turns = 50
            p.edit().putBoolean("turns_migrated_v50", true).apply()
        }
        etTurns.setText(turns.toString())
        etMaxTokens.setText(p.getInt("max_tokens", 200).toString())
        // v1.0.23：默认人设更新（机主指定的推销拖字策略版）。
        // 老用户 prefs 里存的是旧默认值 → 一次性迁移到新默认值，保证生效。
        val savedPrompt = p.getString("system_prompt", null)
        val needsMigration = savedPrompt.isNullOrEmpty() ||
            (!p.getBoolean("prompt_migrated_v123", false) &&
                savedPrompt.contains("对方是推销、诈骗、骚扰就委婉找借口快速结束通话"))
        if (needsMigration) {
            etSystem.setText(Config.DEFAULT_SYSTEM_PROMPT)
            p.edit().putString("system_prompt", Config.DEFAULT_SYSTEM_PROMPT)
                .putBoolean("prompt_migrated_v123", true).apply()
        } else {
            etSystem.setText(savedPrompt)
        }
    }

    // ═══════════════════════ 模型名（可编辑下拉 + 服务器拉取，v1.0.28） ═══════════════════════

    /** 当前候选模型清单（服务器返回 or 上次缓存 or 千问兜底） */
    private var modelOptions: List<String> = emptyList()
    private var modelAdapter: ArrayAdapter<String>? = null
    private var modelFetching = false

    /**
     * 初始化模型名选择器。
     *
     * 交互设计（可编辑 + 可搜索 + 可下拉）：
     *   · 输入框本身是 AutoCompleteTextView —— 打字即按前缀/包含过滤候选，选中即填入；
     *   · 点一下输入框（还没打字）就把全部候选弹出来，省得先想模型名；
     *   · 右侧 ⟳ 从服务器 GET {base}/models 拉取最新清单（千问 DashScope 兼容模式通用）；
     *   · 清单里没有的模型名依然可以直接手打 —— 下拉只是辅助，不是白名单。
     */
    private fun setupModelPicker() {
        modelOptions = ModelCatalog.cached(this)
        val ad = ArrayAdapter(this, R.layout.item_model_dropdown, modelOptions.toMutableList())
        modelAdapter = ad
        etModel.setAdapter(ad)
        etModel.threshold = 1
        // 点一下输入框：还没输入内容时把候选全部列出
        etModel.setOnClickListener {
            if (modelOptions.isNotEmpty() && !etModel.isPopupShowing) etModel.showDropDown()
        }
        // 选中某项 → 立即落盘（不用再点保存）
        etModel.setOnItemClickListener { _, _, _, _ -> schedulePersist() }
        btnFetchModels.setOnClickListener { fetchModelList() }
        tvModelHint.text = if (modelOptions.isEmpty()) {
            "点右侧 ⟳ 从服务器获取模型清单"
        } else {
            "候选 ${modelOptions.size} 个（含上次缓存）；点输入框可下拉搜索，也可直接手打"
        }
    }

    /** 点 ⟳：从服务器拉取模型清单（网络在 dataIo 线程，绝不卡主线程） */
    private fun fetchModelList() {
        if (modelFetching) return
        val base = etBaseUrl.text.toString().trim()
        val key = etApiKey.text.toString().trim()
        if (base.isEmpty() || key.isEmpty()) {
            Toast.makeText(this, "请先填写 Base URL 和 API Key", Toast.LENGTH_SHORT).show()
            return
        }
        modelFetching = true
        btnFetchModels.isEnabled = false
        btnFetchModels.alpha = 0.4f
        tvModelHint.text = "正在从服务器获取模型清单…"
        val timeout = sbTimeout.progress + 1
        dataIo.execute {
            val r = ModelCatalog.fetch(applicationContext, base, key, timeout)
            main.post {
                modelFetching = false
                btnFetchModels.isEnabled = true
                btnFetchModels.alpha = 1f
                if (r.ok) {
                    modelOptions = r.models
                    modelAdapter?.let { ad ->
                        ad.clear()
                        ad.addAll(modelOptions)
                        ad.notifyDataSetChanged()
                    }
                    tvModelHint.text = "已获取 ${r.models.size} 个模型，点输入框可下拉搜索选择"
                    Toast.makeText(this, "已获取 ${r.models.size} 个模型", Toast.LENGTH_SHORT).show()
                    if (!etModel.isPopupShowing) etModel.showDropDown()
                } else {
                    tvModelHint.text = "获取失败：${r.error}（仍可手动输入模型名）"
                    Toast.makeText(this, "获取失败：${r.error}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ───────────────────── 刷新轮（dataIo 读盘/解析 → 主线程只做增量渲染） ─────────────────────

    private fun runRefresh() {
        dataIo.execute {
            try {
                // v1.0.27：先看是不是新一通电话接入。判定必须在构建消息之前做，
                // 这样本轮构建出来的 items 就已经只剩这通电话的内容，主线程重置气泡容器后
                // 直接追加即可，绝不会把上一通的气泡又画回去。
                val seq = ChatBus.sessionSeq
                if (seq != lastSessionSeq) {
                    lastSessionSeq = seq
                    sessionStartAt = ChatBus.sessionStartedAt
                    chatFromLog = emptyList()
                    logStamp = -1L
                    logSize = -1L
                    needChatReset = true
                }
                val items = buildChatItems()
                val logText = if (logExpanded) buildLogText() else null
                val hookSnap = HookStatus.load(applicationContext)
                main.post {
                    if (needChatReset) {
                        needChatReset = false
                        resetChatView()
                    }
                    applyCaller()
                    appendChatBubbles(items)
                    if (logText != null) applyLog(logText)
                    applyHookStatus(hookSnap)
                }
            } catch (t: Throwable) {
                // 隔离：刷新轮任何异常都不允许抛到 dataIo 线程（execute 未捕获异常会杀进程）
                XLog.e("[UI] 刷新轮异常（已隔离，不影响主线程）", t)
            }
        }
    }

    /**
     * dataIo 线程：构建聊天消息列表。三通道合并（按 role+text 去重，稳定顺序）：
     *   ① 广播内存 ChatBus.messages（最新，进程活着时实时到达）
     *   ② ChatStore 私有落盘（含进程重启前的完整历史，无需任何权限）
     *   ③ 日志文件解析（兜底；公共 Download 不可读时自然为空）
     */
    private fun buildChatItems(): List<ChatBus.ChatMsg> {
        val fromBus = synchronized(ChatBus.messages) { ChatBus.messages.toList() }
        val fromStore = ChatStore.load(applicationContext).map {
            ChatBus.ChatMsg(it.role, it.text, it.time)
        }
        // 持久化里若存过通话对象而内存没有（进程重启），补回内存供顶部展示
        if (ChatBus.callerName.isNullOrBlank() || ChatBus.callerNumber.isNullOrBlank()) {
            val sn = ChatStore.callerName(applicationContext)
            val snum = ChatStore.callerNumber(applicationContext)
            if (ChatBus.callerName.isNullOrBlank() && !sn.isNullOrBlank()) ChatBus.callerName = sn
            if (ChatBus.callerNumber.isNullOrBlank() && !snum.isNullOrBlank()) ChatBus.callerNumber = snum
        }

        var merged = mergeChat(mergeChat(fromBus, fromStore), chatFromLog)
        if (merged.isEmpty()) {
            // 内存与持久化都没有才读日志兜底（文件未变则复用上次解析结果）
            val f = LogFileReader.locate(applicationContext)
            val stamp = f?.lastModified() ?: 0L
            val size = f?.length() ?: 0L
            if (stamp != logStamp || size != logSize) {
                logStamp = stamp; logSize = size
                try {
                    val lines = LogFileReader.tail(applicationContext, 600)
                    val r = ChatLogParser.parse(lines)
                    if (!r.callerName.isNullOrBlank()) ChatBus.callerName = r.callerName
                    if (!r.callerNumber.isNullOrBlank()) ChatBus.callerNumber = r.callerNumber
                    chatFromLog = r.messages
                } catch (t: Throwable) {
                    XLog.e("[UI] 聊天解析异常，沿用上一轮结果", t)
                }
            }
            merged = chatFromLog
        }

        // "清空"之后的消息才显示（日志兜底解析出的旧消息也要过滤）
        if (clearedAt > 0L) merged = merged.filter { it.time >= clearedAt - 2000 }
        // 新一通的开始时刻之后的消息才显示：上一通的记录已经进了「历史通话」，
        // "最新通话"只放这通的内容（v1.0.27）
        if (sessionStartAt > 0L) merged = merged.filter { it.time >= sessionStartAt - 2000 }
        // 只保留最新 60 条（与气泡上限一致，避免一次性 inflate 过多）
        if (merged.size > maxBubbles) merged = merged.subList(merged.size - maxBubbles, merged.size)
        return merged
    }

    /** dataIo 线程：构建日志文本（文件 + 本地缓冲合并，最多最新 200 行） */
    private fun buildLogText(): String {
        val fromFile = LogFileReader.tail(applicationContext, 300)
        val fromBus = synchronized(HookLogBus.lines) { HookLogBus.lines.toList() }
        val set = LinkedHashSet<String>()
        set.addAll(fromFile)
        set.addAll(fromBus)
        if (set.isEmpty()) return ""
        val list = set.toList()
        val sb = StringBuilder()
        val start = maxOf(0, list.size - 200)
        for (i in start until list.size) sb.append(list[i]).append('\n')
        return sb.toString()
    }

    /** 主线程：更新顶部通话对象行（文本未变则不碰 TextView） */
    private fun applyCaller() {
        val name = ChatBus.callerName?.takeIf { it.isNotBlank() }
        val num = ChatBus.callerNumber?.takeIf { it.isNotBlank() }
        val caller = when {
            !name.isNullOrBlank() && !num.isNullOrBlank() -> "通话对象：$name（$num）"
            !name.isNullOrBlank() -> "通话对象：$name"
            !num.isNullOrBlank() -> "通话对象：$num"
            else -> "通话对象：等待通话…"
        }
        if (caller != lastCallerText) {
            lastCallerText = caller
            tvCaller.text = caller
        }
    }

    /**
     * 主线程：把消息列表【增量追加】为微信式气泡。
     *
     * ★ 只追加，绝不重建：已渲染过的 key 记在 renderedKeys 里直接跳过；
     *   只有超出 maxBubbles 时才从头部淘汰最旧的一个。
     *   绝不能 removeAllViews() 后全量重建 —— 那会每 2 秒销毁一次视图树，
     *   导致光标闪烁 / 无法编辑（v1.0.14 已踩坑）。
     */
    private fun appendChatBubbles(items: List<ChatBus.ChatMsg>) {
        if (items.isEmpty()) {
            if (tvChatEmpty.visibility != View.VISIBLE) tvChatEmpty.visibility = View.VISIBLE
            return
        }

        val nearBottom = isNearBottom(svChat)
        var appended = false

        for (m in items) {
            val key = m.role + "|" + m.text + "|" + m.time
            if (renderedKeys.contains(key)) continue
            // 与上一条间隔 >3 分钟 → 插入居中时间 chip（微信行为）
            if (m.time - lastBubbleAt > 3 * 60_000L) {
                addTimeChip(m.time)
                lastBubbleAt = m.time
            } else if (lastBubbleAt == 0L) {
                lastBubbleAt = m.time
            }
            addBubble(m)
            renderedKeys.addLast(key)
            appended = true

            while (renderedKeys.size > maxBubbles) {
                renderedKeys.removeFirst()
                if (llChat.childCount > 0) llChat.removeViewAt(0)
            }
        }

        if (appended) {
            if (tvChatEmpty.visibility != View.GONE) tvChatEmpty.visibility = View.GONE
            if (nearBottom) svChat.post { scrollToBottom(svChat) }
        }
    }

    /**
     * 追加一条微信式气泡：对方在左、AI 在右、状态提示居中。
     *
     * v1.0.26 修复"AI 头像没靠右"：v1.0.25 用同一份布局 + 代码 removeView/addView
     * 调换顺序、再靠 row gravity=END 右对齐，部分 ROM 上 gravity 不生效导致 AI 行
     * 仍偏左。现在改为**左右各一份布局**（item_chat_peer / item_chat_ai），
     * 靠 weight=1 的弹性占位块吃掉剩余空间实现对齐，行为确定、与 ROM 无关。
     */
    private fun addBubble(m: ChatBus.ChatMsg) {
        if (m.role == ChatBus.ROLE_SYS) {
            val tv = TextView(this)
            tv.text = m.text
            tv.textSize = 11f
            tv.gravity = Gravity.CENTER
            tv.setTextColor(resources.getColor(R.color.text_faint, null))
            tv.setPadding(dp(10), dp(6), dp(10), dp(6))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = Gravity.CENTER_HORIZONTAL
            lp.topMargin = dp(6)
            llChat.addView(tv, lp)
            return
        }

        val isAi = m.role == ChatBus.ROLE_AI
        val row = layoutInflater.inflate(
            if (isAi) R.layout.item_chat_ai else R.layout.item_chat_peer, llChat, false
        )
        val bubble = row.findViewById<TextView>(R.id.tvBubble)
        bubble.text = hmFmt.format(Date(m.time)) + "  " + m.text
        bubble.maxWidth = bubbleMaxWidth

        val lp = row.layoutParams as LinearLayout.LayoutParams
        lp.topMargin = dp(8)
        lp.bottomMargin = dp(2)
        row.layoutParams = lp

        llChat.addView(row)
    }

    /** 追加一条消息到指定容器（历史通话详情复用，容器可换成任意 LinearLayout） */
    private fun addBubbleTo(container: LinearLayout, role: String, text: String, time: Long) {
        val isAi = role == ChatBus.ROLE_AI
        val row = layoutInflater.inflate(
            if (isAi) R.layout.item_chat_ai else R.layout.item_chat_peer, container, false
        )
        val bubble = row.findViewById<TextView>(R.id.tvBubble)
        bubble.text = hmFmt.format(Date(time)) + "  " + text
        bubble.maxWidth = bubbleMaxWidth
        val lp = row.layoutParams as LinearLayout.LayoutParams
        lp.topMargin = dp(6)
        row.layoutParams = lp
        container.addView(row)
    }

    /** 追加一个居中的时间 chip（微信的"下午 3:20"分隔） */
    private fun addTimeChip(time: Long) {
        val tv = TextView(this)
        tv.text = hmFmt.format(Date(time))
        tv.textSize = 11f
        tv.gravity = Gravity.CENTER
        tv.setTextColor(resources.getColor(R.color.text_faint, null))
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.gravity = Gravity.CENTER_HORIZONTAL
        lp.topMargin = dp(10)
        llChat.addView(tv, lp)
    }

    /** 清空聊天视图（用户主动点击"清空"时调用，非轮询路径，可以安全重建） */
    private fun resetChatView() {
        llChat.removeAllViews()
        renderedKeys.clear()
        lastBubbleAt = 0L
        tvChatEmpty.visibility = View.VISIBLE
    }

    // ═══════════════════════ 历史通话（CallLogDb） ═══════════════════════

    /** 初始化两个筛选下拉（通话人 / 时间范围） */
    private fun setupHistoryFilters() {
        rangeOptions = CallLogDb.RANGES
        spRange.adapter = simpleAdapter(rangeOptions.map { it.second })

        spCaller.adapter = simpleAdapter(listOf("全部通话人"))

        spRange.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                // 时间范围切换：只重查列表，保留用户已选的通话人
                loadHistory(force = false)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        spCaller.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                loadHistory(force = false)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun simpleAdapter(items: List<String>): ArrayAdapter<String> =
        ArrayAdapter(this, R.layout.item_spinner_selected, items).apply {
            setDropDownViewResource(R.layout.item_spinner)
        }

    /**
     * 加载历史通话列表（在 dataIo 线程查询，主线程渲染）。
     * @param force true=连"通话人"下拉的数据源一起刷新（进入页面/点刷新）；
     *              false=只按当前筛选重查列表（下拉切换时，避免重复查全表）
     */
    private fun loadHistory(force: Boolean) {
        if (historyLoading) return
        historyLoading = true

        // 读当前筛选值（主线程）。注意 spCaller 第 0 项是"全部通话人"，
        // 对应 callerOptions 的 -1 偏移。
        val callerIdx = spCaller.selectedItemPosition
        val rangeIdx = spRange.selectedItemPosition
        val callerNumber = if (callerIdx > 0) callerOptions.getOrNull(callerIdx - 1)?.number else null
        val since = if (rangeIdx in rangeOptions.indices) {
            val ms = rangeOptions[rangeIdx].first
            if (ms > 0) System.currentTimeMillis() - ms else 0L
        } else 0L

        dataIo.execute {
            var callers: List<CallRecord>? = null
            if (force) {
                try { callers = CallLogDb.get(applicationContext).allCallers() } catch (_: Throwable) { }
            }
            val list = try {
                CallLogDb.get(applicationContext).listCalls(sinceMs = since, callerId = callerNumber)
            } catch (t: Throwable) {
                XLog.e("[UI] 历史通话查询失败", t)
                emptyList()
            }
            main.post {
                historyLoading = false
                if (callers != null) applyCallerOptions(callers)
                applyHistoryList(list)
            }
        }
    }

    /** 刷新"通话人"下拉内容（保持当前选中项，按号码匹配而非下标） */
    private fun applyCallerOptions(callers: List<CallRecord>) {
        // spCaller 第 0 项是"全部通话人"，callerOptions 没有这一项 → 下标偏移 1
        val prevNumber = callerOptions.getOrNull(spCaller.selectedItemPosition - 1)?.number
        callerOptions = callers
        val labels = ArrayList<String>(callers.size + 1)
        labels.add("全部通话人")
        for (c in callers) labels.add(c.title())
        spCaller.adapter = simpleAdapter(labels)

        // 尽量恢复到之前选中的通话人
        if (!prevNumber.isNullOrBlank()) {
            val idx = callers.indexOfFirst { it.number == prevNumber }
            if (idx >= 0) spCaller.setSelection(idx + 1)
        }
    }

    /** 主线程：渲染历史通话列表（此处允许全量重建——只在用户触发时调用） */
    private fun applyHistoryList(list: List<CallRecord>) {
        llHistory.removeAllViews()
        val total = try { CallLogDb.get(applicationContext).totalCalls() } catch (_: Throwable) { 0 }
        tvHistoryStat.text = "共 ${list.size} 通电话 · 累计 $total 通"
        tvHistoryEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        tvHistoryEmpty.text = if (total == 0) "（还没有历史通话记录）" else "（当前筛选下没有记录）"

        for (rec in list) {
            addHistoryRow(rec)
        }
    }

    /** 追加一行历史通话；点击展开/收起该通详情 */
    private fun addHistoryRow(rec: CallRecord) {
        val row = layoutInflater.inflate(R.layout.item_call_record, llHistory, false)
        val title = row.findViewById<TextView>(R.id.tvRowTitle)
        val sub = row.findViewById<TextView>(R.id.tvRowSub)
        val arrow = row.findViewById<TextView>(R.id.tvRowArrow)

        title.text = rec.title()
        val durSec = ((rec.endTime - rec.startTime) / 1000).coerceAtLeast(0)
        sub.text = "${callTimeFmt.format(Date(rec.startTime))} · ${rec.msgCount} 条消息 · 时长 ${durSec}s"

        val lp = row.layoutParams as LinearLayout.LayoutParams
        lp.bottomMargin = dp(8)
        row.layoutParams = lp

        val expanded = expandedCallId == rec.id
        arrow.text = if (expanded) "▴" else "▾"

        row.setOnClickListener { toggleHistoryDetail(rec.id) }
        llHistory.addView(row)

        if (expanded) {
            val detail = createHistoryDetailView(rec)
            llHistory.addView(detail)
        }
    }

    /** 展开某通电话的完整对话（气泡样式，与实时聊天一致） */
    private fun createHistoryDetailView(rec: CallRecord): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setBackgroundResource(R.drawable.bg_history_msgs)
        val pad = dp(10)
        box.setPadding(pad, pad, pad, pad)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(10)
        box.layoutParams = lp

        val msgs = try { CallLogDb.get(applicationContext).messagesOf(rec.id) } catch (_: Throwable) { emptyList() }
        if (msgs.isEmpty()) {
            val tv = TextView(this)
            tv.text = "（这通电话没有保存对话内容）"
            tv.textSize = 12f
            tv.setTextColor(resources.getColor(R.color.text_sub, null))
            box.addView(tv)
            return box
        }
        for (m in msgs) {
            addBubbleTo(box, m.role, m.text, m.time)
        }
        return box
    }

    /** 点击历史行：同一通则收起，否则展开（一次只展开一通） */
    private fun toggleHistoryDetail(callId: Long) {
        expandedCallId = if (expandedCallId == callId) null else callId
        loadHistory(force = false)
    }

    /** 清空历史需要二次确认（破坏性操作） */
    private fun confirmClearHistory() {
        AlertDialog.Builder(this)
            .setTitle("清空全部通话记录？")
            .setMessage("将删除数据库中的所有通话历史（含对方号码、备注名与全部对话内容），不可恢复。")
            .setPositiveButton("清空") { _, _ ->
                dataIo.execute {
                    try { CallLogDb.get(applicationContext).clearAll() } catch (_: Throwable) { }
                    main.post {
                        expandedCallId = null
                        Toast.makeText(this, "已清空全部通话记录", Toast.LENGTH_SHORT).show()
                        loadHistory(force = true)
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 主线程：贴日志文本（内容没变则不碰 TextView；自动滚底同样仅贴近底部时） */
    private fun applyLog(text: String) {
        val show = if (text.isEmpty()) "（暂无日志）" else text
        if (tvLog.text?.toString() != show) {
            val nearBottom = isNearBottom(svLog)
            tvLog.text = show
            if (nearBottom) svLog.post { scrollToBottom(svLog) }
        }
    }

    /** 滚动容器是否贴近底部（误差 60px，容纳一屏左右的缓冲） */
    private fun isNearBottom(sv: NestedScrollView): Boolean {
        val c = sv.getChildAt(0) ?: return true
        return sv.scrollY + sv.height >= c.height - 60
    }

    private fun scrollToBottom(sv: NestedScrollView) {
        val c = sv.getChildAt(0) ?: return
        sv.scrollTo(0, (c.height - sv.height).coerceAtLeast(0))
    }

    /**
     * 主线程：更新 hook 状态提示（版本号正上方）。
     * 数据来源：小爱进程启动并挂载 hook 后广播 → ChatReceiver 持久化到 prefs。
     * Android 13 作用域存储下 UI 进程读不到小爱写的文件，所以走广播通道。
     */
    private fun applyHookStatus(s: HookStatus.Snap?) {
        val (text, colorRes) = when {
            s == null -> "Hook 状态：⚠️ 未检测到 —— 小爱启动后尚未加载本模块（请检查 LSPosed 是否已启用本模块，然后重启小爱）" to R.color.warn
            s.state == HookStatus.STATE_ERROR ->
                "Hook 状态：❌ 加载失败（${HookStatus.formatTime(s.time)}）：${s.msg}" to R.color.warn
            else ->
                "Hook 状态：✅ 已生效（小爱通话已接入大模型 v${s.version} · ${HookStatus.formatTime(s.time)}）" to R.color.ok
        }
        if (text == lastHookText) return
        lastHookText = text
        tvHookStatus.text = text
        tvHookStatus.setTextColor(resources.getColor(colorRes, null))
    }

    /**
     * 合并两组消息：按 role+text 去重，前者在前、后者在后（稳定顺序）。
     * 不按时间排序，避免跨源时间戳偏差触发逐轮跳动。
     */
    private fun mergeChat(first: List<ChatBus.ChatMsg>, second: List<ChatBus.ChatMsg>): List<ChatBus.ChatMsg> {
        if (second.isEmpty()) return first
        if (first.isEmpty()) return second
        val out = ArrayList<ChatBus.ChatMsg>(first.size + second.size)
        val seen = HashSet<String>(first.size + second.size)
        for (m in first) if (seen.add(m.role + "|" + m.text)) out.add(m)
        for (m in second) if (seen.add(m.role + "|" + m.text)) out.add(m)
        return out
    }
}
