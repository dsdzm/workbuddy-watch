package com.workbuddy.watch.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

/**
 * 浏览器式 cookie 罐：手机号登录与云端接口共用同一份，持久化在本机。
 *
 * 为什么必须这样（均已用 curl 逐条实测确认）：
 *
 *  - 登录态由**多个** cookie 组成，缺一不可：`session` 与 `session_2` 是网关会话，
 *    **必须同时带上，只带其中一个就是 401**；`KEYCLOAK_SESSION` / `KEYCLOAK_IDENTITY`
 *    是 Keycloak 的 SSO 会话（有效期一年），正是它让登录能长期保持；
 *    `tgw_l7_route` 是网关的粘性路由 cookie，会随每个响应刷新。
 *  - 服务端会在不同 `Path`（`/`、`/console`）上写**同名** `session`，未登录时还会下发
 *    `Max-Age=0` 清空它。因此必须按 name + Path 分别保存、按请求路径下发，
 *    否则同名 cookie 会互相覆盖，或把 Keycloak 的域内 cookie 发到 console 接口上。
 *  - 网关会话过期后，网关**不会**自己跳登录页（带着失效的 `session` 访问入口只返回 200），
 *    必须先把 `session` / `session_2` 丢掉，入口才会重新走 OIDC 换新会话，
 *    见 [dropGatewaySession] 与 [NativeLogin.renew]。
 *
 * 过期处理：只认 `Max-Age`（服务端清空 cookie 时都会同时给 `Max-Age=0`），
 * 其余按会话级处理、不设过期——`Expires` 的日期格式各服务端写法不一，不值得解析。
 */
class CookieStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("workbuddy_watch", Context.MODE_PRIVATE)

    /** key = `name|path`，保证同名不同 Path 的 cookie 互不覆盖。 */
    private val entries = LinkedHashMap<String, Entry>()

    /**
     * @param expiresAt 绝对过期时间；[NO_EXPIRY] 表示会话级、不设过期。
     */
    private class Entry(val name: String, val value: String, val path: String, val expiresAt: Long)

    init {
        load()
    }

    /** 是否还持有任何 cookie（用于判断登录凭据是否真的还在本机）。 */
    fun hasAny(): Boolean = entries.values.any { it.value.isNotEmpty() }

    /** 组装 `Cookie` 头：只带路径匹配且未过期的，长路径优先（浏览器语义）。 */
    fun header(url: String): String? {
        val path = requestPath(url)
        val now = System.currentTimeMillis()
        val matched = entries.values
            .filter { it.value.isNotEmpty() && (it.expiresAt == NO_EXPIRY || it.expiresAt > now) && pathMatches(path, it.path) }
            .sortedByDescending { it.path.length }
        if (matched.isEmpty()) return null
        return matched.joinToString("; ") { "${it.name}=${it.value}" }
    }

    /** 吸收响应里的 `Set-Cookie`，让 `tgw_l7_route`、网关 `session` 像浏览器一样滚动刷新。 */
    fun absorb(headers: Map<String, List<String>>?, url: String) {
        if (headers == null) return
        val fallbackPath = defaultPath(requestPath(url))
        var changed = false

        headers.forEach { (key, values) ->
            if (!key.equals("Set-Cookie", ignoreCase = true)) return@forEach
            values.forEach { raw ->
                val parts = raw.split(';')
                val head = parts.firstOrNull()?.trim().orEmpty()
                val eq = head.indexOf('=')
                if (eq <= 0) return@forEach
                val name = head.substring(0, eq).trim()
                val value = head.substring(eq + 1).trim()

                val attrs = parts.drop(1).map { it.trim() }
                val path = attrs.firstOrNull { it.startsWith("Path=", ignoreCase = true) }
                    ?.substringAfter('=')?.trim()?.takeIf { it.startsWith("/") }
                    ?: fallbackPath
                val maxAge = attrs.firstOrNull { it.startsWith("Max-Age=", ignoreCase = true) }
                    ?.substringAfter('=')?.trim()?.toLongOrNull()
                val dead = value.isEmpty() || maxAge == 0L

                val entry = "$name|$path"
                if (dead) {
                    changed = entries.remove(entry) != null || changed
                } else {
                    val expiresAt = if (maxAge != null && maxAge > 0) System.currentTimeMillis() + maxAge * 1000 else NO_EXPIRY
                    val prev = entries.put(entry, Entry(name, value, path, expiresAt))
                    if (prev == null || prev.value != value || prev.expiresAt != expiresAt) changed = true
                }
            }
        }

        if (changed) save()
    }

    /**
     * 直接写入一条 cookie（同名同 Path 覆盖）。
     * 网页登录（WebView）成功后，[CloudSettings.adoptWebViewCookies] 把浏览器里的凭据搬进来时用。
     */
    fun put(name: String, value: String, path: String) {
        if (name.isBlank() || value.isEmpty()) return
        entries["$name|$path"] = Entry(name, value, path.ifBlank { "/" }, NO_EXPIRY)
        save()
    }

    /** 退出登录：整罐丢弃（含 Keycloak SSO，确保下次必须重新验证码登录）。 */
    fun clear() {
        entries.clear()
        prefs.edit().remove(KEY_COOKIES).apply()
    }

    /**
     * 丢掉网关自己的会话（`session` / `session_2`），保留 Keycloak 的 SSO cookie。
     *
     * 实测结论：网关**只有在完全没有这两个 cookie 时**才会把入口 302 到 Keycloak；
     * 带着一个已失效的 `session` 访问入口，它只返回 200 且不跳转，静默续期就永远做不成。
     * 丢掉它们之后，入口会重新走 OIDC，Keycloak 凭 SSO cookie 直接签发新的 code。
     */
    fun dropGatewaySession() {
        val keys = entries.keys.filter { it.substringBefore('|') in GATEWAY_COOKIES }
        if (keys.isEmpty()) return
        keys.forEach { entries.remove(it) }
        save()
    }

    // ---------- 内部：路径语义 ----------

    private fun requestPath(url: String): String =
        runCatching { URL(url).path }.getOrNull().orEmpty().ifBlank { "/" }

    /** 没有显式 Path 时按 RFC 6265 取请求路径的上一级目录。 */
    private fun defaultPath(requestPath: String): String {
        val slash = requestPath.lastIndexOf('/')
        return if (slash <= 0) "/" else requestPath.substring(0, slash)
    }

    private fun pathMatches(requestPath: String, cookiePath: String): Boolean = when {
        cookiePath == "/" -> true
        requestPath == cookiePath -> true
        cookiePath.endsWith("/") -> requestPath.startsWith(cookiePath)
        else -> requestPath.startsWith(cookiePath) && requestPath.getOrNull(cookiePath.length) == '/'
    }

    // ---------- 内部：持久化 ----------

    private fun load() {
        val raw = prefs.getString(KEY_COOKIES, null) ?: return
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val entry = Entry(
                    name = o.getString("n"),
                    value = o.getString("v"),
                    path = o.optString("p", "/"),
                    expiresAt = o.optLong("e", NO_EXPIRY)
                )
                entries["${entry.name}|${entry.path}"] = entry
            }
        }
    }

    private fun save() {
        val arr = JSONArray()
        entries.values.forEach { e ->
            arr.put(
                JSONObject()
                    .put("n", e.name)
                    .put("v", e.value)
                    .put("p", e.path)
                    .put("e", e.expiresAt)
            )
        }
        prefs.edit().putString(KEY_COOKIES, arr.toString()).apply()
    }

    private companion object {
        const val KEY_COOKIES = "cookies"
        const val NO_EXPIRY = 0L

        /** 网关自己签发的会话 cookie，需整体丢弃后入口才会重新走 OIDC。 */
        val GATEWAY_COOKIES = setOf("session", "session_2")
    }
}
