package com.workbuddy.watch.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.workbuddy.watch.ui.components.AccentPillButton
import com.workbuddy.watch.ui.components.InfoPill
import com.workbuddy.watch.ui.components.MutedPillButton
import com.workbuddy.watch.ui.components.PageHeader
import com.workbuddy.watch.ui.components.PillTextField
import com.workbuddy.watch.ui.components.SectionLabel
import com.workbuddy.watch.ui.components.WatchColumn
import com.workbuddy.watch.ui.theme.WatchPalette
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 网页登录页：在系统浏览器里打开**官方登录页**取验证码，本页只负责兜底与说明。
 *
 * 入口地址由 [com.workbuddy.watch.data.CloudSettings.loginPageUrl] 给出，即官方那条能真正
 * 收到短信的地址：
 *
 *     {base}/login/?platform=agents&state=0&redirect_uri={base}/app
 *
 * 之前用的 `/console/accounts` 会被 302 到老的 Keycloak 表单（`client_id=console`），
 * 它的发码接口是**死桩**：回 `200 {"expires_in":60}` 却从不真的下发短信——这就是
 * 「本 App 收不到验证码、手表浏览器却能收到」的真正原因（与内核 / 指纹无关）。
 *
 * 因此：
 *  - ① 「用浏览器获取验证码」按钮打开的是上面的官方地址，短信能正常到；
 *  - ② 下方仍保留「本机内核发码 / 提交」作为备用，但官方 SPA 与旧内核不兼容
 *    （脚本用了可选链，< Chrome 80 直接 SyntaxError），所以主路径请用**原生登录**
 *    （[com.workbuddy.watch.data.NativeLogin]，打的是同一条 auth-v2 接口，不依赖浏览器）。
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun WebLoginScreen(
    roundScreen: Boolean,
    startUrl: String,
    onLoginFinished: (WebView) -> Unit,
    onCancel: () -> Unit
) {
    val base = remember(startUrl) { originOf(startUrl) }

    var status by remember { mutableStateOf("正在打开官方登录页…") }
    var ready by remember { mutableStateOf(false) }
    var jsDisabled by remember { mutableStateOf(false) }
    var action by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var countdown by remember { mutableStateOf(0) }
    var submitting by remember { mutableStateOf(false) }
    var reported by remember { mutableStateOf(false) }
    /** 见过 Keycloak 授权页 / 网关回调，说明这次确实走了登录流程。 */
    var sawLoginFlow by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }

    val main = remember { Handler(Looper.getMainLooper()) }
    val ctx = LocalContext.current

    /** ① 发码交给外部浏览器：只有它的引擎能通过网关风控、真的触发短信下发。 */
    fun openBrowserForCode() {
        val ok = runCatching {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(startUrl))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
        status = if (ok) {
            "已在浏览器打开官方登录页：在那点「获取验证码」，收到短信后回本页填号码和验证码"
        } else {
            "本机没有可用的浏览器，只能用下面「本机内核发码」碰运气"
        }
    }

    /** 记录跳转痕迹。 */
    fun noteUrl(url: String?) {
        val u = url.orEmpty()
        if (u.contains("/auth/") || u.contains("/login") || u.contains(APISIX_REDIRECT)) sawLoginFlow = true
    }

    /** 已经落到回跳地址（网关已回跳并种好会话）→ 收工。 */
    fun finishIfLanded(url: String?) {
        if (reported) return
        val u = url.orEmpty()
        if (sawLoginFlow && u.startsWith("$base$CALLBACK_PATH") && !u.contains(APISIX_REDIRECT)) {
            reported = true
            web?.stopLoading()
            web?.let(onLoginFinished)
        }
    }

    /**
     * 自检 + 读表单：返回 JS 是否可用、登录表单是否已在 DOM 里、表单 action。
     * JS 不可用时 evaluateJavascript 回调拿不到对象，这里会按「内核不支持」提示。
     */
    fun probe(view: WebView) {
        view.evaluateJavascript(JS_PROBE) { raw ->
            if (reported) return@evaluateJavascript
            // 回调拿不到值 = 这颗内核里 JS 根本没跑起来
            if (raw.isNullOrBlank() || raw == "null") {
                jsDisabled = true
                ready = false
                status = "这颗手表内核没有启用 JavaScript，无法用浏览器发码；请改用原生登录或外部浏览器。"
                return@evaluateJavascript
            }
            val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return@evaluateJavascript
            val hasForm = obj.optInt("form", 0) == 1
            val act = obj.optString("action")
            val url = obj.optString("url")
            if (hasForm && act.isNotBlank()) {
                action = act
                ready = true
                jsDisabled = false
                // 提交后又回到登录表单 = 这次提交没成功（验证码不对之类）
                if (submitting) {
                    submitting = false
                    status = "登录未成功，请检查验证码后重试"
                } else {
                    status = "浏览器已就绪，填手机号后点「获取验证码」"
                }
            } else if (url.contains(AUTH_PATH)) {
                // 发码不依赖表单，只要接上了 Keycloak 会话就允许发（提交仍要等拿到 action）
                ready = true
                status = "已接上 Keycloak 会话，可直接发码（页面自身脚本与内核不兼容）"
            }
        }
    }

    /** 发码结果回调（由 [JsBridge] 抛回主线程）。 */
    fun onJsResult(payload: String) {
        if (payload.startsWith(OK_PREFIX)) {
            val seconds = payload.removePrefix(OK_PREFIX).toIntOrNull() ?: 60
            sending = false
            countdown = seconds.coerceIn(1, 120)
            status = "验证码已发送（由浏览器内核发出），请查收短信"
        } else {
            sending = false
            // 服务端按手机号限频，失败后压 60 秒退避，避免把额度烧光
            countdown = 60
            status = "发送失败：" + payload.removePrefix(ERR_PREFIX).ifBlank { "请稍后重试" }
        }
    }

    /** 发码：注入 ES5 XHR，结果经 [JsBridge] 回主线程。 */
    fun sendCode() {
        val view = web ?: return
        if (sending || countdown > 0 || phone.length != 11) return
        val url = SMS_PATH + "?phoneNumber=" +
            URLEncoder.encode("+86" + phone.filter { it.isDigit() }, "UTF-8")
        sending = true
        status = "正在请浏览器内核发送验证码…"
        view.evaluateJavascript(jsSendCode(url), null)
    }

    /** 提交验证码：动态 form POST，让浏览器自己跟随 302 去换会话 cookie。 */
    fun submitCode() {
        val view = web ?: return
        if (submitting) return
        if (action.isBlank()) {
            status = "登录页已失效，请返回后重试"
            return
        }
        submitting = true
        status = "正在提交验证码…"
        view.evaluateJavascript(jsSubmit(action, "+86" + phone.filter { it.isDigit() }, code.trim()), null)
    }

    // 重发倒计时
    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1000)
            countdown -= 1
        }
    }

    WatchColumn(roundScreen) {
        PageHeader("浏览器发码登录", onCancel)

        InfoPill {
            Text(
                status,
                style = MaterialTheme.typography.labelSmall,
                color = WatchPalette.OnPillVariant
            )
        }

        // ① 受信任的一步：外部浏览器的引擎才过得去网关风控
        AccentPillButton(text = "① 用浏览器获取验证码", onClick = ::openBrowserForCode)
        InfoPill {
            Text(
                "在浏览器里点「获取验证码」（别在浏览器里提交登录），收到短信后回本页，填同样的手机号和验证码，点下面「② 登录」。",
                style = MaterialTheme.typography.labelSmall,
                color = WatchPalette.OnPillVariant
            )
        }

        SectionLabel("手机号")
        PillTextField(
            value = phone,
            onValueChange = { v -> phone = v.filter { c -> c.isDigit() }.take(11) },
            enabled = !submitting,
            placeholder = "11 位手机号",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
        )

        MutedPillButton(
            text = when {
                sending -> "发送中…"
                countdown > 0 -> "${countdown}s 后重发"
                else -> "本机内核发码（备用）"
            },
            enabled = ready && !sending && countdown == 0 && phone.length == 11,
            onClick = ::sendCode
        )

        SectionLabel("短信验证码")
        PillTextField(
            value = code,
            onValueChange = { v -> code = v.filter { c -> c.isDigit() }.take(6) },
            enabled = !submitting,
            placeholder = "短信里的数字验证码",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
        )

        AccentPillButton(
            text = if (submitting) "登录中…" else "② 登录",
            enabled = action.isNotBlank() && !submitting && code.isNotBlank(),
            onClick = ::submitCode
        )

        if (jsDisabled) {
            InfoPill(
                container = WatchPalette.ErrorContainer,
                contentColor = WatchPalette.OnErrorContainer
            ) {
                Text(
                    "该内核不支持注入发码脚本。可点下方按钮用外部浏览器验证码登录，或返回用原生登录。",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        // WebView 只做同源握手与读表单 action，不展示（1dp 即可，仍会被测量并执行 JS）
        AndroidView(
            modifier = Modifier.size(1.dp),
            factory = { ctx ->
                val view = WebView(ctx)
                view.settings.javaScriptEnabled = true
                view.settings.domStorageEnabled = true

                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    // 微信 / QQ / OneID 这些 broker 会跳到第三方域，需要放开三方 cookie
                    setAcceptThirdPartyCookies(view, true)
                }

                view.addJavascriptInterface(
                    JsBridge { payload -> main.post { onJsResult(payload) } },
                    JS_BRIDGE
                )

                view.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        v: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        noteUrl(request.url?.toString())
                        return false
                    }

                    @Deprecated("兼容旧内核")
                    override fun shouldOverrideUrlLoading(v: WebView, url: String?): Boolean {
                        noteUrl(url)
                        return false
                    }

                    override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                        noteUrl(url)
                    }

                    override fun onPageFinished(v: WebView, url: String?) {
                        noteUrl(url)
                        finishIfLanded(url)
                        probe(v)
                    }
                }

                view.loadUrl(startUrl)
                web = view
                view
            }
        )

        MutedPillButton(text = "已经登录好了，进入 App", onClick = { web?.let(onLoginFinished) })
    }

    // WebView 不在组合里后必须销毁，否则会泄漏（它持有 Activity 与线程）
    DisposableEffect(Unit) {
        onDispose { runCatching { web?.stopLoading(); web?.destroy() } }
    }
}

