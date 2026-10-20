package com.workbuddy.watch.ui.screens

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.KeyboardType
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
import com.workbuddy.watch.vm.LoginForm

/**
 * 原生登录页（手机号 + 短信验证码），**不使用 WebView**。
 *
 * 所有网络请求由 [com.workbuddy.watch.data.NativeLogin] 在原生层完成：打开 Keycloak 授权表单、
 * 发送短信、提交验证码换会话，会话 cookie 直接写在本机偏好里。因此即便手表没有系统 WebView
 * 也能正常登录。
 */
@Composable
fun LoginScreen(
    roundScreen: Boolean,
    form: LoginForm,
    onPhoneChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onSendCode: () -> Unit,
    onSubmit: () -> Unit,
    onWebLogin: () -> Unit,
    onBack: () -> Unit
) {
    val canSend = !form.sending && form.countdown == 0 && form.phone.length == 11
    val canSubmit = !form.submitting && form.code.isNotBlank()

    WatchColumn(roundScreen) {
        PageHeader("登录 WorkBuddy", onBack)

        InfoPill {
            Text(
                "用手机号 + 验证码登录，凭据只保存在手表。",
                style = MaterialTheme.typography.labelSmall,
                color = WatchPalette.OnPillVariant
            )
        }

        SectionLabel("手机号")
        PillTextField(
            value = form.phone,
            onValueChange = onPhoneChange,
            enabled = !form.submitting,
            placeholder = "11 位手机号",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
        )

        MutedPillButton(
            text = when {
                form.sending -> "发送中…"
                form.countdown > 0 -> "${form.countdown}s 后重发"
                else -> "获取验证码"
            },
            enabled = canSend || form.sending,
            onClick = onSendCode
        )

        SectionLabel("短信验证码")
        PillTextField(
            value = form.code,
            onValueChange = onCodeChange,
            enabled = !form.submitting,
            placeholder = "短信里的数字验证码",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
        )

        form.error?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.labelSmall,
                color = WatchPalette.Error
            )
        }

        AccentPillButton(
            text = if (form.submitting) "登录中…" else "登录",
            enabled = canSubmit,
            onClick = onSubmit
        )

        InfoPill {
            Text(
                "收不到验证码？本机原生请求会被风控拦截，请改用浏览器登录。",
                style = MaterialTheme.typography.labelSmall,
                color = WatchPalette.OnPillVariant
            )
        }
        MutedPillButton(
            text = "改用浏览器登录",
            enabled = !form.submitting && !form.sending,
            onClick = onWebLogin
        )
    }
}
