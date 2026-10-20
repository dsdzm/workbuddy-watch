package com.workbuddy.watch.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

/**
 * WorkBuddy「云上模式」客户端。
 *
 * 端点与信封取自 web 端（workbuddy.cn/app）前端 bundle 的实现，并已实跑验证。
 *
 * 统一信封：`{code, msg, data}`，`code == 0`（或缺省）为成功。
 * 鉴权：console 接口需要网关会话 cookie，由原生登录 [NativeLogin] 收进
 * [CloudSettings.cookies]，请求时按路径手动带上 `Cookie` 头，
 * 并把响应里的 `Set-Cookie` 继续收回去（`session` 会滚动、过期时用
 * [NativeLogin.renew] 静默续期）。
 *
 * 链路：
 *  1. 下达任务   POST {base}/console/as/conversations/
 *                body `{prompt, model, plugins[]}` → `data.id` 即会话 ID
 *  2. 取接入点   GET  {base}/console/as/conversations/{id}/session
 *                → `{link, token, cwd, sandboxId, sessionStatus, expireAt}`
 *  3. 看进度     GET  {base}/console/as/conversations/{id}（轮询，不常驻 SSE）
 *  4. 下发/追问  ACP：`link` 形如 `https://<port>-<会话ID>.workbuddy.agentos-worker.net/acp`
 *                GET 该地址（Accept: text/event-stream）取响应头 `Acp-Connection-Id`，
 *                再 POST JSON-RPC 2.0 `session/prompt`；中断用 `session/cancel`（用 session.token 鉴权）。
 */
class WorkBuddyCloudClient(private val settings: CloudSettings) {

    data class Session(
        val conversationId: String,
        val link: String,
        val token: String,
        val cwd: String?
    )

    data class RemoteConversation(
        val id: String,
        val title: String?,
        /** 云上状态原值：RUNNING / COMPLETED / EXPIRED / CANCELLED / WAITING_USER_INPUT */
        val status: String,
        val session: Session? = null,
        /** 会话里出现过的产物文件（从返回体里解析 agent://files/… 引用）。 */
        val artifacts: List<Artifact> = emptyList(),
        /** 会话正处于等待用户补充信息时的问题；未能解析到具体问题时为通用提问。 */
        val ask: AskQuestion? = null
    )

    // ---------- 登录态 ----------

    /**
     * 探测登录态：以本机保存的会话 cookie 请求一次会话列表，能拿到合法信封即视为登录成功。
     */
    fun probe(): Boolean = runCatching {
        callEnvelope("GET", "/console/as/conversations/?type=cloud&page=1&size=1", null)
    }.getOrNull() != null

    // ---------- 会话 ----------

    /** 下达任务：创建会话并可直接下发首轮 prompt。 */
    fun createConversation(prompt: String, model: String = DEFAULT_MODEL): RemoteConversation {
        val plugins = JSONArray().put(
            JSONObject().put("name", "weixinpay").put("marketplace", "codebuddy-builtin")
        )
        val body = JSONObject()
            .put("prompt", prompt.take(TITLE_LIMIT))
            .put("model", model)
            .put("plugins", plugins)
        val data = callEnvelope("POST", "/console/as/conversations/", body)
            ?: throw IllegalStateException("下达任务失败：响应缺少 data")
        val id = data.optString("id").ifBlank { data.optString("conversationId") }
        if (id.isBlank()) throw IllegalStateException("下达任务失败：响应缺少会话 ID")
        return RemoteConversation(
            id = id,
            title = prompt.take(TITLE_LIMIT),
            status = "RUNNING",
            session = parseSession(id, data.optJSONObject("session"))
        )
    }

    /** 取会话接入点（长连接地址与用途令牌）。 */
    fun fetchSession(id: String): Session {
        val data = callEnvelope("GET", "/console/as/conversations/${enc(id)}/session", null)
            ?: throw IllegalStateException("获取会话接入点失败")
        return parseSession(id, data) ?: throw IllegalStateException("会话接入点缺少 link/token")
    }

    /** 查会话详情（轮询用）。 */
    fun getConversation(id: String): RemoteConversation? {
        val data = callEnvelope("GET", "/console/as/conversations/${enc(id)}", null) ?: return null
        return parseConversation(data, data.toString())
    }

