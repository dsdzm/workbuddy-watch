package com.workbuddy.watch.data

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * WorkBuddy 原生登录（手机号 + 短信验证码），**不依赖 WebView、不依赖浏览器**。
 *
 * ## 为什么换成了这一套
 *
 * 旧实现走的是网关 → Keycloak 表单（`/auth/realms/copilot/protocol/openid-connect/auth?client_id=console`）
 * + 老的发码接口 `/auth/realms/copilot/sms/authentication-code`。实测这个发码接口是个**死桩**：
 *
 * | 请求 | 老接口 | 新接口 |
 * |---|---|---|
 * | 无效号码 `13800000000` | `200 {"expires_in":60}` ← 照样"成功" | `500 {"error_description":"…"}` |
 * | 真实号码但额度已用尽 | `200 {"expires_in":60}` | `500 …PhoneNumberDailyLimit` |
 *
 * 它对**任何输入**都回 200、从不真的下发，所以客户端永远以为"发出去了只是没收到"——这才是
 * 「收不到验证码」的真正原因（与客户端指纹、WebView 内核、浏览器都无关）。
 *
 * 官方手机端登录页用的是**全新的 auth-v2 JSON 接口**，见 `index-DqCPfXcB.js`。
 * 页面地址就是实测能收到短信的那条（[CloudSettings.loginPageUrl]）：
 *
 *     {base}/login/?platform=agents&state=0&redirect_uri={base}/app
 *
 * 注意页面级 `platform=agents` 与下面发码接口的 `platform=web` 不是同一层含义：
 * 前者是 SPA 的页面配置（决定品牌 / 回跳 / 可用登录方式），后者由页面脚本写死（`hn="web"`），
 * 两者在官方那条能收到短信的链路里就是这么并存的。
 *
 * ```
 * de = "/auth-v2/realms/copilot/signin"
 * createSession: ${de}/phone/login    GET  ?platform=web                       -> {session_state}
 * sendSms:       ${de}/phone/sms      POST {session_state, phone}
 *                                     -> {status:"ok"|"cooldown"|"captcha_required", retry_after, expires_in}
 *                                        失败时 5xx {errno, error, error_description}
 * verify:        ${de}/phone/verify   POST {session_state, phone, sms_code, skip_enterprise:true}
 *                                     -> {status, ticket, nickname}
 * finalize:      ${de}/finalize       GET  ?ticket=…&response_type=code&session_state=…  -> {status, code}
 * shortSession:  ${de}/session        POST {code, issuer, redirect_uri}         -> 换取会话 cookie
 * ```
 *
 * 这套接口会**如实回 `cooldown` / `captcha_required` / 腾讯短信通道的错误原因**，
 * 不再有"假成功"。登录完成后按官方页面的说法「由后续拉账号列表补种登录态」：
 * 用同一套 Keycloak SSO 静默走一遍 OIDC，让网关把 `session` 种下来（[bootstrapConsoleSession]）。
 *
 * 整条链路共用 [CloudSettings.cookies] 这一个浏览器式 cookie 罐，不依赖系统 CookieManager。
 */
class NativeLogin(private val settings: CloudSettings) {

    /** 本次登录会话的 `session_state`（auth-v2 用它串联发码 / 校验 / 签发）。 */
    private var sessionState: String? = null

