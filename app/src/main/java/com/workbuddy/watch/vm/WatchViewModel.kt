package com.workbuddy.watch.vm

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.workbuddy.watch.data.Artifact
import com.workbuddy.watch.data.ChatMessage
import com.workbuddy.watch.data.ChatRole
import com.workbuddy.watch.data.CloudSettings
import com.workbuddy.watch.data.CloudTaskRepository
import com.workbuddy.watch.data.ExecEnv
import com.workbuddy.watch.data.FakeTaskRepository
import com.workbuddy.watch.data.NativeLogin
import com.workbuddy.watch.data.RemoteModel
import com.workbuddy.watch.data.SessionExpiredException
import com.workbuddy.watch.data.Task
import com.workbuddy.watch.data.TaskRepository
import com.workbuddy.watch.data.TaskStatus
import com.workbuddy.watch.data.UsageSnapshot
import com.workbuddy.watch.data.WorkBuddyCloudClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.webkit.WebView
import java.io.File

enum class Screen {
    HOME, CONFIRM, RUNNING, RESULT, LIST, HISTORY_DETAIL, ERROR, SETTINGS, MODELS, LOGIN, LOGIN_WEB,
    ASK, ABOUT, USAGE
}
enum class Sheet { NONE, CONN, STOP, FOLLOWUP }

/** 原生登录（手机号 + 短信验证码）表单状态。 */
data class LoginForm(
    val phone: String = "",
    val code: String = "",
    /** 正在请求短信验证码。 */
    val sending: Boolean = false,
    /** 验证码重发倒计时（秒），0 表示可再次发送。 */
    val countdown: Int = 0,
    /** 正在提交验证码换会话。 */
    val submitting: Boolean = false,
    val error: String? = null
)

data class WatchUiState(
    val screen: Screen = Screen.HOME,
    val sheet: Sheet = Sheet.NONE,
    val env: ExecEnv = ExecEnv.CLOUD,
    val connected: Boolean = true,
    /** 云端未连通时的具体原因，用于首页提示；null 表示正常。 */
    val cloudError: String? = null,
    /** 是否已完成 WorkBuddy 云端登录：true 走 [CloudTaskRepository]，false 回退演示。 */
    val loggedIn: Boolean = false,
    /** 登录账号提示（手机号），仅展示用。 */
    val accountHint: String = "",
    /** 原生登录表单。 */
    val login: LoginForm = LoginForm(),
    /** 网页登录（WebView）要打开的入口地址。 */
    val loginUrl: String = "",
    /** 浏览器内核环境检测结果，仅设置页展示用。 */
    val envReport: String? = null,
    /** 文本输入缓冲：首页 / 确认页 / 追问共用。 */
    val draft: String = "",
    /** 已上传到云端、将随任务一并下发的附件。 */
    val attachments: List<Artifact> = emptyList(),
    /** 正在上传附件。 */
    val uploading: Boolean = false,
    /** 正在下载的产物引用（uri），用于按钮 loading。 */
    val downloading: Set<String> = emptySet(),
    val tasks: List<Task> = emptyList(),
    val activeTaskId: String? = null,
    val busy: Boolean = false,
    val toast: String? = null,
    /** 表盘形状：true 圆屏 / false 方屏。用于统一布局内边距。 */
    val roundScreen: Boolean = true,
    /** 是否已在引导页选过表盘形状；false 时首屏强制显示引导页。 */
    val shapeChosen: Boolean = false,
    /** 设置页展示用：当前选用的模型 ID（空表示云端默认）。 */
    val selectedModel: String = "",
    /** 云端可选模型列表。 */
    val models: List<RemoteModel> = emptyList(),
    val modelsLoading: Boolean = false,
    val modelsError: String? = null,
    /** 历史对话详情：完整消息与加载态。 */
    val history: List<ChatMessage> = emptyList(),
    val historyTitle: String = "",
    val historyLoading: Boolean = false,
    val historyError: String? = null,
    /** 历史对话页正在等云端回复（已发出追问，尚未拿到新消息）。 */
    val historyBusy: Boolean = false,
    /** 积分用量：登录后从 `/billing/meter` 接口拉取。 */
    val usage: UsageSnapshot? = null,
    val usageLoading: Boolean = false,
    val usageError: String? = null
) {
    val activeTask: Task? get() = tasks.firstOrNull { it.id == activeTaskId }
    val workingTask: Task? get() = tasks.firstOrNull { it.status == TaskStatus.WORKING || it.status == TaskStatus.BLOCKED }
    val unreadCount: Int get() = tasks.count { it.unread }

    /** 设置页展示用的当前模型名；空 ID 表示云端默认，否则取清单里的名字（没拉过清单就回退 ID）。 */
    val modelLabel: String
        get() = selectedModel.takeIf { it.isNotBlank() }
            ?.let { id -> models.firstOrNull { it.id == id }?.name?.takeIf { it.isNotBlank() } ?: id }
            ?: "云端默认"
}

class WatchViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = CloudSettings(app)

    private val _ui = MutableStateFlow(
        WatchUiState(
            roundScreen = settings.roundScreen,
            shapeChosen = settings.shapeChosen,
            selectedModel = settings.selectedModel
        )
    )
    val ui: StateFlow<WatchUiState> = _ui.asStateFlow()

    /** 已登录云端则直连，否则回退本地演示实现。 */
    private var repo: TaskRepository = buildRepo()

    private var prevStatus: Map<String, TaskStatus> = emptyMap()
    private var toastJob: Job? = null
    private var connectedJob: Job? = null
    private var cloudErrorJob: Job? = null
    private var sessionExpiredJob: Job? = null
    private var tasksJob: Job? = null
    private var countdownJob: Job? = null
    /** 本次登录流程（发码 → 提交）必须共用同一个 Keycloak 会话，故持有同一实例。 */
    private var loginFlow: NativeLogin? = null

    init {
        bindRepo()
    }

    override fun onCleared() {
        (repo as? CloudTaskRepository)?.close()
        super.onCleared()
    }

    private fun buildRepo(): TaskRepository =
        if (settings.isLoggedIn) CloudTaskRepository(settings) else FakeTaskRepository()

    /** 重新订阅当前仓库的数据流（登录 / 登出切换时调用）。 */
    private fun bindRepo() {
        connectedJob?.cancel()
        tasksJob?.cancel()
        cloudErrorJob?.cancel()
        sessionExpiredJob?.cancel()
        prevStatus = emptyMap()
        connectedJob = viewModelScope.launch {
            repo.connected.collect { c -> _ui.update { it.copy(connected = c) } }
        }
        cloudErrorJob = viewModelScope.launch {
            repo.cloudError.collect { e -> _ui.update { it.copy(cloudError = e) } }
        }
        sessionExpiredJob = viewModelScope.launch {
            repo.sessionExpired.collect { expired -> if (expired) onSessionExpired() }
        }
        tasksJob = viewModelScope.launch {
            repo.tasks.collect { list -> handleTasks(list) }
        }
        _ui.update {
            it.copy(
                loggedIn = settings.isLoggedIn,
                accountHint = settings.accountHint,
                connected = repo.connected.value,
                cloudError = repo.cloudError.value,
                tasks = repo.tasks.value,
                roundScreen = settings.roundScreen,
                shapeChosen = settings.shapeChosen,
                selectedModel = settings.selectedModel
            )
        }
    }

    private fun handleTasks(list: List<Task>) {
        val prev = prevStatus
        var navigateTo: Screen? = null
        list.forEach { t ->
            val before = prev[t.id]
            if (before != null && before != t.status) {
                val cur = _ui.value
                when {
                    // 云端抛出 askuser：直接切到应答页
                    t.status == TaskStatus.BLOCKED -> {
                        if (cur.screen == Screen.RUNNING && cur.activeTaskId == t.id) navigateTo = Screen.ASK
                        else showToast("「${t.title}」需要你补充信息")
                    }
                    t.status == TaskStatus.DONE || t.status == TaskStatus.FAILED -> {
                        if (cur.screen == Screen.RUNNING && cur.activeTaskId == t.id) navigateTo = Screen.RESULT
                        else if (cur.screen == Screen.RESULT && cur.activeTaskId == t.id) repo.markRead(t.id)
                        else showToast(t.title)
                    }
                }
            }
        }
        _ui.update {
            it.copy(tasks = list, screen = navigateTo ?: it.screen)
        }
        navigateTo?.let { repo.markRead(_ui.value.activeTaskId ?: "") }
        prevStatus = list.associate { t -> t.id to t.status }
    }

    // ---------- 文字输入 ----------

    /** 首页点「输入任务」进入文字输入页。 */
    fun openCompose() = _ui.update { it.copy(screen = Screen.CONFIRM) }

    fun setDraft(text: String) = _ui.update { it.copy(draft = text) }

    fun pickScene(label: String) {
        _ui.update { it.copy(draft = "帮我${label}：${sceneHint(label)}", screen = Screen.CONFIRM) }
    }

    fun cancelConfirm() = _ui.update { it.copy(draft = "", screen = Screen.HOME) }

    // ---------- 附件（上传） ----------

    /** 选到文件后上传到云端，成功后加入待下发附件。 */
    fun uploadPicked(uri: Uri) {
        if (_ui.value.uploading) return
        val app = getApplication<Application>()
        val resolver = app.contentResolver
        val name = displayName(app, uri) ?: "附件"
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        _ui.update { it.copy(uploading = true) }
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (bytes == null) {
                _ui.update { it.copy(uploading = false, toast = "读取文件失败") }
                clearToastLater()
                return@launch
            }
            runCatching { repo.uploadAttachment(name, mime, bytes) }
                .onSuccess { a ->
                    _ui.update { it.copy(uploading = false, attachments = it.attachments + a, toast = "已附加 ${a.name}") }
                    clearToastLater()
                }
                .onFailure { e ->
                    _ui.update { it.copy(uploading = false, toast = e.message ?: "上传失败") }
                    clearToastLater()
                }
        }
    }

    fun removeAttachment(uri: String) =
        _ui.update { s -> s.copy(attachments = s.attachments.filterNot { it.uri == uri }) }

    /** 手表上没有可用的文件选择器时的提示。 */
    fun pickerUnavailable() {
        _ui.update { it.copy(toast = "手表上没有可用的文件选择器") }
        clearToastLater()
    }

    // ---------- 首页 / 导航 ----------

    fun openList() = _ui.update { it.copy(screen = Screen.LIST) }

    fun goHome() = _ui.update { it.copy(screen = Screen.HOME) }

    fun openTask(id: String) {
        val t = _ui.value.tasks.firstOrNull { it.id == id } ?: return
        when {
            t.status == TaskStatus.FAILED -> _ui.update { it.copy(activeTaskId = id, screen = Screen.ERROR) }
            t.status == TaskStatus.DONE -> {
                repo.markRead(id)
                _ui.update { it.copy(activeTaskId = id, screen = Screen.RESULT) }
            }
            t.status == TaskStatus.BLOCKED && t.ask != null -> {
                repo.markRead(id)
                _ui.update { it.copy(activeTaskId = id, screen = Screen.ASK) }
            }
            else -> _ui.update { it.copy(activeTaskId = id, screen = Screen.RUNNING) }
        }
    }

    // ---------- 确认层 ----------

    fun send() {
        val text = _ui.value.draft
        if (text.isBlank() || _ui.value.busy) return
        if (!_ui.value.connected) {
            _ui.update { it.copy(screen = Screen.ERROR) }
            return
        }
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching { repo.dispatch(text, _ui.value.env, _ui.value.attachments) }
                .onSuccess { id ->
                    _ui.update {
                        it.copy(
                            activeTaskId = id,
                            draft = "",
                            attachments = emptyList(),
                            screen = Screen.RUNNING,
                            busy = false
                        )
                    }
                }
                .onFailure { e ->
                    _ui.update { it.copy(busy = false, screen = Screen.ERROR, toast = e.message ?: "下发失败") }
                    clearToastLater()
                }
        }
    }

    // ---------- 执行中 / askuser ----------

    fun askStop() = _ui.update { it.copy(sheet = Sheet.STOP) }

    fun confirmStop() {
        _ui.value.activeTaskId?.let { repo.stop(it) }
        _ui.update { it.copy(sheet = Sheet.NONE, screen = Screen.LIST) }
    }

    fun askFollowup() = _ui.update { it.copy(sheet = Sheet.FOLLOWUP, draft = "") }

    fun sendFollowup(text: String) {
        val id = _ui.value.activeTaskId ?: return
        if (text.isBlank()) return
        if (!_ui.value.connected) {
            _ui.update { it.copy(sheet = Sheet.NONE, screen = Screen.ERROR) }
            return
        }
        _ui.update { it.copy(sheet = Sheet.NONE, draft = "", screen = Screen.RUNNING) }
        viewModelScope.launch {
            runCatching { repo.reply(id, text) }
                .onFailure { e ->
                    _ui.update { it.copy(screen = Screen.ERROR, toast = e.message ?: "追问失败") }
                    clearToastLater()
                }
        }
    }

    /** 回答云端抛出的 askuser 问题，任务继续执行。 */
    fun answerAsk(answer: String) {
        val id = _ui.value.activeTaskId ?: return
        if (answer.isBlank() || _ui.value.busy) return
        if (!_ui.value.connected) {
            _ui.update { it.copy(screen = Screen.ERROR) }
            return
        }
        _ui.update { it.copy(busy = true, draft = "") }
        viewModelScope.launch {
            runCatching { repo.answerAsk(id, answer) }
                .onSuccess { _ui.update { it.copy(busy = false, screen = Screen.RUNNING) } }
                .onFailure { e ->
                    _ui.update { it.copy(busy = false, screen = Screen.ERROR, toast = e.message ?: "应答失败") }
                    clearToastLater()
                }
        }
    }

    fun retryActive() {
        val id = _ui.value.activeTaskId
        val t = id?.let { repo.tasks.value.firstOrNull { task -> task.id == it } }
        if (!_ui.value.connected) {
            goHome()
            return
        }
        if (t != null && t.status == TaskStatus.FAILED) {
            _ui.update { it.copy(screen = Screen.RUNNING) }
            viewModelScope.launch {
                runCatching { repo.retry(t.id) }
                    .onFailure { e -> _ui.update { it.copy(screen = Screen.ERROR, toast = e.message ?: "重试失败") } }
            }
        } else {
            goHome()
        }
    }

    // ---------- 产物（下载） ----------

    /** 下载云端产物到本机，成功后记录本地路径并提示。 */
    fun downloadArtifact(artifact: Artifact) {
        val taskId = _ui.value.activeTaskId ?: return
        if (artifact.uri in _ui.value.downloading) return
        _ui.update { it.copy(downloading = it.downloading + artifact.uri) }
        viewModelScope.launch {
            val app = getApplication<Application>()
            runCatching {
                val bytes = repo.downloadArtifact(artifact)
                val file = withContext(Dispatchers.IO) { saveArtifact(app, artifact.name, bytes) }
                repo.markDownloaded(taskId, artifact.uri, file.absolutePath)
                file
            }
                .onSuccess { file ->
                    _ui.update { it.copy(downloading = it.downloading - artifact.uri, toast = "已下载到手表：${file.name}") }
                    clearToastLater()
                }
                .onFailure { e ->
                    _ui.update { it.copy(downloading = it.downloading - artifact.uri, toast = e.message ?: "下载失败") }
                    clearToastLater()
                }
        }
    }

    /** 用系统应用打开已下载的产物文件。 */
    fun openArtifact(artifact: Artifact) {
        val path = artifact.localPath
        if (path == null) {
            _ui.update { it.copy(toast = "请先下载该文件") }
            clearToastLater()
            return
        }
        val app = getApplication<Application>()
        val file = File(path)
        if (!file.exists()) {
            _ui.update { it.copy(toast = "文件已不存在，请重新下载") }
            clearToastLater()
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
            val mime = artifact.mimeType ?: app.contentResolver.getType(uri) ?: "*/*"
            app.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }.onFailure {
            _ui.update { s -> s.copy(toast = "手表上没有可打开该文件的 App") }
            clearToastLater()
        }
    }

    // ---------- 连接 / 配置 ----------

    fun openConn() = _ui.update { it.copy(sheet = Sheet.CONN) }

    fun setEnv(env: ExecEnv) = _ui.update { it.copy(env = env, sheet = Sheet.NONE) }

    fun closeSheet() = _ui.update { it.copy(sheet = Sheet.NONE) }

    fun openSettings() = _ui.update { it.copy(screen = Screen.SETTINGS, sheet = Sheet.NONE) }

    fun closeSettings() = _ui.update { it.copy(screen = Screen.HOME) }

    fun openAbout() = _ui.update { it.copy(screen = Screen.ABOUT, sheet = Sheet.NONE) }

    fun closeAbout() = _ui.update { it.copy(screen = Screen.HOME) }

    // ---------- 积分用量 ----------

    fun openUsage() {
        _ui.update { it.copy(screen = Screen.USAGE, sheet = Sheet.NONE, usageError = null) }
        loadUsage()
    }

    fun closeUsage() = _ui.update { it.copy(screen = Screen.HOME) }

    /** 拉取积分；未登录直接给出提示，不发请求。 */
    fun loadUsage() {
        if (!settings.isLoggedIn) {
            _ui.update { it.copy(usage = null, usageLoading = false, usageError = "请先登录") }
            return
        }
        if (_ui.value.usageLoading) return
        _ui.update { it.copy(usageLoading = true, usageError = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { WorkBuddyCloudClient(settings).fetchUsage() }
            }
            result.onSuccess { snap ->
                _ui.update { it.copy(usage = snap, usageLoading = false) }
            }.onFailure { e ->
                if (e is SessionExpiredException) onSessionExpired()
                _ui.update { it.copy(usageLoading = false, usageError = e.message ?: "积分加载失败") }
            }
        }
    }

    // ---------- 引导页 / 表盘形状 ----------

    /** 引导页保存表盘形状选择；之后首屏不再显示引导页。 */
    fun chooseScreenShape(round: Boolean) {
        settings.saveScreenShape(round)
        _ui.update { it.copy(roundScreen = round, shapeChosen = true) }
    }

    /** 设置页里切换表盘形状（圆屏 / 方屏），即时生效。 */
    fun toggleScreenShape() {
        val round = !_ui.value.roundScreen
        settings.saveScreenShape(round)
        _ui.update { it.copy(roundScreen = round, toast = if (round) "已切换为圆形表盘" else "已切换为方形表盘") }
        clearToastLater()
    }

    // ---------- 历史对话 ----------

    /** 打开某会话的完整历史对话（列表 → 打开看完整消息，与网页端一致）。 */
    fun openHistory(id: String) {
        val title = _ui.value.tasks.firstOrNull { it.id == id }?.title ?: "历史对话"
        _ui.update {
            it.copy(
                screen = Screen.HISTORY_DETAIL,
                activeTaskId = id,
                historyTitle = title,
                history = emptyList(),
                historyLoading = true,
                historyError = null
            )
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { repo.loadHistory(id) } }
            result
                .onSuccess { messages ->
                    _ui.update {
                        it.copy(
                            history = messages,
                            historyLoading = false,
                            historyError = if (messages.isEmpty()) "未读取到该会话的消息" else null
                        )
                    }
                }
                .onFailure { e ->
                    _ui.update { it.copy(historyLoading = false, historyError = e.message ?: "历史加载失败") }
                }
        }
    }

    fun closeHistory() =
        _ui.update { it.copy(screen = Screen.LIST, history = emptyList(), historyError = null) }

    /**
     * 在历史对话页里继续追问。
     *
     * 先把用户消息乐观插入列表让界面立刻有反馈，再交给 [CloudTaskRepository.reply]
     * 后台下发；云端不回推事件，因此按固定间隔回拉 `session/load` 直到出现新消息。
     */
    fun sendHistoryMessage() {
        val text = _ui.value.draft.trim()
        val id = _ui.value.activeTaskId ?: return
        if (text.isBlank() || _ui.value.historyBusy) return

        val baseline = _ui.value.history.size
        _ui.update {
            it.copy(
                draft = "",
                historyBusy = true,
                historyError = null,
                history = it.history + ChatMessage(ChatRole.USER, text)
            )
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { repo.reply(id, text) } }
            repeat(HISTORY_POLL_TIMES) {
                delay(HISTORY_POLL_INTERVAL)
                val messages = withContext(Dispatchers.IO) {
                    runCatching { repo.loadHistory(id) }.getOrNull()
                }
                if (messages != null && messages.size > baseline) {
                    _ui.update { it.copy(history = messages, historyBusy = false) }
                    return@launch
                }
            }
            _ui.update { it.copy(historyBusy = false) }
        }
    }

    // ---------- 模型选择 ----------

    /** 打开模型选择页并拉取云端可选模型。 */
    fun openModels() {
        _ui.update { it.copy(screen = Screen.MODELS, modelsLoading = true, modelsError = null) }
        loadModels()
    }

    fun closeModels() = _ui.update { it.copy(screen = Screen.SETTINGS) }

    fun loadModels() {
        _ui.update { it.copy(modelsLoading = true, modelsError = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { repo.listModels() } }
            result
                .onSuccess { list ->
                    _ui.update {
                        it.copy(
                            models = list,
                            modelsLoading = false,
                            modelsError = if (list.isEmpty()) "云端没有返回可选模型" else null
                        )
                    }
                }
                .onFailure { e ->
                    _ui.update { it.copy(modelsLoading = false, modelsError = e.message ?: "模型列表获取失败") }
                }
        }
    }

    /** 选中模型：持久化并回到设置页。空 ID 表示用云端默认模型。 */
    fun chooseModel(id: String) {
        settings.selectedModel = id
        _ui.update { it.copy(selectedModel = id, screen = Screen.SETTINGS, toast = modelToast(id)) }
        clearToastLater()
    }

    /** 切换模型后的提示文案：优先用清单里的模型名。 */
    private fun modelToast(id: String): String =
        if (id.isBlank()) "已使用云端默认模型"
        else "已切换到 " + (_ui.value.models.firstOrNull { it.id == id }?.name ?: id)

    /** 对话页内切换模型：只持久化，不改变当前页面。 */
    fun chooseModelInline(id: String) {
        settings.selectedModel = id
        _ui.update { it.copy(selectedModel = id, toast = modelToast(id)) }
        clearToastLater()
    }

    /** 打开原生登录页（手机号 + 验证码）。 */
    fun openLogin() {
        loginFlow = null
        countdownJob?.cancel()
        _ui.update {
            it.copy(
                screen = Screen.LOGIN,
                sheet = Sheet.NONE,
                // 已登录过则回填手机号，方便再次登录
                login = LoginForm(phone = if (it.loggedIn) it.accountHint else "")
            )
        }
    }

    /**
     * 打开网页登录：在系统浏览器 / WebView 里打开**官方登录页**（[CloudSettings.loginPageUrl]）。
     *
     * 为什么不再用 `/console/accounts`：那个入口会把请求 302 到老的 Keycloak 表单
     * （`client_id=console`），而老表单的发码接口是个死桩——回 200 但从不真的下发短信，
     * 这才是之前「收不到验证码」的原因。官方 SPA 走的是 auth-v2 JSON 链路，短信能到。
     *
     * 原生登录（[NativeLogin]，见登录页）打的也是同一条 auth-v2 接口，所以**本机不依赖浏览器**；
     * 这里保留网页入口只是为了在浏览器里验证 / 兜底。
     */
    fun openWebLogin() {
        countdownJob?.cancel()
        loginFlow = null
        val entry = settings.loginPageUrl()
        _ui.update { it.copy(screen = Screen.LOGIN_WEB, sheet = Sheet.NONE, loginUrl = entry, login = LoginForm()) }
    }

    /** 从网页登录页退回原生登录页。 */
    fun cancelWebLogin() = _ui.update { it.copy(screen = Screen.LOGIN, loginUrl = "") }

    /**
     * 网页登录成功（网关已回跳）：把浏览器里的 cookie 与 UA 收进本机，随后业务请求走原生链路。
     * 必须在主线程调用（要读 WebView 与 CookieManager）。
     */
    fun finishWebLogin(web: WebView) {
        val base = settings.baseUrl.trimEnd('/').ifBlank { CloudSettings.DEFAULT_BASE_URL }
        runCatching { settings.saveUserAgent(web.settings.userAgentString.orEmpty()) }
        runCatching { settings.adoptWebViewCookies(base) }
        settings.saveSession(account = settings.accountHint)
        applySignedIn(settings.accountHint, "已通过网页登录成功")
    }

    fun setLoginPhone(value: String) =
        _ui.update { it.copy(login = it.login.copy(phone = value.filter { c -> c.isDigit() }.take(11), error = null)) }

    fun setLoginCode(value: String) =
        _ui.update { it.copy(login = it.login.copy(code = value.filter { c -> c.isDigit() }.take(6), error = null)) }

    fun cancelLogin() {
        countdownJob?.cancel()
        loginFlow = null
        _ui.update { it.copy(screen = Screen.HOME, login = LoginForm()) }
    }

    /** 第一步：请求短信验证码，成功后开始 60 秒重发倒计时。 */
    fun sendLoginCode() {
        val form = _ui.value.login
        if (form.sending || form.countdown > 0) return
        if (form.phone.length != 11) {
            _ui.update { it.copy(login = form.copy(error = "请输入 11 位手机号")) }
            return
        }
        val flow = NativeLogin(settings).also { loginFlow = it }
        _ui.update { it.copy(login = form.copy(sending = true, error = null)) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { flow.sendCode(form.phone) } }
            result
                .onSuccess { seconds ->
                    if (seconds == NativeLogin.ALREADY_SIGNED_IN) {
                        // Keycloak SSO 还在，免短信直接换到了新会话
                        countdownJob?.cancel()
                        loginFlow = null
                        _ui.update { it.copy(login = LoginForm()) }
                        applySignedIn(form.phone, "已用现有会话登录成功")
                    } else {
                        _ui.update { it.copy(login = it.login.copy(sending = false, countdown = seconds.coerceIn(1, 120))) }
                        startCountdown()
                    }
                }
                .onFailure { e ->
                    // 失败也要退避：验证码接口是按手机号限频的，失败后连续重点只会把额度烧光，
                    // 等真要用的时候反而一直被挡。这里统一压 60 秒重发间隔，并把服务端原话透给用户。
                    _ui.update {
                        it.copy(login = it.login.copy(sending = false, countdown = 60, error = e.message ?: "验证码发送失败"))
                    }
                    startCountdown()
                }
        }
    }

    /** 第二步：提交验证码换取会话，成功后切换为云端仓库。 */
    fun submitLogin() {
        val form = _ui.value.login
        if (form.submitting) return
        if (form.code.length < 4) {
            _ui.update { it.copy(login = form.copy(error = "请输入短信验证码")) }
            return
        }
        val flow = loginFlow
        if (flow == null) {
            _ui.update { it.copy(login = form.copy(error = "请先获取验证码")) }
            return
        }
        _ui.update { it.copy(login = form.copy(submitting = true, error = null)) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { flow.submit(form.phone, form.code) } }
            result
                .onSuccess { account ->
                    countdownJob?.cancel()
                    loginFlow = null
                    _ui.update { it.copy(login = LoginForm()) }
                    applySignedIn(account)
                }
                .onFailure { e ->
                    _ui.update { it.copy(login = it.login.copy(submitting = false, error = e.message ?: "登录失败，请重试")) }
                }
        }
    }

    /**
     * 云端判定登录态失效：清掉本机登录标记（保留 Keycloak SSO），回到「可重新登录」状态。
     * 不整个登出是因为 SSO 还在时重新登录可以免验证码。
     */
    private fun onSessionExpired() {
        settings.markSessionExpired()
        (repo as? CloudTaskRepository)?.close()
        repo = buildRepo()
        bindRepo()
        _ui.update { it.copy(screen = Screen.HOME, sheet = Sheet.NONE, login = LoginForm()) }
        showToast("登录已过期，请重新登录")
    }

    /** 登录成功后切到云端仓库并刷新界面。会话已在 [NativeLogin] 内写盘。 */
    private fun applySignedIn(account: String, toastText: String? = null) {
        (repo as? CloudTaskRepository)?.close()
        repo = buildRepo()
        bindRepo()
        _ui.update {
            it.copy(
                screen = Screen.HOME,
                login = LoginForm(),
                toast = toastText
                    ?: "登录成功，已直连 WorkBuddy 云端${if (account.isNotBlank()) "（$account）" else ""}"
            )
        }
        clearToastLater()
    }

    /** 退出登录：清除本机会话凭据，回退演示实现。 */
    fun logout() {
        loginFlow = null
        countdownJob?.cancel()
        settings.signOut()
        (repo as? CloudTaskRepository)?.close()
        repo = buildRepo()
        bindRepo()
        _ui.update { it.copy(screen = Screen.HOME, sheet = Sheet.NONE, login = LoginForm(), toast = "已退出登录") }
        clearToastLater()
    }

    /**
     * 检测本机浏览器环境。
     *
     * 这件事决定了能不能走「WebView 里完成微信/QQ 登录」：只有 WebView 能实例化，
     * 授权页才渲染得出来，而且登录换到的 cookie 会落在**我们自己的** CookieManager 里，
     * 才谈得上同步进 [CloudSettings.cookies]。外部浏览器 App 不行——它的 cookie 罐
     * 是别的应用的，我们读不到。
     *
     * 注意 WebView 必须在主线程创建；没有内核的设备会在类加载/构造时直接抛 Error，
     * 所以这里连同 [WebView.getCurrentWebViewPackage] 一起包进 runCatching。
     */
    fun checkEnvironment() {
        viewModelScope.launch(Dispatchers.Main) {
            val ctx = getApplication<Application>()
            val lines = buildList {
                val pkg = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
                add("WebView 内核：" + (pkg?.let { "${it.packageName} ${it.versionName}" } ?: "无"))
                val usable = runCatching {
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        loadUrl("about:blank")
                        destroy()
                    }
                    true
                }.getOrDefault(false)
                add("WebView 可用：" + if (usable) "是" else "否")
                val browsers = runCatching {
                    ctx.packageManager.queryIntentActivities(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.workbuddy.cn/")), 0
                    )
                }.getOrDefault(emptyList())
                add("可开网页的 App：" + if (browsers.isEmpty()) "无" else browsers.size.toString())
                browsers.take(5).forEach { add("  · " + it.activityInfo.packageName) }
            }
            _ui.update { it.copy(envReport = lines.joinToString("\n")) }
        }
    }

    /** 验证码重发倒计时。 */
    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            while (_ui.value.login.countdown > 0) {
                delay(1000)
                _ui.update { it.copy(login = it.login.copy(countdown = (it.login.countdown - 1).coerceAtLeast(0))) }
            }
        }
    }

    /** 演示用：模拟与主端断开 / 恢复（云端实现忽略）。 */
    fun setConnected(value: Boolean) {
        repo.setSimulatedOffline(value)
        _ui.update { it.copy(sheet = Sheet.NONE) }
    }

    // ---------- 内部 ----------

    private fun showToast(text: String) {
        _ui.update { it.copy(toast = text) }
        clearToastLater()
    }

    private fun clearToastLater() {
        toastJob?.cancel()
        toastJob = viewModelScope.launch {
            delay(5000)
            _ui.update { it.copy(toast = null) }
        }
    }

    /** 把产物写到应用私有目录，供系统应用打开。 */
    private fun saveArtifact(context: Context, name: String, bytes: ByteArray): File {
        val dir = File(context.filesDir, "artifacts").apply { mkdirs() }
        val safe = name.replace(Regex("[/\\\\]"), "_").ifBlank { "artifact.bin" }
        val file = File(dir, safe)
        file.writeBytes(bytes)
        return file
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    private fun sceneHint(label: String): String = when {
        label.contains("深度研究") -> "调研并输出一份要点报告"
        label.contains("文档处理") -> "整理并生成一份文档"
        label.contains("幻灯片") -> "生成一页演示文稿大纲"
        else -> "完成这件事"
    }

    private companion object {
        /** 追问后回拉历史的最大次数与间隔：3 秒一次，约两分钟。 */
        const val HISTORY_POLL_TIMES = 40
        const val HISTORY_POLL_INTERVAL = 3_000L
    }
}
