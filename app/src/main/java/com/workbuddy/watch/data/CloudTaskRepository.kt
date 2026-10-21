package com.workbuddy.watch.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 云端任务数据源：手表脱离手机，直接调用 WorkBuddy「云上模式」。
 *
 * 状态同步用轮询而非常驻 SSE：手表息屏时系统会回收长连接，轮询更稳。
 * 下发/追问/中断走 [WorkBuddyCloudClient] 的 ACP 调用（session/prompt、session/cancel）。
 */
class CloudTaskRepository(private val settings: CloudSettings) : TaskRepository {

    private val client = WorkBuddyCloudClient(settings)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pollers = mutableMapOf<String, Job>()

    /**
     * 界面是否在前台（亮屏）。息屏时轮询协程挂在 [foregroundFlow] 上，一个请求都不发；
     * 抬手亮屏立即通过，补拉一次最新的会话状态。
     */
    private val foregroundFlow = MutableStateFlow(true)

    /** 会话接入点缓存（link/token），供追问与中断复用，避免每次重复握手。 */
    private val sessions = mutableMapOf<String, WorkBuddyCloudClient.Session>()

    /** 云端会话当前已生效的模型，避免每次都重复发 `session/set_model`。 */
    private val serverModels = mutableMapOf<String, String>()

    /** 用户已在腕上手动停止的会话：云端可能仍报执行中，不能被覆盖回 [TaskStatus.WORKING]。 */
    private val stoppedIds = mutableSetOf<String>()

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    override val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _cloudError = MutableStateFlow<String?>(null)
    override val cloudError: StateFlow<String?> = _cloudError.asStateFlow()

    private val _sessionExpired = MutableStateFlow(false)
    override val sessionExpired: StateFlow<Boolean> = _sessionExpired.asStateFlow()

    init {
        scope.launch { refreshList() }
        scope.launch { healthLoop() }
    }

    override suspend fun dispatch(prompt: String, env: ExecEnv, attachments: List<Artifact>): String =
        withContext(Dispatchers.IO) {
            // 用户在设置里选过模型就用选中的，否则回退 web 端同款默认模型
            val model = currentModel()
            val conversation = client.createConversation(prompt, model)
            val session = conversation.session ?: client.fetchSession(conversation.id)
            sessions[conversation.id] = session
            val task = Task(
                id = conversation.id,
                title = conversation.title ?: prompt,
                status = TaskStatus.WORKING,
                env = env,
                doneSteps = 0,
                totalSteps = 0 // 云端不回传步数，腕上用不确定进度展示
            )
            _tasks.update { listOf(task) + it }
            // 先起轮询：云端一开始执行就能看到 RUNNING，不必等 HTTP 返回
            startPolling(conversation.id)
            // 关键：create 里的 prompt 只截前 100 字用来生成会话标题，不会真正执行。
            // 首轮指令必须通过 ACP session/prompt 下发，否则只会留下一个空会话。
            //
            // session/prompt 的 HTTP 响应要等整个回合跑完才返回，云端思考几分钟就会把
            // 连接读超时。指令在写出请求体时就已经送达，所以这里不等待响应：
            // 后台发出、忽略超时，进度交给上面的轮询。
            deliver(conversation.id) { client.sendPrompt(session, prompt, attachments) }
            conversation.id
        }

    override suspend fun reply(id: String, prompt: String) = withContext(Dispatchers.IO) {
        stoppedIds.remove(id)
        updateTask(id) { it.copy(status = TaskStatus.WORKING, error = null, ask = null) }
        startPolling(id)
        deliver(id) {
            val session = sessionOf(id)
            applyModel(id, session)
            client.sendPrompt(session, prompt)
        }
    }