    /**
     * 第 1~2 步：建登录会话并发短信验证码。
     *
     * @return 重发倒计时秒数（服务端 `retry_after`，缺省 60）。
     *   若 Keycloak SSO 仍有效、无需短信即可换到新会话，直接完成登录并返回 [ALREADY_SIGNED_IN]。
     */
    fun sendCode(phone: String): Int {
        sessionState = null

        // 老接口的死桩已废弃，但 SSO 仍可能让我们完全不用短信：先试一次静默登录。
        if (bootstrapConsoleSession() && verify()) {
            settings.saveSession(account = phone)
            return ALREADY_SIGNED_IN
        }

        val state = createAuthSession()
        sessionState = state

        val payload = JSONObject()
            .put("session_state", state)
            .put("phone", phoneNumber(phone))
            .toString()
        val res = call("POST", authV2(SMS_PATH), payload, follow = true, jsonLike = true)

        val json = runCatching { JSONObject(res.body) }.getOrNull()
            ?: throw IllegalStateException("验证码发送失败，请稍后重试")

        when (json.optString("status")) {
            "ok" -> return json.optInt("retry_after", DEFAULT_COOLDOWN)
                .takeIf { it > 0 } ?: DEFAULT_COOLDOWN

            // 服务端主动告诉我们"冷却中"，还给了还要等几秒
            "cooldown" -> throw IllegalStateException(
                "发送过于频繁，请 " + json.optInt("retry_after", DEFAULT_COOLDOWN) + " 秒后再试"
            )

            // 客户端被判定为可疑时会要求过腾讯人机验证，本机没有对应 SDK
            "captcha_required" -> throw IllegalStateException(
                "服务端要求人机安全验证，请改用手机上打开的官方页面登录"
            )
        }
        throw IllegalStateException(smsErrorText(json))
    }

    /**
     * 第 3~5 步：提交验证码 → 签发 code → 换会话 → 校验并保存。
     * 必须在 [sendCode] 之后调用（依赖同一次登录会话）。
     */
    fun submit(phone: String, code: String): String {
        val state = sessionState ?: throw IllegalStateException("请先获取验证码")

        val payload = JSONObject()
            .put("session_state", state)
            .put("phone", phoneNumber(phone))
            .put("sms_code", code.trim())
            .put("skip_enterprise", true)
            .toString()
        val res = call("POST", authV2(PHONE_VERIFY_PATH), payload, follow = true, jsonLike = true)
        val json = runCatching { JSONObject(res.body) }.getOrNull()

        if (json?.optString("status") != "ok") throw IllegalStateException(verifyErrorText(json))
        val ticket = json.optString("ticket")
        if (ticket.isBlank()) throw IllegalStateException("登录票据缺失，请重新获取验证码")

        // finalize 直接签发 OIDC authorization code
        val finalizeUrl = authV2(FINALIZE_PATH) + "?ticket=" + enc(ticket) +
            "&response_type=code&session_state=" + enc(state)
        val finalized = runCatching {
            JSONObject(call("GET", finalizeUrl, null, follow = true, jsonLike = true).body)
        }.getOrNull()
        val authCode = finalized?.optString("code").orEmpty()

        // 官方前端的 [short-session] 端点：拿 code 直接换会话 cookie
        if (authCode.isNotBlank()) runCatching { shortSession(authCode) }

        // 官方页面在非企微场景下不调 short-session，而是「由后续拉账号列表补种登录态」
        if (!verify()) {
            bootstrapConsoleSession()
            if (!verify()) throw IllegalStateException("登录未生效，请稍后重试")
        }

        settings.saveSession(account = phone)
        return phone
    }

    /**
     * 静默续期：**不需要验证码**。网关会话过期或被顶掉后，只要 Keycloak 的 SSO cookie 还有效，
     * 就能重走一次 OIDC 拿新的 `session`。由云端客户端在收到 401 / 被重定向时自动调用。
     *
     * @return 续期后的会话是否真的可用（SSO 也没了则返回 false，需要重新登录）。
     */
    fun renew(): Boolean = runCatching {
        bootstrapConsoleSession() && verify()
    }.getOrDefault(false)

    // ---------- auth-v2 ----------

    /** 建登录会话：`GET {de}/phone/login?platform=web` → `{session_state}`。 */
    private fun createAuthSession(): String {
        val url = authV2(PHONE_LOGIN_PATH) + "?platform=" + PLATFORM
        val res = call("GET", url, null, follow = true, jsonLike = true)
        val state = runCatching { JSONObject(res.body).optString("session_state") }.getOrNull()
        if (state.isNullOrBlank()) throw IllegalStateException("无法发起登录，请检查网络后重试")
        return state
    }

