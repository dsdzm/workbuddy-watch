package com.workbuddy.watch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.TimeText
import com.workbuddy.watch.ui.components.ToastBar
import com.workbuddy.watch.ui.components.WatchFrame
import com.workbuddy.watch.ui.components.swipeBack
import com.workbuddy.watch.ui.screens.AboutScreen
import com.workbuddy.watch.ui.screens.AskScreen
import com.workbuddy.watch.ui.screens.ConfirmScreen
import com.workbuddy.watch.ui.screens.ErrorScreen
import com.workbuddy.watch.ui.screens.HistoryDetailScreen
import com.workbuddy.watch.ui.screens.HomeScreen
import com.workbuddy.watch.ui.screens.ListScreen
import com.workbuddy.watch.ui.screens.LoginScreen
import com.workbuddy.watch.ui.screens.ModelScreen
import com.workbuddy.watch.ui.screens.OnboardScreen
import com.workbuddy.watch.ui.screens.ResultScreen
import com.workbuddy.watch.ui.screens.RunningScreen
import com.workbuddy.watch.ui.screens.SettingsScreen
import com.workbuddy.watch.ui.screens.UsageScreen
import com.workbuddy.watch.ui.screens.WebLoginScreen
import com.workbuddy.watch.ui.sheets.SheetHost
import com.workbuddy.watch.ui.theme.WorkBuddyWatchTheme
import com.workbuddy.watch.vm.Screen
import com.workbuddy.watch.vm.WatchViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WorkBuddyWatchTheme {
                WatchApp()
            }
        }
    }
}

