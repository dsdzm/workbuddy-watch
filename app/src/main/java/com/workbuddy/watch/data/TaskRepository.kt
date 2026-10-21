package com.workbuddy.watch.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** 没有模型错误的数据源共用的空流。 */
private val NEVER_ERROR: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()

/**
 * 任务数据源。手表可脱离手机，独立对接 WorkBuddy 云端（见 [CloudTaskRepository]）。
 * 未配置主端时回退到 [FakeTaskRepository] 以便演示与离线自检。
 */
interface TaskRepository {
    val tasks: StateFlow<List<Task>>
    val connected: StateFlow<Boolean>

    /** 云端最近一次失败原因，供界面提示「未连通」的具体缘由；null 表示正常。 */
    val cloudError: StateFlow<String?>

    /**
     * 云端已判定登录态失效（401 且静默续期也没能恢复）。
     * 界面据此回到「可重新登录」的状态，而不是停在一条永远发不出去任务的 401 上。
     */
    val sessionExpired: StateFlow<Boolean>

    /** 模型设置失败原因（例如账号没开通所选模型）；null 表示正常。 */
    val modelError: StateFlow<String?> get() = NEVER_ERROR

    /** 提示过一次后清空，避免重复弹同一条。 */
    fun clearModelError() {}

    /** 下达任务；[attachments] 为已上传到云端的附件引用（云端会随 prompt 一并下发）。 */
    suspend fun dispatch(prompt: String, env: ExecEnv, attachments: List<Artifact> = emptyList()): String

    suspend fun reply(id: String, prompt: String)
    fun stop(id: String)
    suspend fun retry(id: String)

    /** 回答云端抛出的 askuser 问题，让任务继续执行。 */
    suspend fun answerAsk(id: String, answer: String)

    /** 把本地文件上传到云端存储，返回可随 prompt 引用的附件。 */
    suspend fun uploadAttachment(name: String, mimeType: String, bytes: ByteArray): Artifact

    /** 拉取产物文件的字节内容，由上层负责落盘。 */
    suspend fun downloadArtifact(artifact: Artifact): ByteArray

    /** 拉取某会话的完整历史消息（历史对话详情页用）。 */
    suspend fun loadHistory(id: String): List<ChatMessage> = emptyList()

    /** 云端可选模型列表（模型选择设置页用）。 */
    suspend fun listModels(): List<RemoteModel> = emptyList()

    /** 云端会话当前实际使用的模型名；null 表示取不到。 */
    suspend fun remoteModel(id: String): String? = null

    /** 用户刚切了模型，立刻同步到云端会话（对话页切完就能确认是否生效）。 */
    fun applyModelNow(id: String) {}

    /** 记录某产物已下载到本机的路径，供界面展示「已下载 / 打开」。 */
    fun markDownloaded(taskId: String, uri: String, localPath: String)

    fun markRead(id: String)

    /** 仅演示实现需要：模拟与主端断开 / 恢复。云端实现忽略此调用。 */
    fun setSimulatedOffline(value: Boolean) {}

    /**
     * 界面是否在前台（手表抬手亮屏）。
     *
     * 息屏时轮询完全挂起、不发任何请求；重新亮屏立即补拉一次，
     * 用来把「省流量」做到不牺牲进度可见性。
     */
    fun setForeground(foreground: Boolean) {}
}