    /** 拿 code 换会话 cookie（官方注释里的 `[short-session] 短 cookie`）。 */
    private fun shortSession(authCode: String) {
        val payload = JSONObject()
            .put("code", authCode)
            .put("issuer", base() + ISSUER_PATH)
            .put("redirect_uri", base() + CALLBACK_PATH)
            .toString()
        call("POST", authV2(SHORT_SESSION_PATH), payload, follow = true, jsonLike = true)
    }

    /**
     * 用 SSO 静默走一遍 OIDC，让网关把 `session` 种下来。官方页面把这一步叫作
     * 「由后续拉账号列表补种登录态」。
     *
     * 关键：先丢掉失效的网关会话——实测网关**只在完全没有 `session` / `session_2` 时**
     * 才把入口 302 到 Keycloak；带着失效会话访问入口只会 200 且不跳转。
     */
    private fun bootstrapConsoleSession(): Boolean = runCatching {
        settings.cookies.dropGatewaySession()
        val boot = call("GET", base() + OIDC_ENTRY, null, follow = false)
        val authUrl = boot.location
        if (authUrl.isNullOrBlank()) return@runCatching false
        call("GET", absolute(authUrl), null, follow = true)
        true
    }.getOrDefault(false)

    /** 用登录后的会话请求一次会话列表，确认登录真的生效。 */
    private fun verify(): Boolean {
        val res = call("GET", base() + SESSION_PROBE_PATH, null, follow = true)
        val envelope = runCatching { JSONObject(res.body).optInt("code", -1) }.getOrDefault(-1)
        return res.status in 200..299 && envelope == 0
    }

    /** 把腾讯短信通道的真实原因翻译成人话。 */
    private fun smsErrorText(json: JSONObject): String {
        val error = json.optString("error")
        val desc = json.optString("error_description")
        return when {
            desc.contains("PhoneNumberDailyLimit", ignoreCase = true) ->
                "该号码今日验证码次数已达上限，请明天再试"

            desc.contains("MinuteLimit", ignoreCase = true) ||
                desc.contains("ThirtySecondLimit", ignoreCase = true) ||
                desc.contains("SecondLimit", ignoreCase = true) -> "发送过于频繁，请稍后再试"

            error == "invalid_request" -> "手机号不可用，请检查后重试"
            error == "invalid_session" -> "登录会话已失效，请重新获取验证码"
            error == "account_unavailable" -> "该账号暂不可用，请更换手机号"
            desc.isNotBlank() -> "短信发送失败：$desc"
            error.isNotBlank() -> "短信发送失败（$error）"
            else -> "验证码发送失败，请稍后重试"
        }
    }

    /** 校验阶段的错误。 */
    private fun verifyErrorText(json: JSONObject?): String = when (json?.optString("error")) {
        "invalid_grant" -> "验证码错误或已失效，请重新输入"
        "invalid_session" -> "登录会话已失效，请重新获取验证码"
        "account_unavailable" -> "该账号暂不可用，请更换手机号"
        else -> "验证码校验失败，请重新输入"
    }

    private fun authV2(path: String) = base() + AUTH_V2 + path

    /** 统一加区号，与服务端一致（中国大陆 +86）。 */
    private fun phoneNumber(phone: String): String = "+86" + phone.trim().filter { it.isDigit() }

    // ---------- 内部：HTTP ----------

    private class Response(val status: Int, val location: String?, val body: String)

