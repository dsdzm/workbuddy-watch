package com.workbuddy.watch.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Calendar
import java.util.Date
import java.util.Locale
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

    /**
     * 刚上传文件的原始字节，供随后的 prompt 内联图片用。
     *
     * 只留最近 [MAX_CACHED_UPLOADS] 个：手表内存有限，且内联只发生在「选完文件马上发送」这一回合。
     */
    private val uploadCache = object : LinkedHashMap<String, ByteArray>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean =
            size > MAX_CACHED_UPLOADS
    }

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
        /** 云端会话当前实际使用的模型名，用来确认切换是否真的生效。 */
        val model: String? = null,
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
            // 云端要的是 `model`（真正的模型标识，如 deepseek-v4.1-flash / hy4-preview）；
            // `id` 只是目录项 ID，直接当模型发会 400 `service info not found`
            val modelId = o.optString("model").ifBlank { o.optString("id") }
            if (modelId.isBlank()) return@mapNotNull null
            RemoteModel(
                id = modelId,
                name = o.optString("name").ifBlank { o.optString("displayName") }
                    .takeIf { it.isNotBlank() } ?: modelId,
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

    /**
     * 取某个会话**真正可用**的模型清单（ACP 侧返回的 `availableModels`）。
     *
     * console 的 `/models` 是模型目录，会把账号没有开通的模型也列出来（例如
     * `hunyuan-2.0-thinking`），拿它去 `session/set_model` 会被云端回
     * `400 model [...] service info not found`。网页端的做法是取这两者的交集：
     * `models.filter(m => availableModels.includes(m.id))`，这里照做。
     *
     * 数据来源是 `session/load` 的 JSON-RPC 响应：
     * `result.models._meta["codebuddy.ai"].availableModels`（也兼容 `result.models.availableModels`）。
     */
    fun fetchAvailableModels(session: Session, timeoutMs: Long = MODELS_TIMEOUT): Set<String> {
        val url = normalizeLink(session.link)
        val stream = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = (timeoutMs + 2000L).toInt()
            setRequestProperty("Authorization", "Bearer ${session.token}")
            setRequestProperty("User-Agent", settings.userAgent)
            setRequestProperty("Accept", "text/event-stream")
        }
        return try {
            stream.responseCode
            val connectionId = stream.getHeaderField(ACP_CONNECTION_HEADER) ?: return emptySet()
            val acpSessionToken = stream.getHeaderField(ACP_SESSION_TOKEN_HEADER)?.trim().orEmpty()
            val params = JSONObject()
                .put("sessionId", session.conversationId)
                .put("cwd", session.cwd.orEmpty())
                .put("mcpServers", JSONArray())
            Thread {
                runCatching {
                    postRpc(
                        url = url,
                        token = session.token,
                        connectionId = connectionId,
                        acpSessionToken = acpSessionToken,
                        method = ACP_LOAD,
                        params = params,
                        readTimeout = timeoutMs.toInt()
                    )
                }
            }.apply { isDaemon = true; start() }

            val deadline = System.currentTimeMillis() + timeoutMs
            val reader = BufferedReader(InputStreamReader(stream.inputStream, Charsets.UTF_8))
            while (System.currentTimeMillis() < deadline) {
                val line = runCatching { reader.readLine() }.getOrNull() ?: break
                if (!line.startsWith("data:")) continue
                val payload = runCatching { JSONObject(line.removePrefix("data:").trim()) }.getOrNull()
                    ?: continue
                val ids = availableModelIds(payload.optJSONObject("result"))
                if (ids.isNotEmpty()) return ids
            }
            emptySet()
        } catch (_: Exception) {
            emptySet()
        } finally {
            runCatching { stream.disconnect() }
        }
    }

    /** 从 `session/load` 的 result 里拆出可用模型 ID 集合。 */
    private fun availableModelIds(result: JSONObject?): Set<String> {
        val models = result?.optJSONObject("models") ?: return emptySet()
        val meta = models.optJSONObject("_meta")?.optJSONObject("codebuddy.ai")
        val array = meta?.optJSONArray("availableModels")
            ?: models.optJSONArray("availableModels")
            ?: return emptySet()
        return (0 until array.length()).mapNotNull { i ->
            when (val item = array.opt(i)) {
                is JSONObject -> item.optString("id").ifBlank { item.optString("model") }
                else -> item?.toString().orEmpty()
            }.takeIf { it.isNotBlank() }
        }.toSet()
    }

    /**
     * 下发或追问。
     *
     * 图片必须以 ACP `image` 内容块（内联 base64）发送：只给 `resource_link` 的话，
     * agent 拿到的只是 URI 文本，沙箱里没有图本体，它会回「你发给我的是一段 JSON」。
     * 其它文件继续用 `resource_link`，由沙箱按 URI 读取。
     *
     * prompt 的响应要等云端整个回合结束，用短超时把调用方尽快放回；
     * 指令已经随请求体送达，断开后云端仍会继续执行。
     */
    fun sendPrompt(session: Session, text: String, attachments: List<Artifact> = emptyList()) {
        val blocks = JSONArray()
        if (text.isNotBlank()) blocks.put(JSONObject().put("type", "text").put("text", text))
        attachments.forEach { a -> blocks.put(contentBlock(a)) }
        val params = JSONObject()
            .put("sessionId", session.conversationId)
            .put("cwd", session.cwd.orEmpty())
            .put("mcpServers", JSONArray())
            .put("prompt", blocks)
        linkOf(session).rpc(ACP_PROMPT, params, PROMPT_READ_TIMEOUT)
    }

    /**
     * 附件 → ACP 内容块。
     *
     * 只发 `resource_link` 的话，agent 沙箱读不到任何内容（它只看到一串 URI），
     * 所以内容必须内联：图片走 `image`，文本走 `resource.text`，其它二进制走 `resource.blob`。
     * 取不到字节或体积超限才退回链接，并尽量换成预签名下载地址，官方客户端才能点开。
     */
    private fun contentBlock(a: Artifact): JSONObject {
        val mime = a.mimeType.orEmpty()
        val bytes = inlineBytes(a)
        if (bytes != null && bytes.isNotEmpty()) {
            if (mime.startsWith("image/") && bytes.size <= MAX_INLINE_IMAGE) {
                return JSONObject()
                    .put("type", "image")
                    .put("mimeType", mime)
                    .put("data", Base64.getEncoder().encodeToString(bytes))
            }
            if (bytes.size <= MAX_INLINE_IMAGE) {
                val res = JSONObject().put("uri", a.uri).put("mimeType", mime)
                if (isTextLike(mime, a.name)) {
                    res.put("text", String(bytes, Charsets.UTF_8))
                } else {
                    res.put("blob", Base64.getEncoder().encodeToString(bytes))
                }
                return JSONObject().put("type", "resource").put("resource", res)
            }
        }
        return JSONObject()
            .put("type", "resource_link")
            .put("name", a.name)
            .put("title", a.name)
            .put("uri", presignedDownloadUrl(a) ?: a.uri)
            .apply { a.mimeType?.let { put("mimeType", it) } }
    }

    /** 能按纯文本读的 MIME / 扩展名（这类文件内联成 resource.text，agent 可直接读源码、日志、CSV）。 */
    private fun isTextLike(mime: String, name: String): Boolean {
        if (mime.startsWith("text/") || mime.contains("json") || mime.contains("xml")) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf(
            "txt", "md", "csv", "tsv", "log", "yaml", "yml", "json", "xml", "html", "css",
            "js", "ts", "kt", "java", "py", "go", "rs", "c", "h", "cpp", "sh", "sql", "ini", "conf"
        )
    }

    /** 内联图片/文件用的原始字节：优先取上传时缓存的那份，没有再按预签名地址下载。 */
    private fun inlineBytes(artifact: Artifact): ByteArray? =
        uploadCache[artifact.uri] ?: runCatching { downloadArtifact(artifact) }.getOrNull()

    /**
     * 设定本会话实际使用的大模型。
     *
     * 建会话接口里的 `model` 只写进会话元信息，真正执行用的模型由 ACP 决定；
     * 且 `session/set_model` 只对**同一条连接**上跑的 agent 生效（网页端也按会话缓存连接）。
     */
    fun setModel(session: Session, modelId: String) {
        linkOf(session).rpc(
            ACP_SET_MODEL,
            JSONObject().put("sessionId", session.conversationId).put("modelId", modelId)
        )
    }

    /** 中断当前执行（非退出排队）。同样必须发在那条常驻连接上。 */
    fun cancelTurn(session: Session) {
        linkOf(session).rpc(
            ACP_CANCEL,
            JSONObject().put("sessionId", session.conversationId),
            timeoutMs = CANCEL_TIMEOUT
        )
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
        linkOf(session).rpc(ACP_RESOLVE_INTERRUPTION, params)
    }

    // ---------- 积分用量 ----------

    /**
     * 拉取账号积分：资源包明细 + 今日请求次数。
     *
     * 端点取自 web 端「套餐与积分」页（`/profile/plans-usage`）。
     *
     * 主数据源用 `get-user-resource-summary`：它不要求 `PackageCodes`，直接返回
     * `Packages[].CycleTotalCapacity / CycleRemainCapacity / CycleUsedCapacity`，
     * 结构稳定。明细接口（`get-user-resource*`）必须带上整串包码与 `Status: [0, 3]`
     * 才有结果，漏传就是空列表 —— 之前的「字段无法识别」就是这样来的。
     */
    fun fetchUsage(): UsageSnapshot {
        val summary = runCatching {
            callEnvelope("POST", "/billing/meter/get-user-resource-summary", JSONObject())
        }.getOrNull()
        val fromSummary = summary?.let { packsOfSummary(it) }.orEmpty()
        if (fromSummary.isNotEmpty()) {
            return UsageSnapshot(packs = fromSummary, todayRequests = runCatching { todayRequests() }.getOrNull())
        }

        val accounts = mutableListOf<JSONObject>()
        // 兜底：账号下全部资源包
        runCatching {
            val data = callEnvelope(
                "POST", "/billing/meter/get-user-resource",
                JSONObject().put("PageNumber", 1).put("PageSize", USAGE_PACK_LIMIT)
            )
            accounts += accountsOf(data)
        }
        if (accounts.isEmpty()) {
            runCatching {
                val data = callEnvelope(
                    "POST", "/billing/meter/get-user-resource-paid-packages",
                    JSONObject()
                        .put("PageNumber", 1)
                        .put("PageSize", USAGE_PACK_LIMIT)
                        .put("PackageCodes", JSONArray(PAID_PACKAGE_CODES))
                        .put("Status", JSONArray(listOf(0, 3)))
                )
                accounts += accountsOf(data)
            }
        }
        if (accounts.isEmpty()) throw IllegalStateException("积分接口未返回资源包")

        val packs = accounts.distinctBy { it.toString() }.mapNotNull { o ->
            val total = numberOf(o, "Total", "total", "TotalCapacity", "Capacity", "capacity")
            val left = numberOf(o, "left", "Left", "RemainCapacity", "Remain", "Remaining", "remain", "Balance")
            if (total == null && left == null) return@mapNotNull null
            UsagePack(
                name = o.optString("PackageName").ifBlank { packageLabel(o.optString("PackageCode")) },
                total = total ?: 0.0,
                left = left ?: 0.0,
                status = o.optString("Status").takeIf { it.isNotBlank() },
                expireAt = o.optString("ExpiredTime").ifBlank { o.optString("DeductionEndTime") }
                    .takeIf { it.isNotBlank() }
            )
        }
        if (packs.isEmpty()) throw IllegalStateException("积分接口字段无法识别")

        // 今日用量失败不影响余额展示
        return UsageSnapshot(packs = packs, todayRequests = runCatching { todayRequests() }.getOrNull())
    }

    /** 解析 summary 的 `Packages[]`；容量字段是 Cycle*Capacity 三件套。 */
    private fun packsOfSummary(data: JSONObject): List<UsagePack> {
        val arr = data.optJSONArray("Packages") ?: data.optJSONArray("packages") ?: return emptyList()
        val subscription = data.optString("SubscriptionPackageName")
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val total = numberOf(o, "CycleTotalCapacity", "TotalCapacity")
            val left = numberOf(o, "CycleRemainCapacity", "RemainCapacity")
            val used = numberOf(o, "CycleUsedCapacity", "UsedCapacity")
            if (total == null && left == null) return@mapNotNull null
            val code = o.optString("PackageCode")
            UsagePack(
                name = subscription.ifBlank { packageLabel(code) },
                total = total ?: ((left ?: 0.0) + (used ?: 0.0)),
                left = left ?: ((total ?: 0.0) - (used ?: 0.0))
            )
        }
    }

    /** 包码 → 可读名称。包码形如 `TCACA_code_001_PqouKr6QWV`，末段是随机串。 */
    private fun packageLabel(code: String): String {
        val number = Regex("""code_(\d{3})""").find(code)?.groupValues?.get(1)
        return when (number) {
            "001" -> "免费额度"
            "002", "003", "004" -> "专业版"
            "006" -> "赠送积分"
            "039", "040" -> "试用额度"
            else -> if (number != null) "积分包 $number" else "积分包"
        }
    }

    /** 今日请求次数：优先取信封里的 `total`，否则数列表长度。 */
    private fun todayRequests(): Int? {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val end = fmt.format(Date())
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }
        val body = JSONObject()
            .put("startTime", fmt.format(calendar.time))
            .put("endTime", end)
            .put("pageNum", 1)
            .put("pageSize", 1)
        val data = callEnvelope("POST", "/billing/meter/get-user-daily-usage", body) ?: return null
        (data.opt("total") as? Number)?.let { return it.toInt() }
        return data.optJSONArray("data")?.length() ?: data.optJSONArray("list")?.length()
    }

    /** 递归找资源包数组（`Accounts`），各接口层级不一。 */
    private fun accountsOf(node: Any?): List<JSONObject> = when (node) {
        is JSONObject -> {
            val direct = node.optJSONArray("Accounts") ?: node.optJSONArray("accounts")
            if (direct != null) {
                (0 until direct.length()).mapNotNull { direct.optJSONObject(it) }
            } else {
                node.keys().asSequence().mapNotNull { accountsOf(node.opt(it)) }.firstOrNull().orEmpty()
            }
        }

        is JSONArray -> (0 until node.length()).asSequence()
            .mapNotNull { accountsOf(node.opt(it)) }.firstOrNull().orEmpty()

        else -> emptyList()
    }

    /** 按候选键取数值，兼容数字与数字字符串两种形态。 */
    private fun numberOf(o: JSONObject, vararg keys: String): Double? {
        keys.forEach { key ->
            val d = when (val v = o.opt(key)) {
                is Number -> v.toDouble()
                is String -> v.toDoubleOrNull()
                else -> null
            }
            if (d != null && d.isFinite()) return d
        }
        return null
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
        val artifact = Artifact(name = name, uri = "agent://files/${objectKeyOf(name)}", mimeType = mimeType)
        if (bytes.size <= MAX_INLINE_IMAGE) uploadCache[artifact.uri] = bytes
        return artifact
    }

    /** 下载产物文件字节（换取预签名下载地址后 GET）。 */
    fun downloadArtifact(artifact: Artifact): ByteArray {
        val url = presignedDownloadUrl(artifact)
            ?: throw IllegalStateException("下载失败：未取得预签名地址")
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

    /** 预签名下载地址；取不到返回 null（附件仍按原 URI 发送）。 */
    private fun presignedDownloadUrl(artifact: Artifact): String? = runCatching {
        val key = objectKeyOf(artifact.uri)
        presign(listOf(key)).firstOrNull()?.optString("download_url")?.takeIf { it.isNotBlank() }
    }.getOrNull()

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
            // billing 接口按来源分流（web / miniProgram），缺省会拿到非预期结构
            setRequestProperty("X-Client-Platform", CLIENT_PLATFORM)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        if (body != null) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        conn.headerFields?.let { settings.cookies.absorb(it, url) }
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.let { s ->
            BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { it.readText() }
        }
        // 不调用 disconnect()：响应体已读到 EOF，连接会回到 keep-alive 池，
        // 下一次同 host 的轮询直接复用这条 TCP/TLS 连接，省掉重复握手的几 KB。
        return HttpResult(code, text)
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

    // ---------- 内部：常驻 ACP 连接 ----------

    /**
     * 一条常驻的 ACP 连接。
     *
     * 网页端按会话缓存连接（`connectionCache`），因为 `session/set_model`、
     * `session/cancel` 这类控制方法只对**同一条连接**上跑的 agent 生效。
     * 每次新建连接去发，服务端会当成另一个客户端而静默丢弃，
     * 表现就是「选了模型不生效」「点了中断没反应」，而 prompt 却照常送达。
     */
    inner class AcpLink internal constructor(
        val sessionId: String,
        private val url: String,
        private val bearer: String,
        private val connectionId: String,
        private val sessionToken: String,
        private val raw: HttpURLConnection
    ) {
        @Volatile
        private var closed = false
        @Volatile
        var lastUsedAt: Long = System.currentTimeMillis()
            private set

        val alive: Boolean get() = !closed

        fun rpc(method: String, params: JSONObject, timeoutMs: Int = READ_TIMEOUT) {
            check(alive) { "ACP 连接已关闭" }
            postRpc(url, bearer, connectionId, sessionToken, method, params, timeoutMs)
            lastUsedAt = System.currentTimeMillis()
        }

        fun close() {
            closed = true
            runCatching { raw.disconnect() }
        }
    }

    private val links = java.util.concurrent.ConcurrentHashMap<String, AcpLink>()

    private fun linkOf(session: Session): AcpLink {
        links[session.conversationId]?.takeIf { it.alive }?.let { return it }
        return openLink(session).also { links[session.conversationId] = it }
    }

    /** 关闭某会话的常驻连接（换配置 / 手动停止后释放）。 */
    fun closeLink(id: String) {
        links.remove(id)?.close()
    }

    fun closeAllLinks() {
        links.values.forEach { it.close() }
        links.clear()
    }

    /** 回收长时间没动过的连接，避免息屏后还挂着一条流。 */
    fun trimLinks(maxIdleMs: Long) {
        val now = System.currentTimeMillis()
        links.values
            .filter { !it.alive || now - it.lastUsedAt > maxIdleMs }
            .forEach { link -> if (links.remove(link.sessionId, link)) link.close() }
    }

    private fun openLink(session: Session): AcpLink {
        val url = normalizeLink(session.link)
        val raw = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = LINK_READ_TIMEOUT
            setRequestProperty("Authorization", "Bearer ${session.token}")
            setRequestProperty("User-Agent", settings.userAgent)
            setRequestProperty("Accept", "text/event-stream")
        }
        val connectionId: String
        val sessionToken: String
        try {
            raw.responseCode // 触发握手
            connectionId = raw.getHeaderField(ACP_CONNECTION_HEADER)
                ?: throw IllegalStateException("ACP 握手失败：缺少 $ACP_CONNECTION_HEADER")
            sessionToken = raw.getHeaderField(ACP_SESSION_TOKEN_HEADER)?.trim().orEmpty()
        } catch (t: Throwable) {
            runCatching { raw.disconnect() }
            throw t
        }
        val link = AcpLink(session.conversationId, url, session.token, connectionId, sessionToken, raw)
        // 事件流必须有人读：否则接收缓冲塞满后服务端会主动断连，连接也就不再是「同一条」。
        // 进度仍由轮询给出，这里只负责把流读掉。
        Thread({
            runCatching {
                raw.inputStream.use { input ->
                    val buf = ByteArray(8 * 1024)
                    while (link.alive && input.read(buf) >= 0) {
                        // 读掉即可
                    }
                }
            }
        }, "acp-link-${session.conversationId}").apply { isDaemon = true }.start()
        return link
    }

    /** 这条会话是否还有活着的常驻连接（连接重建后模型设置需要重发）。 */
    fun hasLink(id: String): Boolean = links[id]?.alive == true

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
        // 网页端把 sessionStatus 与 status 视为同义，会话生命周期另有 lifecycle
        // （active / completed / archived）。这里按优先级取第一个有值的，
        // 都取不到就留空——绝不能默认 RUNNING，否则界面会永远转圈。
        val session = o.optJSONObject("session")
        val status = sequenceOf(
            o.optString("status"),
            o.optString("sessionStatus"),
            o.optString("lifecycle"),
            session?.optString("sessionStatus").orEmpty(),
            session?.optString("status").orEmpty(),
            session?.optString("state").orEmpty()
        ).firstOrNull { it.isNotBlank() }.orEmpty().uppercase()
        return RemoteConversation(
            id = id,
            title = o.optString("name").takeIf { it.isNotBlank() }
                ?: o.optString("title").takeIf { it.isNotBlank() },
            status = status,
            model = sequenceOf(
                o.optString("model"),
                o.optJSONObject("status")?.optString("model").orEmpty(),
                session?.optString("model").orEmpty()
            ).firstOrNull { it.isNotBlank() },
            session = parseSession(id, session),
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
        /**
         * 兜底模型：账号没有显式选择时使用。
         *
         * 必须落在账号实际支持的清单里（云端对未知模型直接 400 `service info not found`），
         * 旧值 `deepseek-r1` 已下线，改用官方端默认的 hy4-preview。
         */
        const val DEFAULT_MODEL = "hy4-preview"

        /**
         * 资源包明细接口必须显式传包码，否则返回空列表。
         * 取自 web 端 `BackendProvider` 里的包码常量（免费 / 专业 / 赠送 / 试用）。
         */
        private val PAID_PACKAGE_CODES = listOf(
            "TCACA_code_001_PqouKr6QWV", // free
            "TCACA_code_002_AkiJS3ZHF5", // proMon
            "TCACA_code_006_DbXS0lrypC", // gift
            "TCACA_code_029_6wCGEWquYy", // bonus29
            "TCACA_code_030_BjSt89qTvr", // bonus30
            "TCACA_code_035_ArVxJcGDsm", // freeMonIntl
            "TCACA_code_036_lupO5WgNdG", // extraIntl
            "TCACA_code_037_WxOD3MpI2o", // bonusIntl
            "TCACA_code_038_OhvqZtiPKr", // extra38
            "TCACA_code_039_KRcQj7wUat", // proTrialMon
            "TCACA_code_040_mi9rCYg46x"  // proTrialYear
        )

        private const val TITLE_LIMIT = 100
        private const val CONNECT_TIMEOUT = 8_000
        private const val READ_TIMEOUT = 15_000
        /** prompt 的响应要等整个回合，故意压低：指令已送出，界面不该陪着等。 */
        private const val PROMPT_READ_TIMEOUT = 25_000
        /** 上传 / 下载产物文件时允许更长的读写时间。 */
        private const val TRANSFER_TIMEOUT = 60_000
        private const val HANDSHAKE_READ_TIMEOUT = 20_000
        /** 常驻连接的事件流读超时：要能跨过整个云端回合，断了就重连。 */
        private const val LINK_READ_TIMEOUT = 10 * 60_000
        /** 中断请求要快速返回，避免界面卡在「正在中断」。 */
        private const val CANCEL_TIMEOUT = 8_000

        /** 内联图片上限：再大就退回 `resource_link`，避免单条 prompt 被网关拒收。 */
        private const val MAX_INLINE_IMAGE = 8 * 1024 * 1024
        /** 上传缓存保留的份数。 */
        private const val MAX_CACHED_UPLOADS = 4
        /** 拉取会话可用模型清单（session/load 响应）的最长等待。 */
        private const val MODELS_TIMEOUT = 8_000L

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

        /** ACP 扩展方法：设定本会话实际使用的大模型（`{sessionId, modelId}`）。 */
        private const val ACP_SET_MODEL = "session/set_model"

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

        /** billing 接口按来源分流，web 端固定传 `web`。 */
        private const val CLIENT_PLATFORM = "web"

        /** 积分资源包一次最多拉取的条数。 */
        private const val USAGE_PACK_LIMIT = 100

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