/** 注入到网页里的回调桥：JS 用 `WB.post(...)` 把结果抛回原生。 */
private class JsBridge(private val onPost: (String) -> Unit) {
    @JavascriptInterface
    fun post(payload: String) = onPost(payload)
}

/** 登录完成后回跳的地址；落到它就是登录成功。 */
private const val CALLBACK_PATH = "/app"

/** 取 `scheme://host[:port]`，用于判断「是否已回跳到本站」。 */
private fun originOf(url: String): String = runCatching {
    val u = java.net.URL(url)
    buildString {
        append(u.protocol).append("://").append(u.host)
        if (u.port > 0) append(':').append(u.port)
    }
}.getOrDefault(url.substringBefore("/login/").trimEnd('/'))

/** 网关用 code 换令牌并种下登录态 cookie 的那一跳。 */
private const val APISIX_REDIRECT = "/.apisix/redirect"

/** Keycloak 授权页路径：用来判断 WebView 是不是已经停在登录页上。 */
private const val AUTH_PATH = "/auth/realms/copilot/"

/** 发码接口（与官方登录页内联脚本里的 axios.get 同一条）。 */
private const val SMS_PATH = "/auth/realms/copilot/sms/authentication-code"

/** JS 回调桥名字，注入脚本里用 `WB.post(...)`。 */
private const val JS_BRIDGE = "WB"