    /** 会话列表（云上模式）。 */
    fun listConversations(size: Int = 30): List<RemoteConversation> {
        val sort = try {
            URLEncoder.encode("""{"orderBy":"updatedAt","order":"desc"}""", "UTF-8")
        } catch (e: Exception) {
            "%7B%22orderBy%22%3A%22updatedAt%22%2C%22order%22%3A%22desc%22%7D"
        }
        val origins = URLEncoder.encode(CONVERSATION_ORIGINS, "UTF-8")
        val path = "/console/as/conversations/?type=cloud&page=1&size=$size" +
            "&conversationOrigin=$origins&sort=$sort"
        val data = callEnvelope("GET", path, null) ?: return emptyList()
        val array = data.optJSONArray("conversations") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i -> parseConversation(array.optJSONObject(i)) }
    }

    /**
     * 可选模型列表：`GET /console/enterprises/{enterpriseId|personal}/models` → `{models[], agents[]}`。
     * 个人账号的 `enterpriseId` 就是 `personal`（与 web 端 `getTeamsModels` 的兜底一致）。
     */
    fun listModels(enterpriseId: String? = null): List<RemoteModel> {
        val ent = enterpriseId?.trim()?.takeIf { it.isNotBlank() } ?: CloudSettings.ENTERPRISE_PERSONAL
        val data = callEnvelope("GET", "/console/enterprises/${enc(ent)}/models", null) ?: return emptyList()
        val array = data.optJSONArray("models") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            if (o.optBoolean("disabled")) return@mapNotNull null
            val id = o.optString("id").ifBlank { o.optString("model") }
            if (id.isBlank()) return@mapNotNull null
            RemoteModel(
                id = id,
                name = o.optString("name").takeIf { it.isNotBlank() } ?: id,
                isDefault = o.optBoolean("isDefault"),
                description = o.optString("descriptionZh")
                    .takeIf { it.isNotBlank() } ?: o.optString("description").takeIf { it.isNotBlank() }
            )
        }
    }

    /**
     * 拉取某个会话的完整历史消息（历史对话详情页用）。
     *
     * 官方没有 REST 历史接口：网页端打开会话时走 ACP `session/load`，由云端把这段会话的
     * 全部消息**回放到同一条 SSE 流**上。这里照做：先建 SSE 流拿 `Acp-Connection-Id`，
     * 再发 `session/load`，然后在同一流上按 `session/update` 收集 user/assistant 文本分片。
     *
     * @param timeoutMs 最长等待时间；手表侧不宜久等，默认 [HISTORY_TIMEOUT]。
     */
    fun loadHistory(session: Session, timeoutMs: Long = HISTORY_TIMEOUT): List<ChatMessage> {
        val url = normalizeLink(session.link)
        val deadline = System.currentTimeMillis() + timeoutMs
        val stream = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT
            // 单次读取超时略大于总预算，真正的收尾由看门狗在 deadline 断开连接完成
            readTimeout = (timeoutMs + 2000L).toInt()
            setRequestProperty("Authorization", "Bearer ${session.token}")
            setRequestProperty("User-Agent", settings.userAgent)
            setRequestProperty("Accept", "text/event-stream")
        }
        return try {
            stream.responseCode // 触发请求，拿到响应头
            val connectionId = stream.getHeaderField(ACP_CONNECTION_HEADER)
                ?: throw IllegalStateException("历史加载失败：缺少 $ACP_CONNECTION_HEADER")
            val acpSessionToken = stream.getHeaderField(ACP_SESSION_TOKEN_HEADER)?.trim().orEmpty()

            // POST 与读流必须并行；放到单独线程，避免阻塞 SSE 读取
            val params = JSONObject()
                .put("sessionId", session.conversationId)
                .put("cwd", session.cwd.orEmpty())
                .put("mcpServers", JSONArray())
            val loader = Thread {
                runCatching {
                    postRpc(
                        url = url,
                        token = session.token,
                        connectionId = connectionId,
                        acpSessionToken = acpSessionToken,
                        method = ACP_LOAD,
                        params = params,
                        readTimeout = HISTORY_TIMEOUT.toInt()
                    )
                }
            }.apply { isDaemon = true; start() }

            // 看门狗：到点直接断开，避免读流在回放结束后长时间干等
            val watchdog = Thread {
                while (System.currentTimeMillis() < deadline) Thread.sleep(200)
                runCatching { stream.disconnect() }
            }.apply { isDaemon = true; start() }

            val messages = mutableListOf<ChatMessage>()
            var builder: StringBuilder? = null
            var builderRole: ChatRole? = null
            val reader = BufferedReader(InputStreamReader(stream.inputStream, Charsets.UTF_8))
            while (messages.size < HISTORY_MAX_MESSAGES) {
                val line = runCatching { reader.readLine() }.getOrNull() ?: break
                if (!line.startsWith("data:")) continue
                val update = updateOf(line.removePrefix("data:").trim()) ?: continue
                val role = roleOf(update.optString("sessionUpdate")) ?: continue
                val text = textOf(update.opt("content"))
                if (text.isBlank()) continue
                if (builderRole == role && builder != null) {
                    builder.append(text)
                } else {
                    builder?.toString()?.takeIf { it.isNotBlank() }?.let {
                        messages += ChatMessage(builderRole!!, it.take(MESSAGE_LIMIT))
                    }
                    builder = StringBuilder(text)
                    builderRole = role
                }
            }
            builder?.toString()?.takeIf { it.isNotBlank() }?.let {
                messages += ChatMessage(builderRole!!, it.take(MESSAGE_LIMIT))
            }
            runCatching { watchdog.interrupt() }
            runCatching { loader.interrupt() }
            messages
        } finally {
            runCatching { stream.disconnect() }
        }
    }

    // ---------- ACP：下发 / 追问 / 中断 ----------

    /**
     * 下发或追问。[attachments] 会作为 ACP `resource_link` 内容块一并发送，
     * 让云端沙箱能直接读取这些已上传的文件。
     */
    fun sendPrompt(session: Session, text: String, attachments: List<Artifact> = emptyList()) {
        val blocks = JSONArray()
        if (text.isNotBlank()) blocks.put(JSONObject().put("type", "text").put("text", text))
        attachments.forEach { a ->
            blocks.put(
                JSONObject()
                    .put("type", "resource_link")
                    .put("name", a.name)
                    .put("title", a.name)
                    .put("uri", a.uri)
                    .apply { a.mimeType?.let { put("mimeType", it) } }
            )
        }
        val params = JSONObject()
            .put("sessionId", session.conversationId)
            .put("prompt", blocks)
        acpCall(session, ACP_PROMPT, params)
    }

    /** 中断当前执行（非退出排队）。 */
    fun cancelTurn(session: Session) {
        acpCall(session, ACP_CANCEL, JSONObject().put("sessionId", session.conversationId))
    }

    /**
     * 回答 askuser 问题：走 ACP 扩展方法 `_codebuddy.ai/resolveInterruption`。
     * 仅当拿到了中断 ID 时可用，否则上层退化为追问。
     */
    fun resolveInterruption(session: Session, question: AskQuestion, answer: String) {
        val requestId = question.requestId ?: return
        val params = JSONObject()
            .put("sessionId", session.conversationId)
            .put("requestId", requestId)
            .put("toolCallId", requestId)
            .put("optionId", answer)
            .put("answer", answer)
        acpCall(session, ACP_RESOLVE_INTERRUPTION, params)
    }

    // ---------- 产物上传 / 下载 ----------

    /**
     * 上传文件到云端存储：先取预签名地址，再 PUT 原始字节。
     * 返回可直接在 prompt 里引用的 [Artifact]。
     */
    fun uploadFile(name: String, mimeType: String, bytes: ByteArray): Artifact {
        val item = presign(listOf(name)).firstOrNull()
            ?: throw IllegalStateException("上传失败：未取得预签名地址")
        val uploadUrl = item.optString("upload_url").ifBlank { item.optString("url") }
        if (uploadUrl.isBlank()) throw IllegalStateException("上传失败：响应缺少 upload_url")
        val conn = (URL(uploadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = TRANSFER_TIMEOUT
            doOutput = true
            setRequestProperty("Content-Type", mimeType)
        }
        try {
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("上传失败：HTTP $code")
        } finally {
            conn.disconnect()
        }
        return Artifact(name = name, uri = "agent://files/${objectKeyOf(name)}", mimeType = mimeType)
    }

    /** 下载产物文件字节（换取预签名下载地址后 GET）。 */
    fun downloadArtifact(artifact: Artifact): ByteArray {
        val key = objectKeyOf(artifact.uri)
        val item = presign(listOf(key)).firstOrNull()
            ?: throw IllegalStateException("下载失败：未取得预签名地址")
        val url = item.optString("download_url")
        if (url.isBlank()) throw IllegalStateException("下载失败：响应缺少 download_url")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = TRANSFER_TIMEOUT
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("下载失败：HTTP $code")
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    /** 取预签名条目（COS 上传/下载地址）。 */
    private fun presign(paths: List<String>): List<JSONObject> {
        val body = JSONObject().put("object_keys", JSONArray(paths))
        val data = callEnvelope("POST", "/console/as/support/presigned_url", body)
            ?: throw IllegalStateException("预签名请求失败：响应缺少 data")
        val items = data.optJSONArray("items") ?: data.optJSONObject("data")?.optJSONArray("items")
        return items?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) } }.orEmpty()
    }

    // ---------- 内部：HTTP ----------

    /** 发送请求并解开 `{code,msg,data}` 信封，返回 `data`（缺省时返回整个响应）。 */
    private fun callEnvelope(method: String, path: String, body: JSONObject?): JSONObject? {
        val raw = request(method, base() + path, body?.toString(), json = true)
        val root = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val code = root.opt("code")
        if (code is Int && code != 0) {
            throw IllegalStateException(root.optString("msg").ifBlank { "云端返回 code=$code" })
        }
        return root.optJSONObject("data") ?: root
    }

    private fun request(method: String, url: String, body: String?, json: Boolean): String? {
        var result = execute(method, url, body, json)
        var attempt = 0
        // 登录态失效有两种表现：302 被重定向回登录页，或直接 401。
        // 带上仍然有效的 Keycloak SSO cookie 静默续期后重试（浏览器也是这么自愈的）。
        while ((result.code == HTTP_UNAUTHORIZED || result.code in 300..399) &&
            attempt < MAX_AUTH_ATTEMPTS && renewSession()
        ) {
            result = execute(method, url, body, json)
            attempt++
        }
        if (result.code !in 200..299) {
            val msg = result.text?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?.optString("msg")?.takeIf { it.isNotBlank() }
            // 401 / 被重定向回登录页：网关已不认这份登录态，且静默续期也没能救回来
            if (result.code == HTTP_UNAUTHORIZED || result.code in 300..399) {
                throw SessionExpiredException(msg ?: "登录已过期")
            }
            throw IllegalStateException(msg ?: "HTTP ${result.code}")
        }
        return result.text
    }

    /** 单次 HTTP。响应里的 `Set-Cookie` 一律交给 [CloudSettings.cookies] 滚动保存。 */
    private fun execute(method: String, url: String, body: String?, json: Boolean): HttpResult {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            // 手动处理重定向：自动跟随会丢掉中间响应里的 Set-Cookie（会话就是在其中签发的）
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            // 会话凭据：原生登录收集的 httpOnly cookie，按请求路径下发
            settings.cookies.header(url)?.let { setRequestProperty("Cookie", it) }
            // 网关会话与签发时的 User-Agent 绑定，这里必须和登录时用同一个（见 CloudSettings.userAgent）
            setRequestProperty("User-Agent", settings.userAgent)
            setRequestProperty("Referer", base() + "/app")
            setRequestProperty("Accept", if (json) "application/json, text/plain, */*" else "*/*")
            setRequestProperty(REQUEST_ID_HEADER, nextRequestId())
            setRequestProperty("X-Submitted-From", SUBMITTED_FROM)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            conn.headerFields?.let { settings.cookies.absorb(it, url) }
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let { s ->
                BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { it.readText() }
            }
            return HttpResult(code, text)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 静默续期：带上仍然有效的 Keycloak SSO cookie 重走一次 OIDC 换新的网关 `session`。
     * 由 [request] 在收到 401 / 被重定向回登录页时调用；失败后长时间退避（见 [renewSession] 实现）。
     */
    private fun renewSession(): Boolean {
        val now = System.currentTimeMillis()
        synchronized(renewLock) {
            if (now - lastRenewAt < RENEW_THROTTLE_MS) return false
            // 续期会整体换掉网关会话，失败后长时间退避：既没必要反复尝试，也避免干扰其它设备的会话
            if (now - lastRenewFailedAt < RENEW_BACKOFF_MS) return false
            lastRenewAt = now
        }
        val ok = runCatching { NativeLogin(settings).renew() }.getOrDefault(false)
        if (!ok) synchronized(renewLock) { lastRenewFailedAt = System.currentTimeMillis() }
        return ok
    }

    private class HttpResult(val code: Int, val text: String?)

    /** 上次静默续期时间，用于节流（见 [renewSession]）。 */
    private var lastRenewAt = 0L

    /** 上次续期失败时间，用于退避（见 [renewSession]）。 */
    private var lastRenewFailedAt = 0L

    // ---------- 内部：ACP ----------

    /**
     * ACP 调用。协议要求先建流拿到 `Acp-Connection-Id`（可能还带 `acp-session-token`），
     * 再用同一 ID 发起 JSON-RPC POST；因此这里保持 GET 连接打开直到 POST 完成。
     */
    private fun acpCall(session: Session, method: String, params: JSONObject) {
        val url = normalizeLink(session.link)
        val stream = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = HANDSHAKE_READ_TIMEOUT
            setRequestProperty("Authorization", "Bearer ${session.token}")
            setRequestProperty("User-Agent", settings.userAgent)
            setRequestProperty("Accept", "text/event-stream")
        }
        try {
            stream.responseCode // 触发请求
            val connectionId = stream.getHeaderField(ACP_CONNECTION_HEADER)
                ?: throw IllegalStateException("ACP 握手失败：缺少 $ACP_CONNECTION_HEADER")
            val acpSessionToken = stream.getHeaderField(ACP_SESSION_TOKEN_HEADER)?.trim().orEmpty()
            postRpc(url, session.token, connectionId, acpSessionToken, method, params)
        } finally {
            runCatching { stream.disconnect() }
        }
    }

    /**
     * 在已建好的 ACP 连接上发一次 JSON-RPC 请求。
     * [loadHistory] 会在读 SSE 的同时从另一线程调用它，因此这里不负责连接的建立与关闭。
     */
    private fun postRpc(
        url: String,
        token: String,
        connectionId: String,
        acpSessionToken: String,
        method: String,
        params: JSONObject,
        readTimeout: Int = READ_TIMEOUT
    ) {
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", requestSeq.incrementAndGet())
            .put("method", method)
            .put("params", params)

        val post = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT
            this.readTimeout = readTimeout
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("User-Agent", settings.userAgent)
            if (acpSessionToken.isNotBlank()) setRequestProperty(ACP_SESSION_TOKEN_HEADER, acpSessionToken)
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json, text/event-stream")
            setRequestProperty(ACP_CONNECTION_HEADER, connectionId)
        }
        try {
            post.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = post.responseCode
            if (code !in 200..299) throw IllegalStateException("ACP $method 失败：HTTP $code")
            val text = post.inputStream?.let { s ->
                BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { it.readText() }
            }
            text?.takeIf { it.isNotBlank() }?.let { body ->
                runCatching { JSONObject(body) }.getOrNull()
                    ?.optJSONObject("error")
                    ?.let { err -> throw IllegalStateException(err.optString("message").ifBlank { "ACP $method 失败" }) }
            }
        } finally {
            post.disconnect()
        }
    }

    // ---------- 内部：解析 ----------

    private fun parseSession(id: String, o: JSONObject?): Session? {
        if (o == null) return null
        val link = o.optString("link").ifBlank { o.optString("url") }
        val token = o.optString("token")
        if (link.isBlank() || token.isBlank()) return null
        return Session(
            conversationId = id,
            link = link,
            token = token,
            cwd = o.optString("cwd").takeIf { it.isNotBlank() }
        )
    }

    private fun parseConversation(o: JSONObject?, raw: String = o?.toString().orEmpty()): RemoteConversation? {
        if (o == null) return null
        val id = o.optString("id").ifBlank { o.optString("conversationId") }
        if (id.isBlank()) return null
        val status = (o.optString("status").ifBlank { o.optString("sessionStatus") }).uppercase()
            .ifBlank { "RUNNING" }
        return RemoteConversation(
            id = id,
            title = o.optString("name").takeIf { it.isNotBlank() }
                ?: o.optString("title").takeIf { it.isNotBlank() },
            status = status,
            session = parseSession(id, o.optJSONObject("session")),
            artifacts = artifactsOf(raw),
            ask = askOf(o, status)
        )
    }

    /** 从会话返回体里捞出产物引用（形如 `agent://files/workspace/报告.md`）。 */
    private fun artifactsOf(raw: String): List<Artifact> =
        ARTIFACT_URI.findAll(raw)
            .map { it.value }
            .distinct()
            .map { uri -> Artifact(name = uri.substringAfterLast('/').ifBlank { "产物文件" }, uri = uri) }
            .toList()

    /**
     * 解析 askuser 问题。优先取返回体中 `/questions` 结构；取不到但状态表明在等用户输入时，
     * 退化为一条通用提问（手表可自由作答，云端按追问处理）。
     */
    private fun askOf(o: JSONObject, status: String): AskQuestion? {
        val first = findQuestions(o)?.optJSONObject(0)
        if (first != null) {
            val question = first.optString("question").ifBlank { first.optString("title") }
            if (question.isNotBlank()) {
                val options = first.optJSONArray("options")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        val opt = arr.optJSONObject(i) ?: return@mapNotNull null
                        val label = opt.optString("label").ifBlank { opt.optString("value") }
                        if (label.isBlank()) null
                        else AskOption(label, opt.optString("description").takeIf { it.isNotBlank() })
                    }
                }.orEmpty()
                return AskQuestion(
                    question = question,
                    header = first.optString("header").takeIf { it.isNotBlank() },
                    options = options,
                    requestId = findInterruptionId(o)
                )
            }
        }
        return if (status in WAITING_STATES) AskQuestion("云端需要你补充信息后继续") else null
    }

    /** 递归查找 `questions` 数组（AskUserQuestion 的入参结构）。 */
    private fun findQuestions(node: Any?): JSONArray? = when (node) {
        is JSONObject -> node.optJSONArray("questions")?.takeIf { it.length() > 0 }
            ?: node.keys().asSequence().mapNotNull { findQuestions(node.opt(it)) }.firstOrNull()
        is JSONArray -> (0 until node.length()).asSequence().mapNotNull { findQuestions(node.opt(it)) }.firstOrNull()
        else -> null
    }

    /** 递归查找 pending permission 的中断 ID（用于精确回传答案）。 */
    private fun findInterruptionId(node: Any?): String? = when (node) {
        is JSONObject -> {
            val direct = node.optString("interruptionId").ifBlank { node.optString("requestId") }
            if (direct.isNotBlank()) direct
            else node.keys().asSequence().mapNotNull { findInterruptionId(node.opt(it)) }.firstOrNull()
        }
        is JSONArray -> (0 until node.length()).asSequence().mapNotNull { findInterruptionId(node.opt(it)) }.firstOrNull()
        else -> null
    }

    /** 从 SSE 的 `data:` 负载里取出 `session/update` 的 `update` 对象。 */
    private fun updateOf(data: String): JSONObject? = runCatching {
        val root = JSONObject(data)
        if (root.optString("method") != "session/update") return null
        root.optJSONObject("params")?.optJSONObject("update")
    }.getOrNull()

    /** ACP update 类型 → 消息角色。思考分片（agent_thought_chunk）不展示，腕上噪音太大。 */
    private fun roleOf(sessionUpdate: String): ChatRole? = when (sessionUpdate) {
        "user_message_chunk" -> ChatRole.USER
        "agent_message_chunk" -> ChatRole.ASSISTANT
        else -> null
    }

    /** 取内容块文本，兼容 `{type,text}`、`[{type,text}…]` 与纯字符串三种形态。 */
    private fun textOf(content: Any?): String = when (content) {
        is String -> content
        is JSONObject -> content.optString("text").takeIf { it.isNotBlank() }
            ?: textOf(content.opt("content"))
        is JSONArray -> (0 until content.length()).joinToString("") { textOf(content.opt(it)) }
        else -> ""
    }

    /** 去掉 `agent://files/`、`agent:///` 等前缀，得到对象存储里的 key。 */
    private fun objectKeyOf(uri: String): String = when {
        uri.startsWith("agent://files/") -> uri.removePrefix("agent://files/")
        uri.startsWith("agent:///") -> uri.removePrefix("agent:///")
        uri.startsWith("agent://") -> uri.removePrefix("agent://")
        else -> uri
    }

    private fun base() = settings.baseUrl.trimEnd('/').ifBlank { CloudSettings.DEFAULT_BASE_URL }

    private fun normalizeLink(link: String) = link.replace(Regex("^http://"), "https://")

    private fun enc(raw: String) = URLEncoder.encode(raw, "UTF-8")

    private fun nextRequestId() = "wb-watch-${requestSeq.incrementAndGet()}"

    companion object {
        /** 新建会话时的默认模型（web 端同款默认值）。 */
        const val DEFAULT_MODEL = "deepseek-r1"

        private const val TITLE_LIMIT = 100
        private const val CONNECT_TIMEOUT = 8_000
        private const val READ_TIMEOUT = 15_000
        /** 上传 / 下载产物文件时允许更长的读写时间。 */
        private const val TRANSFER_TIMEOUT = 60_000
        private const val HANDSHAKE_READ_TIMEOUT = 20_000

        /** 网关判定未登录；此时尝试用 Keycloak SSO cookie 静默续期。 */
        private const val HTTP_UNAUTHORIZED = 401
        /** 一次请求内最多续期重试的次数，避免把会话顶来顶去。 */
        private const val MAX_AUTH_ATTEMPTS = 2
        /** 续期节流：同一时间窗口内只续期一次，避免并发把彼此会话顶掉。 */
        private const val RENEW_THROTTLE_MS = 5_000L
        /** 续期失败后的退避时间：静默续期救不回来时，直接让用户重新登录，别反复折腾会话。 */
        private const val RENEW_BACKOFF_MS = 60_000L
        private val renewLock = Any()

        private const val ACP_CONNECTION_HEADER = "Acp-Connection-Id"
        private const val ACP_SESSION_TOKEN_HEADER = "acp-session-token"
        private const val ACP_PROMPT = "session/prompt"
        private const val ACP_CANCEL = "session/cancel"
        /** ACP 扩展方法：把整段会话历史回放到同一条 SSE 流上（网页端打开会话时也走它）。 */
        private const val ACP_LOAD = "session/load"

        /** 单次历史加载的最长等待时间；手表侧不宜久等，到点由看门狗断开读流。 */
        private const val HISTORY_TIMEOUT = 12_000L
        /** 单次历史加载最多收集的消息条数，防止超长会话把内存撑爆。 */
        private const val HISTORY_MAX_MESSAGES = 200
        /** 单条消息最多保留的字符数。 */
        private const val MESSAGE_LIMIT = 4000
        /** ACP 扩展方法：回传 askuser / 权限中断的选择。 */
        private const val ACP_RESOLVE_INTERRUPTION = "_codebuddy.ai/resolveInterruption"
        private const val REQUEST_ID_HEADER = "X-Request-ID"

        /** 会话返回体里产物引用的形态，如 `agent://files/workspace/报告.md`。 */
        private val ARTIFACT_URI = Regex("""agent://files/[^\s"'\\()\[\]{}]+""")

        /** 云上表示「在等用户补充信息」的状态原值。 */
        private val WAITING_STATES = setOf(
            "WAITING_USER_INPUT", "WAITING_INPUT", "REQUIRES_ACTION", "PAUSED", "BLOCKED"
        )
        /** 与 web 端保持一致，避免服务端按来源校验时被拒。 */
        private const val SUBMITTED_FROM = "web"
        private const val CONVERSATION_ORIGINS =
            "agents,workbuddy-mp,workbuddy-app,openapi,workbuddy-deploy"

        private val requestSeq = AtomicInteger(0)
    }
}

/**
 * 网关已经不认当前登录态（401 或把请求重定向回登录页），且静默续期也没能恢复。
 * 界面据此提示用户重新登录，而不是甩一个 `HTTP 401`。
 */
class SessionExpiredException(message: String) : RuntimeException(message)