/** 本地演示实现（无主端时使用）。 */
class FakeTaskRepository : TaskRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    private val _tasks = MutableStateFlow(seedTasks())
    override val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()

    private val _connected = MutableStateFlow(true)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** 演示实现不会失败，恒为 null。 */
    override val cloudError: StateFlow<String?> = MutableStateFlow(null)

    /** 演示实现不存在登录态，恒为 false。 */
    override val sessionExpired: StateFlow<Boolean> = MutableStateFlow(false)

    override fun setSimulatedOffline(value: Boolean) { _connected.value = value }

    override suspend fun dispatch(prompt: String, env: ExecEnv, attachments: List<Artifact>): String {
        val task = Task(
            id = UUID.randomUUID().toString(),
            title = prompt,
            status = TaskStatus.WORKING,
            env = env,
            doneSteps = 0,
            totalSteps = 3
        )
        _tasks.update { listOf(task) + it }
        launchProgress(task.id, attachments.size)
        return task.id
    }

    override suspend fun reply(id: String, prompt: String) {
        updateTask(id) { it.copy(status = TaskStatus.WORKING, doneSteps = 0, ask = null) }
        launchProgress(id, 0)
    }

    override suspend fun answerAsk(id: String, answer: String) {
        // 演示：回答后继续执行，把答案并入结果摘要
        updateTask(id) {
            val points = it.result?.points.orEmpty()
            it.copy(
                status = TaskStatus.WORKING,
                doneSteps = 0,
                ask = null,
                unread = false,
                result = it.result?.copy(points = points + "已按你的选择继续：$answer")
            )
        }
        launchProgress(id, 0)
    }

    /** 演示：附件直接落到一个假的对象存储地址，不真正上传。 */
    override suspend fun uploadAttachment(name: String, mimeType: String, bytes: ByteArray): Artifact {
        delay(600)
        return Artifact(name = name, uri = "agent://files/$name", mimeType = mimeType)
    }

    /** 演示：返回一段占位内容，真实实现由 [CloudTaskRepository] 走预签名下载。 */
    override suspend fun downloadArtifact(artifact: Artifact): ByteArray {
        delay(600)
        return "# ${artifact.name}\n\n（演示产物，由本地演示仓库生成）\n".toByteArray()
    }

    /** 演示：给出一段本地历史对话，便于离线体验详情页。 */
    override suspend fun loadHistory(id: String): List<ChatMessage> {
        delay(400)
        val title = _tasks.value.firstOrNull { it.id == id }?.title ?: "演示会话"
        return listOf(
            ChatMessage(ChatRole.USER, title),
            ChatMessage(
                ChatRole.ASSISTANT,
                "好的，我来处理这件事。\n\n这是本地演示数据：登录云端后，这里会显示你在网页端真实的完整对话记录。"
            ),
            ChatMessage(ChatRole.USER, "再详细一点"),
            ChatMessage(ChatRole.ASSISTANT, "已完成第一步与第二步，正在整理最终结果。")
        )
    }

    /** 演示：本地模型清单。 */
    override suspend fun listModels(): List<RemoteModel> {
        delay(300)
        return listOf(
            RemoteModel("deepseek-r1", "DeepSeek R1", isDefault = true, description = "推理能力强，适合复杂任务"),
            RemoteModel("deepseek-v3", "DeepSeek V3", description = "通用对话，响应更快"),
            RemoteModel("gpt-4o", "GPT-4o", description = "多模态，理解力强")
        )
    }

    override fun stop(id: String) {
        jobs.remove(id)?.cancel()
        updateTask(id) { it.copy(status = TaskStatus.STOPPED, ask = null) }
    }

    override suspend fun retry(id: String) {
        updateTask(id) { it.copy(status = TaskStatus.WORKING, doneSteps = 0, error = null, ask = null) }
        launchProgress(id, 0)
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

    private fun launchProgress(id: String, attachmentCount: Int) {
        jobs.remove(id)?.cancel()
        jobs[id] = scope.launch {
            delay(500)
            while (true) {
                val t = _tasks.value.firstOrNull { it.id == id } ?: return@launch
                if (t.status != TaskStatus.WORKING) return@launch

                // 演示 askuser：执行到一半时抛一个问题，等待用户选择
                if (t.totalSteps > 2 && t.doneSteps == 1 && t.ask == null) {
                    updateTask(id) {
                        it.copy(
                            status = TaskStatus.BLOCKED,
                            unread = true,
                            ask = AskQuestion(
                                question = "结果需要哪种呈现方式？",
                                header = "呈现方式",
                                options = listOf(
                                    AskOption("Markdown 文档", "结构清晰，便于继续编辑"),
                                    AskOption("PDF 报告", "适合直接分享"),
                                    AskOption("演示大纲", "用于汇报")
                                )
                            )
                        )
                    }
                    return@launch
                }

                if (t.doneSteps >= t.totalSteps) {
                    updateTask(id) {
                        val points = buildList {
                            add("已在${envName(it.env)}完成执行")
                            if (attachmentCount > 0) add("已接收 $attachmentCount 个附件")
                            add("可下载产物文件")
                        }
                        it.copy(
                            status = TaskStatus.DONE,
                            doneSteps = it.totalSteps,
                            unread = true,
                            ask = null,
                            result = TaskResult(points = points, files = demoArtifacts())
                        )
                    }
                    return@launch
                }
                delay(1100)
                updateTask(id) { it.copy(doneSteps = (it.doneSteps + 1).coerceAtMost(it.totalSteps)) }
            }
        }
    }

    private fun demoArtifacts() = listOf(
        Artifact(name = "执行结果.md", uri = "agent://files/workspace/执行结果.md", mimeType = "text/markdown")
    )

    private fun updateTask(id: String, transform: (Task) -> Task) {
        _tasks.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    private fun envName(env: ExecEnv) = if (env == ExecEnv.CLOUD) "云端工作" else "连接电脑"

    private fun seedTasks() = listOf(
        Task("t1", "把本周销售数据整理成周报", TaskStatus.DONE, ExecEnv.CLOUD, 3, 3,
            result = TaskResult(
                points = listOf("本周 GMV 128 万，环比 +6%", "华东区贡献 42%，为增长主力", "TOP3 品类：生鲜、乳品、零食"),
                files = listOf(Artifact("销售周报.md", "agent://files/workspace/销售周报.md", "text/markdown"))
            ),
            unread = true),
        Task("t2", "调研三家竞品的定价策略", TaskStatus.BLOCKED, ExecEnv.CLOUD, 1, 4,
            ask = AskQuestion(
                question = "需要聚焦哪个价位段做深入对比？",
                header = "价位段",
                options = listOf(
                    AskOption("低价位", "9.9 元以下"),
                    AskOption("中价位", "10–49 元"),
                    AskOption("全价位", "不做限定")
                )
            )),
        Task("t3", "生成 Q3 复盘演示大纲", TaskStatus.DONE, ExecEnv.PC, 2, 2,
            result = TaskResult(
                points = listOf("目标达成率 92%", "三条主要偏差及原因", "下季度三个重点方向"),
                files = listOf(Artifact("Q3复盘大纲.docx", "agent://files/workspace/Q3复盘大纲.docx"))
            )),
        Task("t4", "把会议录音整理成纪要", TaskStatus.FAILED, ExecEnv.CLOUD, 0, 0,
            error = "执行失败：未取得音频文件")
    )
}