    /**
     * 回答 askuser：拿到中断 ID 时走 ACP 精确回传，否则退化为普通追问，
     * 让云端按「用户补充信息」继续。
     */
    override suspend fun answerAsk(id: String, answer: String) = withContext(Dispatchers.IO) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return@withContext
        val ask = task.ask
        updateTask(id) { it.copy(status = TaskStatus.WORKING, ask = null, unread = false, error = null) }
        startPolling(id)
        deliver(id) {
            val session = sessionOf(id)
            applyModel(id, session)
            if (ask?.requestId != null) {
                client.resolveInterruption(session, ask, answer)
            } else {
                client.sendPrompt(session, answer)
            }
        }
    }

    override suspend fun uploadAttachment(name: String, mimeType: String, bytes: ByteArray): Artifact =
        withContext(Dispatchers.IO) { client.uploadFile(name, mimeType, bytes) }

    override suspend fun downloadArtifact(artifact: Artifact): ByteArray =
        withContext(Dispatchers.IO) { client.downloadArtifact(artifact) }

    /**
     * 历史对话详情：走 ACP `session/load` 把整段会话回放到 SSE 流上（与网页端一致）。
     * 会话接入点优先复用轮询里已缓存的那份，没有就现取一次。
     */
    override suspend fun loadHistory(id: String): List<ChatMessage> =
        withContext(Dispatchers.IO) { client.loadHistory(sessionOf(id)) }

    override suspend fun listModels(): List<RemoteModel> =
        withContext(Dispatchers.IO) { client.listModels(settings.enterpriseId) }

    override fun stop(id: String) {
        pollers.remove(id)?.cancel()
        stoppedIds.add(id)
        updateTask(id) { it.copy(status = TaskStatus.STOPPED, unread = false, ask = null) }
        // 云端中断是尽力而为：会话接入点可能已过期，重取一次再试。
        // 腕上已经是终态，不会因为这次失败把界面翻回「执行中」。
        scope.launch {
            repeat(2) { attempt ->
                if (runCatching { client.cancelTurn(sessionOf(id)) }.isSuccess) return@launch
                if (attempt == 0) {
                    sessions.remove(id)
                    delay(500)
                }
            }
        }
    }

    override suspend fun retry(id: String) = withContext(Dispatchers.IO) {
        val prompt = _tasks.value.firstOrNull { it.id == id }?.title.orEmpty()
        stoppedIds.remove(id)
        updateTask(id) { it.copy(status = TaskStatus.WORKING, error = null) }
        startPolling(id)
        deliver(id) {
            val session = sessionOf(id)
            applyModel(id, session)
            client.sendPrompt(session, prompt)
        }
    }

    override fun markRead(id: String) {
        updateTask(id) { it.copy(unread = false) }
    }

    override fun markDownloaded(taskId: String, uri: String, localPath: String) {
        updateTask(taskId) { t ->
            val files = t.result?.files.orEmpty().map { if (it.uri == uri) it.copy(localPath = localPath) else it }
            t.copy(result = t.result?.copy(files = files))
        }
    }

    /** 切换配置时释放旧的轮询协程。 */
    fun close() {
        scope.cancel()
    }

    override fun setForeground(foreground: Boolean) {
        foregroundFlow.value = foreground
    }

    // ---------- 内部 ----------

    private fun sessionOf(id: String): WorkBuddyCloudClient.Session =
        sessions[id] ?: client.fetchSession(id).also { sessions[id] = it }

    /** 当前选中的模型；没选过就用网页端同款默认模型。 */
    private fun currentModel(): String =
        settings.selectedModel.ifBlank { WorkBuddyCloudClient.DEFAULT_MODEL }

    /**
     * 让云端会话切到当前选中模型。
     *
     * 追问是新建一条 ACP 连接，模型不会自动继承，必须先 `session/set_model`；
     * 设置失败也要继续下发，否则用户会卡在「点了没反应」。
     */
    private fun applyModel(id: String, session: WorkBuddyCloudClient.Session) {
        val model = currentModel()
        if (serverModels[id] == model) return
        runCatching { client.setModel(session, model) }.onSuccess { serverModels[id] = model }
    }

    /**
     * 后台下发指令：界面不等 HTTP 响应，超时也不算失败。
     *
     * `session/prompt` 的响应要等云端把整个回合跑完才返回，思考几分钟就必然触发读超时。
     * 但请求体写出时指令已经送达，云端会继续执行，所以这里只负责「送出去」，
     * 之后的进度由 [startPolling] 轮询反映。
     */
    private fun deliver(id: String, call: () -> Unit) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { call() }
            } catch (_: java.net.SocketTimeoutException) {
                // 云端仍在执行，属于正常情况
            } catch (e: Exception) {
                updateTask(id) {
                    it.copy(error = "指令下发失败：" + (e.message ?: e.javaClass.simpleName).take(60))
                }
            }
        }
    }

    private suspend fun refreshList() {
        runCatching { client.listConversations() }
            .onSuccess { remote ->
                markHealthy()
                val mapped = remote.map { it.toTask(envOf(it.id)) }
                if (mapped.isNotEmpty()) {
                    _tasks.update { local -> merge(local, mapped) }
                }
                // 列表里仍标执行中的任务（含重启 app 前的）要接着盯，
                // 否则云端早已结束、腕上却一直停在「执行中」
                _tasks.value
                    .filter { it.status == TaskStatus.WORKING && !pollers.containsKey(it.id) }
                    .forEach { startPolling(it.id) }
            }
            .onFailure { markUnhealthy(it) }
    }

    private suspend fun healthLoop() {
        while (scope.isActive) {
            // 已连通时不做主动探测：任务轮询和页面操作本身就会刷新连接状态，
            // 只有「还没连上」或「已掉线」才需要周期重试，避免息屏后还要一直发请求。
            if (!_connected.value) {
                foregroundFlow.first { it }
                runCatching { client.listConversations(size = 1) }
                    .onSuccess { markHealthy() }
                    .onFailure { markUnhealthy(it) }
            }
            delay(HEALTH_INTERVAL)
        }
    }

    private fun markHealthy() {
        _connected.value = true
        _cloudError.value = null
    }

    /** 把异常转成用户能看懂的一句话，直接显示在首页「未连通」提示里。 */
    private fun markUnhealthy(error: Throwable) {
        _connected.value = false
        _cloudError.value = when (error) {
            is SessionExpiredException -> {
                // 网关已不认这份登录态且静默续期也失败：交给界面回到「重新登录」入口
                _sessionExpired.value = true
                "登录已过期，请重新登录"
            }
            is java.net.UnknownHostException, is java.net.ConnectException -> "网络不可用，请检查手表网络"
            is java.net.SocketTimeoutException -> "请求超时，请稍后重试"
            is javax.net.ssl.SSLException -> "网络连接不安全，请检查网络"
            else -> (error.message ?: error.javaClass.simpleName).take(80)
        }
    }

    /**
     * 任务进度轮询。
     *
     * 省流量两条规则：
     * 1. 指数退避 2s → 30s。云端跑几分钟的任务，请求数从「每 2 秒一次」降到几次。
     * 2. 息屏即挂起：整个协程停在 [foregroundFlow] 上，不发任何请求；
     *    抬手亮屏立刻放行，补拉一次最新状态，进度感知没有损失。
     */
    private fun startPolling(id: String) {
        pollers.remove(id)?.cancel()
        pollers[id] = scope.launch {
            var backoff = INITIAL_POLL_INTERVAL
            while (isActive) {
                // 息屏：这里挂起，期间零请求
                foregroundFlow.first { it }

                // 退避等待；若中途息屏则直接进入下一轮（下一轮会挂起等待亮屏）
                val wentBackground = withTimeoutOrNull(backoff) { foregroundFlow.first { !it } }
                if (wentBackground != null) continue

                val remote = runCatching { client.getConversation(id) }.getOrNull()
                if (remote == null) {
                    backoff = (backoff * 2).coerceAtMost(MAX_POLL_INTERVAL)
                    continue
                }
                val remoteStatus = remote.status.toTaskStatusOrNull()
                if (remoteStatus == TaskStatus.WORKING) remote.session?.let { sessions[id] = it }
                updateTask(id) { t ->
                    // 用户已在腕上停止的会话，云端可能还报 RUNNING，不能把状态翻回去
                    val status = remoteStatus?.takeUnless {
                        t.status == TaskStatus.STOPPED && it == TaskStatus.WORKING
                    } ?: t.status
                    t.copy(
                        status = status,
                        title = remote.title ?: t.title,
                        error = if (status == TaskStatus.FAILED) "云端执行失败" else null,
                        unread = status == TaskStatus.DONE || status == TaskStatus.FAILED ||
                            (status == TaskStatus.BLOCKED && t.ask == null),
                        // 等待补充信息时给出云端抛出的问题
                        ask = if (status == TaskStatus.BLOCKED) (remote.ask ?: t.ask) else null,
                        result = if (status == TaskStatus.DONE) cloudResult(remote, t.result) else t.result
                    )
                }
                val now = _tasks.value.firstOrNull { it.id == id }?.status
                if (now == null || now.isTerminal()) return@launch
                backoff = (backoff * 2).coerceAtMost(MAX_POLL_INTERVAL)
            }
        }
    }

    /** 云端结果：把会话里解析到的产物挂到结果上，并保留已下载的本地路径。 */
    private fun cloudResult(
        remote: WorkBuddyCloudClient.RemoteConversation,
        prev: TaskResult?
    ): TaskResult {
        val downloaded = prev?.files.orEmpty()
            .mapNotNull { f -> f.localPath?.let { f.uri to it } }
            .toMap()
        val files = remote.artifacts.map { a ->
            downloaded[a.uri]?.let { a.copy(localPath = it) } ?: a
        }
        val points = buildList {
            add("任务已在云端完成")
            if (files.isNotEmpty()) add("已产出 ${files.size} 个文件，可在手表下载")
            else add("完整结果请在手机或电脑上查看")
        }
        return TaskResult(points = points, files = files)
    }

    private fun TaskStatus.isTerminal() =
        this == TaskStatus.DONE || this == TaskStatus.FAILED || this == TaskStatus.STOPPED

    private fun envOf(id: String): ExecEnv =
        _tasks.value.firstOrNull { it.id == id }?.env ?: ExecEnv.CLOUD

    private fun updateTask(id: String, transform: (Task) -> Task) {
        _tasks.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    /**
     * 合并云端列表与本地状态。
     *
     * 云端列表只给会话元信息，直接覆盖会把本地「已停止」翻回 RUNNING，
     * 也会丢掉已下载的产物路径，所以同 ID 时择优合并。
     */
    private fun merge(local: List<Task>, remote: List<Task>): List<Task> {
        val remoteIds = remote.map { it.id }.toSet()
        val localById = local.associateBy { it.id }
        val merged = remote.map { r ->
            val l = localById[r.id] ?: return@map r
            val keepStopped = (l.status == TaskStatus.STOPPED || stoppedIds.contains(r.id)) &&
                r.status == TaskStatus.WORKING
            r.copy(
                status = if (keepStopped) TaskStatus.STOPPED else r.status,
                result = r.result ?: l.result,
                unread = if (keepStopped) false else r.unread
            )
        }
        return merged + local.filter { it.id !in remoteIds }
    }

    private fun WorkBuddyCloudClient.RemoteConversation.toTask(env: ExecEnv): Task {
        // 拿不到状态时按「已完成」处理：宁可显示结束，也不要永远转圈
        val status = this.status.toTaskStatusOrNull() ?: TaskStatus.DONE
        return Task(
            id = id,
            title = title ?: "云端任务",
            status = status,
            env = env,
            doneSteps = 0,
            totalSteps = 0,
            error = if (status == TaskStatus.FAILED) "云端执行失败" else null,
            ask = if (status == TaskStatus.BLOCKED) ask else null,
            result = if (status == TaskStatus.DONE) cloudResult(this, null) else null
        )
    }

    /**
     * 云上状态原值映射为腕上状态；无法识别时返回 null，交由调用方保留现状。
     *
     * 网页端把 `sessionStatus` 与 `status` 视为同义，且会话生命周期另有
     * `active / completed / archived`，所以这里两类词都认。
     * `IDLE` 表示回合已结束（网页端由 CANCELLED 映射而来），归为完成。
     * 除 RUNNING / COMPLETED / CANCELLED / EXPIRED 外，云端在等用户补充信息时会给出
     * `WAITING_USER_INPUT` 一类状态，统一映射为 [TaskStatus.BLOCKED] 以便腕上弹出问题。
     */
    private fun String.toTaskStatusOrNull(): TaskStatus? = when (uppercase()) {
        "" -> null
        "RUNNING", "PENDING", "IN_PROGRESS", "PROCESSING", "WORKING", "BUSY", "GENERATING",
        "ACTIVE", "QUEUED", "STARTED" -> TaskStatus.WORKING
        "COMPLETED", "COMPLETE", "SUCCESS", "DONE", "FINISHED", "IDLE", "ARCHIVED" -> TaskStatus.DONE
        "WAITING_USER_INPUT", "WAITING_INPUT", "REQUIRES_ACTION", "PAUSED", "BLOCKED" -> TaskStatus.BLOCKED
        "CANCELLED", "CANCELED", "STOPPED", "ABORTED", "TERMINATED" -> TaskStatus.STOPPED
        "EXPIRED", "FAILED", "ERROR" -> TaskStatus.FAILED
        else -> null
    }

    private companion object {
        /** 首次轮询间隔，之后每次翻倍，最长 [MAX_POLL_INTERVAL]。 */
        const val INITIAL_POLL_INTERVAL = 2_000L
        const val MAX_POLL_INTERVAL = 30_000L

        /** 只在掉线时才探测的间隔。 */
        const val HEALTH_INTERVAL = 60_000L
    }
}
