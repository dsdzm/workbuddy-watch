package com.workbuddy.watch.data

import android.content.Context
import android.webkit.CookieManager
import java.net.URLEncoder

/**
 * 云端登录态。手表独立运行，因此状态保存在本机（[Context.MODE_PRIVATE]）。
 *
 * 登录凭据（网关 `session` + Keycloak SSO 等一整套 cookie）由原生登录 [NativeLogin]
 * 收进 [cookies]，后续请求按路径手动下发 `Cookie` 头，
 * **完全不依赖 WebView / 系统 CookieManager**。
 */
class CloudSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("workbuddy_watch", Context.MODE_PRIVATE)

    /** 浏览器式 cookie 罐：登录与云端接口共用，登录后长期存续。 */
    val cookies = CookieStore(context)

    /** WorkBuddy 云上模式站点；登录页与 console 接口同在此域下。 */
    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL).orEmpty()
        set(value) = prefs.edit().putString(KEY_BASE_URL, value.trim()).apply()

    /** 是否已完成登录。 */
    var loggedIn: Boolean
        get() = prefs.getBoolean(KEY_LOGGED_IN, false)
        set(value) = prefs.edit().putBoolean(KEY_LOGGED_IN, value).apply()

    /** 登录账号提示（手机号），仅用于界面展示。 */
    var accountHint: String
        get() = prefs.getString(KEY_ACCOUNT, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_ACCOUNT, value.trim()).apply()

    /** 已登录且本机确实还留着凭据，才算真正可用。 */
    val isLoggedIn: Boolean get() = loggedIn && cookies.hasAny()

    /**
     * 表盘形状：`true` 圆屏 / `false` 方屏。首次启动必须由用户在引导页选一次，
     * 之后记住（[shapeChosen] 为 true）。选错的后果是内容被圆形表盘裁掉或留白过大，
     * 所以这里不做自动探测，强制人工确认。
     */
    var roundScreen: Boolean
        get() = prefs.getBoolean(KEY_ROUND, true)
        set(value) = prefs.edit().putBoolean(KEY_ROUND, value).apply()

    /** 是否已在引导页选过表盘形状。 */
    var shapeChosen: Boolean
        get() = prefs.getBoolean(KEY_SHAPE_CHOSEN, false)
        private set(value) = prefs.edit().putBoolean(KEY_SHAPE_CHOSEN, value).apply()

    /** 引导页保存用户选择。 */
    fun saveScreenShape(round: Boolean) {
        roundScreen = round
        shapeChosen = true
    }

    /** 当前选用的模型 ID（空表示用云端默认）。 */
    var selectedModel: String
        get() = prefs.getString(KEY_MODEL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_MODEL, value.trim()).apply()

    /** 账号所属企业 ID；个人账号为 "personal"。用于模型列表接口。 */
    var enterpriseId: String
        get() = prefs.getString(KEY_ENTERPRISE, ENTERPRISE_PERSONAL).orEmpty().ifBlank { ENTERPRISE_PERSONAL }
        set(value) = prefs.edit().putString(KEY_ENTERPRISE, value.trim()).apply()

    /**
     * 官方登录页地址。被测过能真正收到短信的**就是这一个**（见 [LOGIN_PLATFORM] / [LOGIN_REDIRECT_PATH]）：
     *
     *     {base}/login/?platform=agents&state=0&redirect_uri={base}/app
     *
     * 不要再退回 `/console/accounts`：那会把页面 302 到老的 Keycloak 表单
     * （`client_id=console`），它的发码接口是个死桩，任何输入都回 200 却从不发短信。
     */
    fun loginPageUrl(): String {
        val root = baseUrl.trimEnd('/').ifBlank { DEFAULT_BASE_URL }
        return root + LOGIN_PATH + "?platform=" + LOGIN_PLATFORM +
            "&state=0&redirect_uri=" + URLEncoder.encode(root + LOGIN_REDIRECT_PATH, "UTF-8")
    }

    /** 原生登录成功后落盘：cookie 已由 [CookieStore] 逐条收集：由 [NativeLogin] 调用。 */
    fun saveSession(account: String = "") {
        prefs.edit()
            .putBoolean(KEY_LOGGED_IN, true)
            .putString(KEY_ACCOUNT, account.trim())
            .apply()
    }

    fun signOut() {
        cookies.clear()
        prefs.edit()
            .putBoolean(KEY_LOGGED_IN, false)
            .putString(KEY_ACCOUNT, "")
            .apply()
    }

    /**
     * 登录态已被网关判定失效（401 且静默续期也没救回来）：清掉登录标记，
     * 并只丢掉失效的网关会话，**保留 Keycloak SSO cookie**。
     * 这样界面回到「可重新登录」的状态，而 SSO 仍有效时重新登录不必再收一次验证码。
     */
    fun markSessionExpired() {
        cookies.dropGatewaySession()
        prefs.edit().putBoolean(KEY_LOGGED_IN, false).apply()
    }

    /**
     * 请求网关 / Keycloak 统一使用的 User-Agent。
     *
     * 实测结论：网关签发的 `session` 会跟**签发时**的 User-Agent 绑定 —— 登录用哪个 UA，
     * 后续业务请求就必须用同一个，否则 401（同一份 cookie 只改这一项即可复现 200 ↔ 401 切换）。
     * 因此登录与业务请求必须共用这一个值。
     *
     * 由于短信验证码只能由真浏览器内核发起（见 [adoptWebViewCookies] 与
     * [com.workbuddy.watch.ui.screens.WebLoginScreen]），会话实际上是 WebView 用**它自己的**
     * UA 签发的，所以网页登录成功后必须把那个 UA 记下来（[saveUserAgent]），原生请求才对得上。
     */
    var userAgent: String
        get() = prefs.getString(KEY_USER_AGENT, "").orEmpty().ifBlank { DEFAULT_USER_AGENT }
        private set(value) = prefs.edit().putString(KEY_USER_AGENT, value.trim()).apply()

    /** 记下网页登录实际使用的 UA（网关会话与这个值绑定）。 */
    fun saveUserAgent(value: String) {
        if (value.isNotBlank()) userAgent = value
    }

    /**
     * 把网页登录（WebView）换到的浏览器 cookie 搬进本机 cookie 罐。
     *
     * Android 的 [CookieManager.getCookie] 只按 URL 返回 `name=value` 串、**不带 Path**，
     * 所以这里用「在哪些路径下可见」反推每条 cookie 的 Path：
     *  - 在 `/` 可见 → `Path=/`（网关 `session`、`tgw_l7_route` 都在这里）
     *  - 只在 `/console` 可见 → `Path=/console`
     *  - 只在 `/auth/realms/copilot/` 可见 → `Path=/auth/realms/copilot/`（Keycloak 的 SSO cookie）
     * 这样原生请求按路径下发时两边都不会漏。
     */
    fun adoptWebViewCookies(base: String) {
        val root = base.trimEnd('/').ifBlank { DEFAULT_BASE_URL }
        val cm = CookieManager.getInstance()
        cm.flush()
        val atRoot = splitCookies(cm.getCookie("$root/"))
        val atConsole = splitCookies(cm.getCookie("$root/console"))
        val atAuth = splitCookies(cm.getCookie("$root$AUTH_PATH"))
        if (atRoot.isEmpty() && atConsole.isEmpty() && atAuth.isEmpty()) return

        // 这次是网页上的全新登录，旧凭据（可能来自失败的原生登录）一律作废
        cookies.clear()
        (atRoot.keys + atConsole.keys + atAuth.keys).forEach { name ->
            val value = atRoot[name] ?: atConsole[name] ?: atAuth[name] ?: return@forEach
            if (value.isEmpty()) return@forEach
            val path = when {
                atRoot.containsKey(name) -> "/"
                atConsole.containsKey(name) -> "/console"
                else -> AUTH_PATH
            }
            cookies.put(name, value, path)
        }
    }

    private fun splitCookies(header: String?): Map<String, String> =
        header.orEmpty().split(';').mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) null else part.substring(0, eq).trim() to part.substring(eq + 1).trim()
        }.toMap()

    companion object {
        /** console 接口与登录页所在域。 */
        const val DEFAULT_BASE_URL = "https://www.workbuddy.cn"

        /** 官方登录页路径。 */
        const val LOGIN_PATH = "/login/"

        /**
         * 官方登录页的 `platform` 查询参数（页面级配置，决定品牌 / 回跳 / 可用登录方式）。
         * 实测能收到短信的官方地址用的就是 `agents`。
         */
        const val LOGIN_PLATFORM = "agents"

        /** 登录完成后回跳的地址（消费这些接口的那个 SPA）。 */
        const val LOGIN_REDIRECT_PATH = "/app"

        /** 没有网页登录 UA 时使用的兜底 UA。 */
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7 Build/TQ3A.230805.001) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        /** Keycloak 在本 realm 下的 cookie 路径。 */
        const val AUTH_PATH = "/auth/realms/copilot/"

        /** 个人账号的企业 ID（模型列表接口的兜底取值，与 web 端一致）。 */
        const val ENTERPRISE_PERSONAL = "personal"

        private const val KEY_BASE_URL = "base_url"
        private const val KEY_LOGGED_IN = "logged_in"
        private const val KEY_ACCOUNT = "account_hint"
        private const val KEY_USER_AGENT = "user_agent"
        private const val KEY_ROUND = "round_screen"
        private const val KEY_SHAPE_CHOSEN = "shape_chosen"
        private const val KEY_MODEL = "selected_model"
        private const val KEY_ENTERPRISE = "enterprise_id"
    }
}
