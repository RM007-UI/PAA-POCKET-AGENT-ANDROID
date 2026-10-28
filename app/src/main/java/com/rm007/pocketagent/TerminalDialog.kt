package com.rm007.pocketagent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.util.Log
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File

@Composable
internal fun PocketTerminalDialog(
    workingDirectory: File,
    homeDirectory: File,
    toolDirectory: File,
    onDismiss: () -> Unit,
    onOpenTools: () -> Unit
) {
    val context = LocalContext.current
    val controller = remember(
        workingDirectory.absolutePath,
        homeDirectory.absolutePath,
        toolDirectory.absolutePath
    ) {
        PocketTerminalController(context.applicationContext, workingDirectory, homeDirectory, toolDirectory)
    }
    var session by remember(controller) { mutableStateOf(controller.newSession()) }
    var terminalView by remember { mutableStateOf<TerminalView?>(null) }

    DisposableEffect(controller, session) {
        onDispose {
            controller.bindView(null)
            if (session.getEmulator() != null && session.isRunning()) {
                session.finishIfRunning()
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF070B08),
            shape = RoundedCornerShape(0.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize().imePadding()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF111A13))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "TERMINAL",
                            color = Color(0xFFB5F4C2),
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            workingDirectory.absolutePath,
                            color = Color(0xFF9BA99E),
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    TextButton(onClick = onOpenTools) {
                        Text("ابزارها", color = Color(0xFFB5F4C2), fontSize = 12.sp)
                    }
                    TextButton(onClick = onDismiss) {
                        Text("بستن", color = Color.White, fontSize = 12.sp)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .background(Color(0xFF0C120D))
                        .padding(horizontal = 6.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TerminalKey("Ctrl+C") { controller.send(session, byteArrayOf(3)) }
                    TerminalKey("Ctrl+D") { controller.send(session, byteArrayOf(4)) }
                    TerminalKey("Tab") { controller.send(session, byteArrayOf(9)) }
                    TerminalKey("Esc") { controller.send(session, byteArrayOf(27)) }
                    TerminalKey("↑") { controller.send(session, byteArrayOf(27, 91, 65)) }
                    TerminalKey("↓") { controller.send(session, byteArrayOf(27, 91, 66)) }
                    TerminalKey("←") { controller.send(session, byteArrayOf(27, 91, 68)) }
                    TerminalKey("→") { controller.send(session, byteArrayOf(27, 91, 67)) }
                    TerminalKey("Ctrl+L") { controller.send(session, byteArrayOf(12)) }
                    TerminalKey("پوسته تازه") {
                        if (session.getEmulator() != null && session.isRunning()) {
                            session.finishIfRunning()
                        }
                        session = controller.newSession()
                    }
                }

                AndroidView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color(0xFF070B08)),
                    factory = { viewContext ->
                        TerminalView(viewContext, null).apply {
                            setTerminalViewClient(controller)
                            setTypeface(Typeface.MONOSPACE)
                            setTextSize(14)
                            isFocusable = true
                            isFocusableInTouchMode = true
                            controller.bindView(this)
                            attachSession(session)
                        }
                    },
                    update = { view ->
                        controller.bindView(view)
                        terminalView = view
                        if (view.mTermSession !== session) {
                            view.attachSession(session)
                        }
                    }
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF111A13))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Android sh · PTY · ابزارهای برنامه در PATH",
                        modifier = Modifier.weight(1f),
                        color = Color(0xFF9BA99E),
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    TextButton(onClick = {
                        terminalView?.let { view ->
                            view.requestFocus()
                            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                            imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
                        }
                    }) {
                        Text("کیبورد", color = Color(0xFFB5F4C2), fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalKey(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.height(34.dp),
        shape = RoundedCornerShape(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp)
    ) {
        Text(label, color = Color(0xFFB5F4C2), fontSize = 11.sp, maxLines = 1)
    }
}

private class PocketTerminalController(
    private val context: Context,
    private val workingDirectory: File,
    private val homeDirectory: File,
    private val toolDirectory: File
) : TerminalSessionClient, TerminalViewClient {

    @Volatile
    private var terminalView: TerminalView? = null

    fun bindView(view: TerminalView?) {
        terminalView = view
    }

    fun newSession(): TerminalSession {
        if (!workingDirectory.exists()) workingDirectory.mkdirs()
        val env = EmbeddedTermuxRuntime.terminalEnvironment(context, toolDirectory)
        return TerminalSession(
            EmbeddedTermuxRuntime.bashFile(context).absolutePath,
            workingDirectory.absolutePath,
            arrayOf("--login", "-i"),
            env,
            6000,
            this
        ).apply {
            mSessionName = "Pocket Agent"
        }
    }

    fun send(session: TerminalSession, bytes: ByteArray) {
        if (session.isRunning()) session.write(bytes, 0, bytes.size)
    }

    override fun onTextChanged(changedSession: TerminalSession) {
        terminalView?.post { terminalView?.onScreenUpdated() }
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        terminalView?.post { terminalView?.onScreenUpdated() }
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        terminalView?.post { terminalView?.onScreenUpdated() }
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Terminal selection", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        if (!text.isNullOrEmpty()) send(session, text.toByteArray(Charsets.UTF_8))
    }

    override fun onBell(session: TerminalSession) = Unit

    override fun onColorsChanged(session: TerminalSession) {
        terminalView?.post { terminalView?.invalidate() }
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
        terminalView?.post { terminalView?.invalidate() }
    }

    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String, message: String) { Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, e.message ?: "Terminal error", e) }

    override fun onScale(scale: Float): Float = scale

    override fun onSingleTapUp(e: MotionEvent) {
        terminalView?.let { view ->
            view.requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: android.view.KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: android.view.KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false
    override fun readControlKey(): Boolean = false
    override fun readAltKey(): Boolean = false
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
    override fun onEmulatorSet() {
        terminalView?.post { terminalView?.onScreenUpdated() }
    }
}