@Composable
private fun WatchApp(vm: WatchViewModel = viewModel()) {
    val state by vm.ui.collectAsStateWithLifecycle()

    // 附件选择：选到文件后上传到云端，成功后进入待下发列表
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.uploadPicked(it) }
    }

    // 首次启动：必须先选定表盘形状，之后才进入主界面
    if (!state.shapeChosen) {
        WatchFrame(roundScreen = state.roundScreen) {
            OnboardScreen(
                roundScreen = state.roundScreen,
                version = BuildConfig.VERSION_NAME,
                onChoose = { vm.chooseScreenShape(it) }
            )
        }
        return
    }

    // 右滑返回：按当前页面给出对应的返回动作；HOME 无返回动作。
    val back: (() -> Unit)? = when (state.screen) {
        Screen.CONFIRM -> vm::cancelConfirm
        Screen.RUNNING, Screen.ASK, Screen.RESULT, Screen.LIST, Screen.ERROR -> vm::goHome
        Screen.HISTORY_DETAIL -> vm::closeHistory
        Screen.SETTINGS -> vm::closeSettings
        Screen.MODELS -> vm::closeModels
        Screen.USAGE -> vm::closeUsage
        Screen.ABOUT -> vm::closeAbout
        Screen.LOGIN -> vm::cancelLogin
        Screen.LOGIN_WEB -> vm::cancelWebLogin
        Screen.HOME -> null
    }
    // pointerInput 只绑定一次，必须通过 rememberUpdatedState 读到最新的返回动作
    val latestBack = rememberUpdatedState(back)

    AppScaffold(timeText = { TimeText() }) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .swipeBack { latestBack.value?.invoke() }
        ) {
            when (state.screen) {
                Screen.HOME -> HomeScreen(
                    roundScreen = state.roundScreen,
                    connected = state.connected,
                    cloudError = state.cloudError,
                    loggedIn = state.loggedIn,
                    accountHint = state.accountHint,
                    workingTask = state.workingTask,
                    unreadCount = state.unreadCount,
                    attachments = state.attachments,
                    uploading = state.uploading,
                    modelLabel = state.modelLabel,
                    onScene = vm::pickScene,
                    onCompose = vm::openCompose,
                    onPickFile = {
                        runCatching { pickFile.launch(arrayOf("*/*")) }
                            .onFailure { vm.pickerUnavailable() }
                    },
                    onRemoveAttachment = vm::removeAttachment,
                    onList = vm::openList,
                    onUsage = vm::openUsage,
                    onSettings = vm::openSettings,
                    onAbout = vm::openAbout,
                    onModels = { vm.openModels(Screen.HOME) },
                    onConn = vm::openConn,
                    onLogin = vm::openLogin,
                    onOpenTask = vm::openTask
                )

                Screen.CONFIRM -> ConfirmScreen(
                    roundScreen = state.roundScreen,
                    draft = state.draft,
                    envText = com.workbuddy.watch.ui.screens.envLabel(state.env),
                    busy = state.busy,
                    attachments = state.attachments,
                    onDraftChange = vm::setDraft,
                    onRemoveAttachment = vm::removeAttachment,
                    onSend = vm::send,
                    onBack = vm::cancelConfirm
                )

                Screen.RUNNING -> state.activeTask?.let {
                    RunningScreen(
                        roundScreen = state.roundScreen,
                        task = it,
                        onStop = vm::askStop,
                        onHome = vm::goHome,
                        onFollowup = vm::askFollowup
                    )
                } ?: Loading()

                Screen.ASK -> state.activeTask?.let {
                    AskScreen(
                        roundScreen = state.roundScreen,
                        task = it,
                        draft = state.draft,
                        busy = state.busy,
                        onDraftChange = vm::setDraft,
                        onAnswer = vm::answerAsk,
                        onStop = vm::askStop,
                        onHome = vm::goHome
                    )
                } ?: Loading()

                Screen.RESULT -> state.activeTask?.let {
                    ResultScreen(
                        roundScreen = state.roundScreen,
                        task = it,
                        downloading = state.downloading,
                        onDownload = vm::downloadArtifact,
                        onOpen = vm::openArtifact,
                        onFollowup = vm::askFollowup,
                        onDone = vm::goHome
                    )
                } ?: Loading()

                Screen.LIST -> ListScreen(
                    roundScreen = state.roundScreen,
                    tasks = state.tasks,
                    onOpen = vm::openHistory,
                    onHome = vm::goHome
                )

                Screen.HISTORY_DETAIL -> HistoryDetailScreen(
                    roundScreen = state.roundScreen,
                    title = state.historyTitle,
                    messages = state.history,
                    loading = state.historyLoading,
                    error = state.historyError,
                    busy = state.historyBusy,
                    draft = state.draft,
                    modelLabel = state.modelLabel,
                    models = state.models,
                    modelsLoading = state.modelsLoading,
                    modelsError = state.modelsError,
                    selectedModel = state.selectedModel,
                    onDraftChange = vm::setDraft,
                    onSend = vm::sendHistoryMessage,
                    onLoadModels = vm::loadModels,
                    onChooseModel = vm::chooseModelInline,
                    onReload = { state.activeTaskId?.let { vm.openHistory(it) } },
                    onBack = vm::closeHistory
                )

                Screen.ERROR -> ErrorScreen(
                    roundScreen = state.roundScreen,
                    task = state.activeTask,
                    onRetry = vm::retryActive,
                    onHome = vm::goHome
                )

                Screen.LOGIN -> LoginScreen(
                    roundScreen = state.roundScreen,
                    form = state.login,
                    onPhoneChange = vm::setLoginPhone,
                    onCodeChange = vm::setLoginCode,
                    onSendCode = vm::sendLoginCode,
                    onSubmit = vm::submitLogin,
                    onWebLogin = vm::openWebLogin,
                    onBack = vm::cancelLogin
                )

                Screen.LOGIN_WEB -> WebLoginScreen(
                    roundScreen = state.roundScreen,
                    startUrl = state.loginUrl,
                    onLoginFinished = vm::finishWebLogin,
                    onCancel = vm::cancelWebLogin
                )

                Screen.SETTINGS -> SettingsScreen(
                    roundScreen = state.roundScreen,
                    loggedIn = state.loggedIn,
                    accountHint = state.accountHint,
                    modelLabel = state.modelLabel,
                    envReport = state.envReport,
                    onLogin = vm::openLogin,
                    onLogout = vm::logout,
                    onToggleShape = vm::toggleScreenShape,
                    onModels = { vm.openModels(Screen.SETTINGS) },
                    onCheckEnv = vm::checkEnvironment,
                    onBack = vm::closeSettings
                )

                Screen.MODELS -> ModelScreen(
                    roundScreen = state.roundScreen,
                    models = state.models,
                    loading = state.modelsLoading,
                    error = state.modelsError,
                    selected = state.selectedModel,
                    onSelect = vm::chooseModel,
                    onReload = vm::loadModels,
                    onBack = vm::closeModels
                )

                Screen.ABOUT -> AboutScreen(
                    roundScreen = state.roundScreen,
                    version = BuildConfig.VERSION_NAME,
                    onBack = vm::closeAbout
                )

                Screen.USAGE -> UsageScreen(
                    roundScreen = state.roundScreen,
                    usage = state.usage,
                    loading = state.usageLoading,
                    error = state.usageError,
                    loggedIn = state.loggedIn,
                    onLogin = vm::openLogin,
                    onReload = vm::loadUsage,
                    onBack = vm::closeUsage
                )
            }

            state.toast?.let {
                ToastBar(
                    text = it,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                )
            }
        }
    }

    SheetHost(
        state = state,
        onClose = vm::closeSheet,
        onSetEnv = vm::setEnv,
        onSetConnected = vm::setConnected,
        onOpenSettings = vm::openSettings,
        onOpenLogin = vm::openLogin,
        onConfirmStop = vm::confirmStop,
        onQuickReply = vm::sendFollowup,
        onDraftChange = vm::setDraft
    )
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