private const val OK_PREFIX = "ok:"
private const val ERR_PREFIX = "err:"

/**
 * 自检 + 读表单。返回对象，`evaluateJavascript` 会把它序列化成 JSON。
 * 全部用 ES5 写法（var / function / 字符串拼接），老内核也能解析。
 */
private const val JS_PROBE = """
(function(){
  var r = {js: 1, form: 0, action: '', url: location.href};
  try {
    var f = document.getElementById('kc-form-login');
    if (f) { r.form = 1; r.action = f.getAttribute('action') || ''; }
  } catch (e) { r.err = String(e); }
  return r;
})()
"""

/** 同源 XHR 发码；结果用字符串前缀回抛，避免再依赖 JSON 解析。 */
private fun jsSendCode(url: String): String = """
(function(){
  var xhr = new XMLHttpRequest();
  xhr.open('GET', '$url', true);
  xhr.setRequestHeader('Accept', 'application/json, text/plain, */*');
  xhr.onreadystatechange = function(){
    if (xhr.readyState !== 4) return;
    var ok = xhr.status >= 200 && xhr.status < 300;
    var msg = '';
    try {
      var d = JSON.parse(xhr.responseText);
      msg = ok ? String(d.expires_in) : String(d.error || d.msg || '');
    } catch (e) {
      msg = (xhr.responseText || ('HTTP ' + xhr.status)).substring(0, 150);
    }
    if (window.$JS_BRIDGE && $JS_BRIDGE.post) {
      $JS_BRIDGE.post((ok ? '$OK_PREFIX' : '$ERR_PREFIX') + msg);
    }
  };
  xhr.send();
  return 1;
})()
"""

/** 动态 form POST 登录；浏览器会自己跟随 302 完成换会话，无需我们解析跳转。 */
private fun jsSubmit(action: String, phoneNumber: String, code: String): String = """
(function(){
  var post = document.createElement('form');
  post.method = 'POST';
  post.action = '$action';
  var fields = [
    ['phoneActivated', 'true'],
    ['phoneNumber', '$phoneNumber'],
    ['code', '$code'],
    ['credentialId', ''],
    ['login', '登录']
  ];
  for (var i = 0; i < fields.length; i++) {
    var el = document.createElement('input');
    el.type = 'hidden';
    el.name = fields[i][0];
    el.value = fields[i][1];
    post.appendChild(el);
  }
  document.body.appendChild(post);
  post.submit();
  return 1;
})()
"""
