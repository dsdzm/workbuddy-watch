package com.workbuddy.watch.data

/** 任务在腕上的状态（由主端 job.state 派生）。 */
enum class TaskStatus { WORKING, BLOCKED, DONE, FAILED, STOPPED }

/** 执行环境：云端沙箱 / 远程连接电脑。 */
enum class ExecEnv { CLOUD, PC }

/**
 * 云端产物文件引用。
 *
 * [uri] 为云端路径（形如 `agent://files/workspace/报告.md`），下载时经
 * `POST /console/as/support/presigned_url` 换预签名链接；下载完成后写回 [localPath]。
 */
data class Artifact(
    val name: String,
    val uri: String,
    val mimeType: String? = null,
    val localPath: String? = null
) {
    val downloaded: Boolean get() = localPath != null
}

/** askuser 的一个可选项。 */
data class AskOption(val label: String, val description: String? = null)

/**
 * 云端执行中抛给手表的问题（对应 ACP 的 AskUserQuestion / request_permission）。
 *
 * [requestId] 为 ACP 中断 ID，存在时可精确回传答案；缺省则退化为以追问方式继续。
 */
data class AskQuestion(
    val question: String,
    val header: String? = null,
    val options: List<AskOption> = emptyList(),
    val requestId: String? = null
)

data class TaskResult(
    val points: List<String>,
    val files: List<Artifact> = emptyList()
)

data class Task(
    val id: String,
    val title: String,
    val status: TaskStatus,
    val env: ExecEnv,
    val doneSteps: Int,
    val totalSteps: Int,
    val result: TaskResult? = null,
    val error: String? = null,
    val unread: Boolean = false,
    /** 云端等待用户补充信息时的问题；非空即表示该任务被 askuser 阻塞。 */
    val ask: AskQuestion? = null
)

/** 会话里的一条消息，用于「历史对话」详情页（与网页端一致的完整对话）。 */
enum class ChatRole { USER, ASSISTANT }

data class ChatMessage(
    val role: ChatRole,
    val text: String
)

/** 云端可选模型（`GET /console/enterprises/{id}/models` 里的 `models[]`）。 */
data class RemoteModel(
    val id: String,
    val name: String,
    val isDefault: Boolean = false,
    val description: String? = null
)