    /**
     * 发一次请求。手动处理重定向，以便（a）每次都带上最新的 cookie，
     * （b）需要时可停在 302 上读取 `Location`。
     *
     * @param jsonLike 按浏览器 XHR / fetch 的样子发 `Accept` / `Accept-Language` / `Referer` / `Origin`。
     */
    private fun call(
        method: String,
        url: String,
        body: String?,
        follow: Boolean,
        jsonLike: Boolean = false
    ): Response {
        var target = url
        var verb = method
        var payload = body
        var hops = 0

        while (true) {
            val conn = (URL(target).openConnection() as HttpURLConnection).apply {
                requestMethod = verb
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                setRequestProperty("User-Agent", settings.userAgent)
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
                if (jsonLike) {
                    setRequestProperty("Referer", settings.loginPageUrl())
                    setRequestProperty("Origin", originOf(base()))
                    setRequestProperty("Accept", "application/json, text/plain, */*")
                } else {
                    setRequestProperty("Referer", "${base()}/")
                    setRequestProperty("Accept", "text/html, application/json, */*")
                }
                settings.cookies.header(target)?.let { setRequestProperty("Cookie", it) }
                if (payload != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            val status: Int
            val text: String
            val location: String?
            try {
                if (payload != null) conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                status = conn.responseCode
                conn.headerFields?.let { settings.cookies.absorb(it, target) }
                location = conn.getHeaderField("Location")
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                text = stream?.let { s ->
                    BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { it.readText() }
                }.orEmpty()
            } finally {
                conn.disconnect()
            }

            val redirect = status == 301 || status == 302 || status == 303 || status == 307 || status == 308
            if (!redirect || location.isNullOrBlank()) return Response(status, location, text)
            hops++
            if (!follow || hops > MAX_HOPS) return Response(status, location, text)

            // 302/303 之后按浏览器语义降级为 GET
            if (status == 303 || (status == 302 && verb == "POST")) {
                verb = "GET"
                payload = null
            }
            target = URL(URL(target), location).toString()
        }
    }

    private fun base() = settings.baseUrl.trimEnd('/').ifBlank { CloudSettings.DEFAULT_BASE_URL }

    /** 把可能是相对路径的地址补全为绝对地址（网关的 `Location` 两种形态都出现过）。 */
    private fun absolute(url: String): String = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        url.startsWith("/") -> base() + url
        else -> "${base()}/$url"
    }

    /** 取 `scheme://host[:port]`，用于给浏览器式请求补 `Origin` 头。 */
    private fun originOf(url: String): String = runCatching {
        val u = URL(url)
        buildString {
            append(u.protocol).append("://").append(u.host)
            if (u.port > 0) append(':').append(u.port)
        }
    }.getOrDefault(url.trimEnd('/'))

    private fun enc(raw: String) = URLEncoder.encode(raw, "UTF-8")

    companion object {
        /**
         * [sendCode] 的返回值：Keycloak SSO 仍有效，已在无短信的情况下换到新会话。
         * 界面据此直接进入已登录状态，不必再走验证码。
         */
        const val ALREADY_SIGNED_IN = 0

        /** 未登录时会被网关重定向到 Keycloak 的入口（官方 web 端也是用它探测登录态）。 */
        private const val OIDC_ENTRY = "/console/accounts"
        /** 登录态探测：能正常返回 envelope 即说明会话已生效。 */
        private const val SESSION_PROBE_PATH = "/console/as/conversations/?type=cloud&page=1&size=1"

        /** auth-v2 手机号登录链路（取自官方登录页 bundle）。 */
        private const val AUTH_V2 = "/auth-v2/realms/copilot/signin"
        private const val PHONE_LOGIN_PATH = "/phone/login"
        private const val SMS_PATH = "/phone/sms"
        /** 提交短信验证码。注意不要和 [SESSION_PROBE_PATH] 混用。 */
        private const val PHONE_VERIFY_PATH = "/phone/verify"
        private const val FINALIZE_PATH = "/finalize"
        private const val SHORT_SESSION_PATH = "/session"

        /** 官方页面固定的 `platform=web`。 */
        private const val PLATFORM = "web"

        /** OIDC issuer，取自 auth-v2 的 `.well-known/openid-configuration`。 */
        private const val ISSUER_PATH = "/auth/realms/copilot"

        /** 官方登录页拿到 code 后回跳的地址（`/app`，也就是消费这些接口的那个 SPA）。 */
        private const val CALLBACK_PATH = "/app"

        private const val DEFAULT_COOLDOWN = 60

        private const val CONNECT_TIMEOUT = 10_000
        private const val READ_TIMEOUT = 20_000
        private const val MAX_HOPS = 8
    }
}
