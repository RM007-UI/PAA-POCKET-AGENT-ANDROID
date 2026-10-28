package com.rm007.pocketagent

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PocketAgentApp() }
    }
}

private fun isPocketAccessibilityEnabled(context: Context): Boolean {
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ).orEmpty()
    val expected = ComponentName(context, PhoneAccessibilityService::class.java).flattenToString()
    return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
}

private data class UiMessage(val role: String, val text: String)
private data class PendingCommand(val action: AgentCommandAction, val decision: CompletableDeferred<Boolean>)

private val PocketColors = darkColorScheme(
    primary = Color(0xFFA7F3D0),
    onPrimary = Color(0xFF052E25),
    secondary = Color(0xFF9CA3AF),
    background = Color(0xFF0A0A0A),
    surface = Color(0xFF151515),
    surfaceVariant = Color(0xFF202020),
    onBackground = Color(0xFFF5F5F5),
    onSurface = Color(0xFFF5F5F5),
    error = Color(0xFFFCA5A5)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PocketAgentApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chatStore = remember { ChatStore(context.applicationContext) }
    val terminalTools = remember { TerminalTools(context.applicationContext) }
    var currentChatId by remember { mutableStateOf(chatStore.currentChatId()) }
    val agent = remember { AgentEngine(context.applicationContext, chatStore).apply { selectChat(chatStore.currentChatId()) } }
    val securePrefs = remember { SecurePrefs(context.applicationContext) }

    var messages by remember(currentChatId) {
        val saved = chatStore.loadMessages(currentChatId).map { UiMessage(it.role, it.text) }
        mutableStateOf(saved.ifEmpty {
            listOf(UiMessage("assistant", "سلام! من Pocket Agent هستم. گفتگو و حافظهٔ مدل این چت به‌صورت جداگانه ذخیره می‌شود؛ حافظهٔ کلی هم بین همهٔ چت‌ها مشترک است."))
        })
    }
    var chatList by remember { mutableStateOf(chatStore.listChats()) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showChats by remember { mutableStateOf(false) }
    var showToolbox by remember { mutableStateOf(false) }
    var showEmbeddedTerminal by remember { mutableStateOf(false) }
    var terminalWorkingDirectory by remember { mutableStateOf<File?>(null) }
    var showMemory by remember { mutableStateOf(false) }
    var showChanges by remember { mutableStateOf(false) }
    var showApplyConfirm by remember { mutableStateOf(false) }
    var showDiscardConfirm by remember { mutableStateOf(false) }
    var showPushConfirm by remember { mutableStateOf(false) }
    var pendingCommand by remember { mutableStateOf<PendingCommand?>(null) }
    var selectedDiff by remember { mutableStateOf<WorkspaceManager.Change?>(null) }
    var selectedDiffIsGitHub by remember { mutableStateOf(false) }
    var changeList by remember { mutableStateOf(emptyList<WorkspaceManager.Change>()) }
    var githubChangeList by remember { mutableStateOf(emptyList<WorkspaceManager.Change>()) }
    var rootStatus by remember { mutableStateOf("روت بررسی نشده") }
    var accessibilityEnabled by remember { mutableStateOf(isPocketAccessibilityEnabled(context)) }
    var showAccessSetup by remember { mutableStateOf(!isPocketAccessibilityEnabled(context)) }
    var projectVersion by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()
    var permissionGate by remember { mutableStateOf<CompletableDeferred<Map<String, Boolean>>?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permissionGate?.complete(result)
        permissionGate = null
    }

    LaunchedEffect(currentChatId, messages) {
        val stored = messages.map { StoredMessage(it.role, it.text) }
        withContext(Dispatchers.IO) {
            chatStore.saveMessages(currentChatId, stored)
            chatStore.listChats()
        }.also { chatList = it }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                messages = messages + UiMessage("system", "نگهداری مجوز پوشه ممکن نشد: ${e.message ?: "خطای نامشخص"}")
            }
            busy = true
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) { agent.workspace.importWorkspace(uri) }
                    agent.resetConversation()
                    changeList = emptyList()
                    githubChangeList = emptyList()
                    selectedDiff = null
                    showChanges = false
                    messages = messages + UiMessage("system", "$result\nزمینهٔ مدل برای پروژهٔ تازه شروع شد تا با پروژهٔ قبلی مخلوط نشود.")
                    projectVersion++
                } catch (e: Exception) {
                    messages = messages + UiMessage("system", "انتخاب پروژه ناموفق بود: ${e.message ?: e.javaClass.simpleName}")
                } finally { busy = false }
            }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    fun checkRoot() {
        if (busy) return
        busy = true
        rootStatus = "در حال بررسی…"
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { ShellRunner.execute("id", null, root = true, timeoutSeconds = 12) }
                rootStatus = if (result.contains("uid=0(root)") || result.contains("uid=0")) "روت در دسترس است" else "روت تأیید نشد"
                messages = messages + UiMessage("system", "بررسی روت:\n$result")
            } catch (e: Exception) {
                rootStatus = "بررسی روت ناموفق بود"
                messages = messages + UiMessage("system", "بررسی روت ناموفق بود: ${e.message ?: e.javaClass.simpleName}")
            } finally { busy = false }
        }
    }

    fun refreshChanges(open: Boolean = true) {
        scope.launch {
            try {
                val lists = withContext(Dispatchers.IO) {
                    agent.workspace.changes() to agent.workspace.githubChanges()
                }
                changeList = lists.first
                githubChangeList = lists.second
            } catch (e: Exception) {
                messages = messages + UiMessage("system", "به‌روزرسانی فهرست تغییرات ناموفق بود: ${e.message ?: e.javaClass.simpleName}")
            }
            if (open) showChanges = true
        }
    }

    fun openEmbeddedTerminal() {
        if (busy) return
        val projectDirectory = agent.workspace.workDirectory()
        busy = true
        scope.launch {
            try {
                val wasInstalled = withContext(Dispatchers.IO) {
                    val ready = EmbeddedTermuxRuntime.isInstalled(context)
                    EmbeddedTermuxRuntime.ensureInstalled(context)
                    ready
                }
                terminalWorkingDirectory = projectDirectory?.takeIf { it.isDirectory }
                    ?: EmbeddedTermuxRuntime.homeDirectory(context)
                showEmbeddedTerminal = true
                if (!wasInstalled) {
                    messages = messages + UiMessage(
                        "system",
                        "محیط ترمینال داخلی PAA راه‌اندازی شد؛ نصب اولیه انجام شد و از این به بعد بدون برنامهٔ جداگانه اجرا می‌شود."
                    )
                }
            } catch (e: Exception) {
                messages = messages + UiMessage(
                    "system",
                    "راه‌اندازی ترمینال داخلی ناموفق بود: ${e.message ?: e.javaClass.simpleName}"
                )
            } finally {
                busy = false
            }
        }
    }

    fun pushToGitHub() {
        if (busy) return
        if (!agent.workspace.isReady()) {
            messages = messages + UiMessage("system", "اول پوشهٔ پروژه را انتخاب کن.")
            return
        }
        if (securePrefs.githubRepo().isBlank() || !securePrefs.hasGithubToken()) {
            showChanges = false
            showSettings = true
            messages = messages + UiMessage("system", "برای همگام‌سازی، نام مخزن و توکن GitHub را در تنظیمات وارد کن.")
            return
        }
        scope.launch {
            try {
                githubChangeList = withContext(Dispatchers.IO) { agent.workspace.githubChanges() }
                if (githubChangeList.isEmpty()) {
                    messages = messages + UiMessage("system", "تغییر تازه‌ای برای ارسال به GitHub وجود ندارد.")
                } else {
                    showPushConfirm = true
                }
            } catch (e: Exception) {
                messages = messages + UiMessage("system", "خواندن تغییرات GitHub ناموفق بود: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun sendMessage() {
        val prompt = input.trim()
        if (prompt.isEmpty() || busy) return
        input = ""
        chatStore.updateTitleFromFirstMessage(currentChatId, prompt)
        chatList = chatStore.listChats()
        messages = messages + UiMessage("user", prompt)
        busy = true
        scope.launch {
            try {
                val answer = agent.runTask(
                    prompt,
                    confirmCommand = { action ->
                        val gate = CompletableDeferred<Boolean>()
                        val request = PendingCommand(action, gate)
                        withContext(Dispatchers.Main.immediate) { pendingCommand = request }
                        val approved = gate.await()
                        withContext(Dispatchers.Main.immediate) {
                            if (pendingCommand?.decision === gate) pendingCommand = null
                        }
                        if (!approved) {
                            "کاربر اجرای فرمان را رد کرد؛ این فرمان اجرا نشد."
                        } else {
                            withContext(Dispatchers.IO) {
                                when (action.name) {
                                    "install_terminal_script" -> terminalTools.installScriptPayload(action.payload ?: "{}")
                                    "download_terminal_binary" -> terminalTools.downloadBinaryPayload(action.payload ?: "{}")
                                    else -> {
                                        val workDirectory = agent.workspace.workDirectory()
                                        if (action.requiresRoot) {
                                            ShellRunner.execute(
                                                command = action.command,
                                                workingDirectory = workDirectory,
                                                root = true,
                                                timeoutSeconds = 60,
                                                extraBinDirectory = terminalTools.binDirectory()
                                            )
                                        } else {
                                            EmbeddedTermuxRuntime.execute(
                                                context = context,
                                                command = action.command,
                                                workingDirectory = workDirectory,
                                                timeoutSeconds = 120,
                                                extraBinDirectory = terminalTools.binDirectory()
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    },
                    requestAndroidAccess = { type, name ->
                        if (type == "runtime") {
                            val requested = runtimePermissions(name)
                            if (requested.isEmpty()) {
                                if (name == "notifications" && Build.VERSION.SDK_INT < 33) "این نسخهٔ Android مجوز زمان اجرای اعلان‌ها نمی‌خواهد."
                                else "نوع مجوز شناخته‌شده نیست یا در این نسخه پشتیبانی نمی‌شود: $name"
                            } else {
                                val missing = requested.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                                if (missing.isEmpty()) {
                                    "دسترسی «$name» از قبل فعال است."
                                } else {
                                    val gate = CompletableDeferred<Map<String, Boolean>>()
                                    permissionGate = gate
                                    permissionLauncher.launch(missing.toTypedArray())
                                    val results = gate.await()
                                    val grantedCount = requested.count {
                                        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED || results[it] == true
                                    }
                                    val ok = if (name == "location") grantedCount > 0 else grantedCount == requested.size
                                    if (ok) "اندروید دسترسی «$name» را اعطا کرد."
                                    else "اندروید دسترسی «$name» را نداد. می‌توانی بعداً از تنظیمات برنامه آن را تغییر بدهی."
                                }
                            }
                        } else {
                            openSpecialAccessSettings(context, name)
                        }
                    },
                    onEvent = { role, text ->
                        withContext(Dispatchers.Main.immediate) {
                            messages = messages + UiMessage(role, text)
                        }
                    }
                )
                messages = messages + UiMessage("assistant", answer)
                refreshChanges(open = false)
            } catch (e: Exception) {
                messages = messages + UiMessage("system", "ایجنت نتوانست کار را کامل کند: ${e.message ?: e.javaClass.simpleName}")
            } finally { busy = false }
        }
    }

    MaterialTheme(colorScheme = PocketColors) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text("Pocket Agent", fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
                                Text(
                                    if (agent.workspace.isReady()) "${agent.workspace.displayName()} · $rootStatus" else "${agent.workspace.displayName()} · $rootStatus",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.secondary,
                                    maxLines = 1
                                )
                            }
                        },
                        actions = {
                            IconButton(onClick = { showChats = true; chatList = chatStore.listChats() }, enabled = !busy) {
                                Icon(Icons.Filled.History, contentDescription = "گفتگوها")
                            }
                            IconButton(onClick = { openEmbeddedTerminal() }, enabled = !busy) {
                                Icon(Icons.Filled.Terminal, contentDescription = "ترمینال داخلی PAA")
                            }
                            IconButton(onClick = { showToolbox = true }, enabled = !busy) {
                                Icon(Icons.Filled.Build, contentDescription = "ابزارهای ترمینال")
                            }
                            IconButton(onClick = { showMemory = true }, enabled = !busy) {
                                Icon(Icons.Filled.Book, contentDescription = "حافظهٔ کلی")
                            }
                            IconButton(onClick = { folderPicker.launch(null) }, enabled = !busy) {
                                Icon(Icons.Filled.FolderOpen, contentDescription = "انتخاب پروژه")
                            }
                            IconButton(onClick = { refreshChanges() }, enabled = !busy) {
                                Icon(Icons.Filled.Code, contentDescription = "بازبینی تغییرات")
                            }
                            IconButton(onClick = { showSettings = true }, enabled = !busy) {
                                Icon(Icons.Filled.Settings, contentDescription = "تنظیمات")
                            }
                        }
                    )
                },
                bottomBar = {
                    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    if (agent.workspace.isReady()) "ویرایش‌ها داخل کپی کاری‌اند؛ فایل اصلی امن می‌ماند تا اعمال کنی." else "برای کار با سورس، ابتدا یک پوشهٔ پروژه انتخاب کن.",
                                    modifier = Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.secondary,
                                    fontSize = 10.sp
                                )
                                TextButton(onClick = {
                                    currentChatId = chatStore.createChat()
                                    agent.selectChat(currentChatId)
                                    chatList = chatStore.listChats()
                                    input = ""
                                }, enabled = !busy) {
                                    Icon(Icons.Filled.History, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Text("چت جدید", fontSize = 10.sp)
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = input,
                                    onValueChange = { input = it },
                                    modifier = Modifier.weight(1f),
                                    placeholder = { Text("مثلاً: سورس را بررسی کن و خطاها را درست کن…") },
                                    enabled = !busy,
                                    maxLines = 5,
                                    shape = RoundedCornerShape(22.dp)
                                )
                                IconButton(onClick = { sendMessage() }, enabled = !busy && input.isNotBlank()) {
                                    if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    else Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "ارسال")
                                }
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(color = Color(0xFF17231E), shape = RoundedCornerShape(11.dp), modifier = Modifier.weight(1f)) {
                            Text(
                                "خروجی فایل و ترمینال ممکن است برای تحلیل به مدل ارسال شود. روت و فرمان‌ها همیشه تأیید می‌خواهند.",
                                modifier = Modifier.padding(9.dp),
                                fontSize = 11.sp,
                                color = Color(0xFFD1FAE5)
                            )
                        }
                        OutlinedButton(onClick = { checkRoot() }, enabled = !busy, contentPadding = PaddingValues(horizontal = 9.dp, vertical = 6.dp)) {
                            Text("روت", fontSize = 11.sp)
                        }
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        items(messages) { message -> MessageBubble(message) }
                        if (busy) {
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Text("ایجنت در حال بررسی، ویرایش یا اجرای ابزار است…", color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showChats) {
            AlertDialog(
                onDismissRequest = { showChats = false },
                title = { Text("گفتگوها") },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("هر چت تاریخچه و حافظهٔ مدل جداگانه دارد. حافظهٔ کلی از همهٔ چت‌ها مشترک است.", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                        chatList.forEach { chat ->
                            TextButton(
                                onClick = {
                                    agent.selectChat(chat.id)
                                    currentChatId = chat.id
                                    showChats = false
                                    input = ""
                                },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(chat.title, color = if (chat.id == currentChatId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                    Text(if (chat.id == currentChatId) "چت فعلی" else "باز کردن گفتگو", fontSize = 10.sp, color = MaterialTheme.colorScheme.secondary)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        currentChatId = chatStore.createChat()
                        agent.selectChat(currentChatId)
                        chatList = chatStore.listChats()
                        showChats = false
                        input = ""
                    }) { Text("چت جدید") }
                },
                dismissButton = { TextButton(onClick = { showChats = false }) { Text("بستن") } }
            )
        }

        if (showToolbox) {
            TerminalToolsDialog(
                homePath = chatStore.homeDirectory.absolutePath,
                binPath = terminalTools.binDirectory().absolutePath,
                installedTools = terminalTools.listTools(),
                onDismiss = { showToolbox = false }
            )
        }

        if (showEmbeddedTerminal) {
            PocketTerminalDialog(
                workingDirectory = terminalWorkingDirectory
                    ?: EmbeddedTermuxRuntime.homeDirectory(context),
                homeDirectory = EmbeddedTermuxRuntime.homeDirectory(context),
                toolDirectory = terminalTools.binDirectory(),
                onDismiss = { showEmbeddedTerminal = false },
                onOpenTools = {
                    showEmbeddedTerminal = false
                    showToolbox = true
                }
            )
        }

        if (showMemory) {
            GlobalMemoryDialog(
                initialValue = chatStore.readGlobalMemory(),
                onDismiss = { showMemory = false },
                onSave = { value ->
                    try {
                        chatStore.replaceGlobalMemory(value)
                        showMemory = false
                        messages = messages + UiMessage("system", "حافظهٔ کلی ذخیره شد؛ در درخواست‌های بعدی به همهٔ گفتگوها داده می‌شود.")
                    } catch (e: Exception) {
                        messages = messages + UiMessage("system", "ذخیرهٔ حافظه ناموفق بود: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
            )
        }

        if (showAccessSetup) {
            AlertDialog(
                onDismissRequest = { /* Required device access is intentionally not silently skipped. */ },
                title = { Text("راه‌اندازی دسترسی گوشی") },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            if (accessibilityEnabled) "سرویس کنترل صفحه فعال است."
                            else "برای اینکه ایجنت بتواند در برنامه‌های دیگر صفحه را بخواند، لمس کند، پیمایش کند و متن وارد کند، باید سرویس دسترس‌پذیری را خودت در تنظیمات اندروید فعال کنی."
                        )
                        Text("۱. روی «باز کردن تنظیمات» بزن.\n۲. Pocket Agent — کنترل گوشی را پیدا کن.\n۳. سرویس را روشن و پیام هشدار اندروید را تأیید کن.\n۴. به برنامه برگرد و «بررسی دوباره» را بزن.")
                        Text(
                            "اندروید اجازه نمی‌دهد برنامه این مجوز ویژه را خودش بی‌صدا فعال کند. دسترسی روت هم فقط در دستگاه روت‌شده و با تأیید درخواست su/KernelSU ممکن است. رمزها و محدودیت‌های برنامه‌های بانکی یا صفحات امن قابل دور زدن تضمین‌شده نیستند.",
                            color = MaterialTheme.colorScheme.secondary,
                            fontSize = 12.sp
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (e: Exception) {
                            messages = messages + UiMessage("system", "باز کردن تنظیمات دسترس‌پذیری ناموفق بود: ${e.message ?: e.javaClass.simpleName}")
                        }
                    }) { Text("باز کردن تنظیمات") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        accessibilityEnabled = isPocketAccessibilityEnabled(context)
                        if (accessibilityEnabled) {
                            showAccessSetup = false
                            messages = messages + UiMessage("system", "دسترسی دسترس‌پذیری تأیید شد. بعضی بخش‌های اندروید همچنان محدودیت سیستمی دارند.")
                        } else {
                            messages = messages + UiMessage("system", "دسترسی هنوز فعال نیست. در تنظیمات اندروید، سرویس Pocket Agent — کنترل گوشی را روشن کن و دوباره بررسی کن.")
                        }
                    }) { Text("بررسی دوباره") }
                }
            )
        }

        if (showSettings) {
            ProviderSettingsDialog(
                initialEndpoint = securePrefs.endpoint(),
                initialModel = securePrefs.model(),
                hasSavedKey = securePrefs.apiKey().isNotBlank(),
                initialGithubRepo = securePrefs.githubRepo(),
                initialGithubBranch = securePrefs.githubBranch(),
                hasSavedGithubToken = securePrefs.hasGithubToken(),
                onDismiss = { showSettings = false },
                onSave = { endpoint, model, key, repo, branch, githubToken ->
                    try {
                        agent.saveSettings(endpoint, model, key)
                        securePrefs.saveGithubSettings(repo, branch, githubToken)
                        messages = messages + UiMessage("system", "تنظیمات ذخیره شد. کلید API و توکن GitHub در Android Keystore رمزگذاری می‌شوند و به مدل ارسال نمی‌شوند.")
                        showSettings = false
                        refreshChanges(open = false)
                    } catch (e: Exception) {
                        messages = messages + UiMessage("system", "ذخیرهٔ تنظیمات ناموفق بود: ${e.message}")
                    }
                }
            )
        }

        pendingCommand?.let { request ->
            val action = request.action
            val highRisk = ShellRunner.isHighRisk(action.command)
            AlertDialog(
                onDismissRequest = { request.decision.complete(false) },
                title = { Text(if (action.requiresRoot) "تأیید فرمان روت" else "تأیید فرمان ترمینال") },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 470.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("دلیل ایجنت: ${action.reason}")
                        Text("فرمان دقیق:", fontWeight = FontWeight.SemiBold)
                        Surface(color = Color(0xFF090909), shape = RoundedCornerShape(10.dp)) {
                            Text(action.command, modifier = Modifier.fillMaxWidth().padding(12.dp), fontSize = 12.sp, color = Color(0xFFA7F3D0))
                        }
                        if (action.requiresRoot) {
                            Text("این فرمان با مجوز superuser اجرا می‌شود و می‌تواند به کل دستگاه دسترسی داشته باشد.", color = Color(0xFFFCA5A5), fontWeight = FontWeight.SemiBold)
                        }
                        if (highRisk) {
                            Text("هشدار: الگوی فرمان شبیه عملیاتی پرخطر یا برگشت‌ناپذیر است. این تشخیص کامل نیست؛ قبل از تأیید خودت فرمان را بررسی کن.", color = Color(0xFFFCA5A5), fontWeight = FontWeight.Medium)
                        }
                        Text(
                            "فرمان داخل پوشهٔ کاری آغاز می‌شود، اما sandbox سیستم‌عاملی کامل نیست و خود فرمان می‌تواند مسیرهای دیگری را هدف بگیرد. اگر تأیید کنی، خروجی آن به مدل ارسال می‌شود.",
                            color = MaterialTheme.colorScheme.secondary,
                            fontSize = 11.sp
                        )
                    }
                },
                confirmButton = { Button(onClick = { request.decision.complete(true) }) { Text("تأیید و اجرا") } },
                dismissButton = { TextButton(onClick = { request.decision.complete(false) }) { Text("رد کردن") } }
            )
        }

        if (showChanges) {
            AlertDialog(
                onDismissRequest = { showChanges = false },
                title = { Text("بازبینی تغییرات · محلی ${changeList.size} / GitHub ${githubChangeList.size}", fontSize = 16.sp) },
                text = {
                    Column(modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                        Text("تغییرات محلی برای اعمال روی پوشهٔ انتخابی:", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                        if (changeList.isEmpty()) {
                            Text("تغییر محلیِ اعمال‌نشده‌ای وجود ندارد.", modifier = Modifier.padding(vertical = 8.dp), fontSize = 12.sp)
                        } else {
                            changeList.take(120).forEach { change ->
                                TextButton(
                                    onClick = { selectedDiff = change; selectedDiffIsGitHub = false },
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(horizontal = 3.dp, vertical = 4.dp)
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Text("${change.kind} · ${change.path}", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                                        Text("قبل ${change.beforeBytes} B → بعد ${change.afterBytes} B", color = MaterialTheme.colorScheme.secondary, fontSize = 10.sp)
                                    }
                                }
                            }
                            if (changeList.size > 120) Text("… فقط ۱۲۰ تغییر محلی نمایش داده می‌شود.", fontSize = 11.sp)
                        }
                        Divider(color = Color(0xFF303030), modifier = Modifier.padding(vertical = 8.dp))
                        Text("تغییرات آمادهٔ ارسال به GitHub: ${githubChangeList.size}", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                        Text("مقصد: ${securePrefs.githubRepo().ifBlank { "تنظیم نشده" }} @ ${securePrefs.githubBranch()}", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        if (githubChangeList.isEmpty()) {
                            Text("در مقایسه با snapshot مرجع GitHub تغییر جدیدی نیست.", modifier = Modifier.padding(vertical = 8.dp), fontSize = 12.sp)
                        } else {
                            githubChangeList.take(120).forEach { change ->
                                TextButton(
                                    onClick = { selectedDiff = change; selectedDiffIsGitHub = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(horizontal = 3.dp, vertical = 4.dp)
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Text("${change.kind} · ${change.path}", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                                        Text("قبل ${change.beforeBytes} B → بعد ${change.afterBytes} B", color = MaterialTheme.colorScheme.secondary, fontSize = 10.sp)
                                    }
                                }
                            }
                            if (githubChangeList.size > 120) Text("… فقط ۱۲۰ تغییر GitHub نمایش داده می‌شود.", fontSize = 11.sp)
                        }
                        Spacer(Modifier.size(8.dp))
                        OutlinedButton(onClick = { pushToGitHub() }, enabled = githubChangeList.isNotEmpty() && !busy, modifier = Modifier.fillMaxWidth()) { Text("ارسال تغییرات بازبینی‌شده به GitHub") }
                    }
                },
                confirmButton = {
                    Button(onClick = { showApplyConfirm = true }, enabled = changeList.isNotEmpty() && !busy) { Text("اعمال روی فایل اصلی") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { showDiscardConfirm = true }, enabled = changeList.isNotEmpty() && !busy) { Text("دورریختن") }
                        TextButton(onClick = { showChanges = false }) { Text("بستن") }
                    }
                }
            )
        }

        if (showPushConfirm) {
            AlertDialog(
                onDismissRequest = { if (!busy) showPushConfirm = false },
                title = { Text("ارسال تغییرات به GitHub؟") },
                text = {
                    Column(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${githubChangeList.size} فایل به‌صورت یک commit جدید روی ${securePrefs.githubRepo()} و شاخهٔ ${securePrefs.githubBranch()} ارسال می‌شود.")
                        Text("توکن ذخیره‌شده فقط برای ارتباط با GitHub استفاده می‌شود و به مدل هوش مصنوعی فرستاده نمی‌شود.", color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp)
                        Text("اگر workflow ساخت APK در شاخهٔ مقصد موجود باشد و تغییرات آن را فعال کنند، GitHub Actions می‌تواند اجرا شود. خطای API یا تعارض شاخه ارسال را متوقف می‌کند.", color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp)
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        showPushConfirm = false
                        if (!busy) {
                            busy = true
                            scope.launch {
                                try {
                                    val result = withContext(Dispatchers.IO) { GitHubSync(securePrefs, agent.workspace).pushChanges() }
                                    messages = messages + UiMessage("system", "ارسال GitHub موفق بود: ${result.filesPushed} فایل، commit ${result.commitSha.take(10)}\n${result.commitUrl}")
                                    refreshChanges(open = false)
                                } catch (e: Exception) {
                                    messages = messages + UiMessage("system", "ارسال به GitHub ناموفق بود: ${e.message ?: e.javaClass.simpleName}")
                                } finally { busy = false }
                            }
                        }
                    }, enabled = !busy && githubChangeList.isNotEmpty()) { Text("تأیید و ارسال") }
                },
                dismissButton = { TextButton(onClick = { showPushConfirm = false }, enabled = !busy) { Text("لغو") } }
            )
        }

        selectedDiff?.let { change ->
            val preview = remember(change.path, projectVersion, selectedDiffIsGitHub) { runCatching { agent.workspace.diffPreview(change.path, againstGitHub = selectedDiffIsGitHub) }.getOrElse { it.message ?: "پیش‌نمایش در دسترس نیست." } }
            AlertDialog(
                onDismissRequest = { selectedDiff = null },
                title = { Text((if (selectedDiffIsGitHub) "GitHub · " else "محلی · ") + change.path, fontSize = 14.sp) },
                text = {
                    Column(modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                        Text("${change.kind} · قبل ${change.beforeBytes} bytes · بعد ${change.afterBytes} bytes", fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.size(8.dp))
                        Text(preview, fontSize = 11.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                },
                confirmButton = { TextButton(onClick = { selectedDiff = null }) { Text("بازگشت") } }
            )
        }

        if (showApplyConfirm) {
            AlertDialog(
                onDismissRequest = { showApplyConfirm = false },
                title = { Text("اعمال تغییرات روی پروژهٔ اصلی؟") },
                text = { Text("${changeList.size} فایل به پوشه‌ای که انتخاب کرده‌ای نوشته یا از آن حذف می‌شود. این عملیات در سطح فایل‌ها انجام می‌شود و در صورت خطای مجوز/ذخیره‌ساز ممکن است بخشی از تغییرات اعمال شده باشد.") },
                confirmButton = {
                    Button(onClick = {
                        showApplyConfirm = false
                        busy = true
                        scope.launch {
                            try {
                                val count = withContext(Dispatchers.IO) { agent.workspace.applyChanges() }
                                messages = messages + UiMessage("system", "تغییرات روی پوشهٔ اصلی اعمال شد: $count فایل. بعد از اعمال، دوباره build/test بگیر.")
                                showChanges = false
                                changeList = withContext(Dispatchers.IO) { agent.workspace.changes() }
                                projectVersion++
                            } catch (e: Exception) {
                                messages = messages + UiMessage("system", "اعمال تغییرات کامل نشد: ${e.message ?: e.javaClass.simpleName}. فهرست تغییرات را دوباره بررسی کن؛ ممکن است بخشی از فایل‌ها نوشته شده باشد.")
                                changeList = withContext(Dispatchers.IO) { agent.workspace.changes() }
                            } finally { busy = false }
                        }
                    }) { Text("تأیید اعمال") }
                },
                dismissButton = { TextButton(onClick = { showApplyConfirm = false }) { Text("لغو") } }
            )
        }

        if (showDiscardConfirm) {
            AlertDialog(
                onDismissRequest = { showDiscardConfirm = false },
                title = { Text("تغییرات کپی کاری دور ریخته شود؟") },
                text = { Text("فقط ویرایش‌های محلیِ اعمال‌نشدهٔ کپی کاری حذف می‌شوند و به آخرین وضعیتی که روی پوشهٔ اصلی اعمال کرده‌ای برمی‌گردد. تغییرات آمادهٔ ارسال GitHub مستقل می‌مانند.") },
                confirmButton = {
                    Button(onClick = {
                        showDiscardConfirm = false
                        busy = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { agent.workspace.discardChanges() }
                                changeList = withContext(Dispatchers.IO) { agent.workspace.changes() }
                                githubChangeList = withContext(Dispatchers.IO) { agent.workspace.githubChanges() }
                                projectVersion++
                                messages = messages + UiMessage("system", "تغییرات ارسال‌نشدهٔ کپی کاری دور ریخته شد.")
                            } catch (e: Exception) {
                                messages = messages + UiMessage("system", "بازگردانی ناموفق بود: ${e.message}")
                            } finally { busy = false }
                        }
                    }) { Text("دورریختن تغییرات") }
                },
                dismissButton = { TextButton(onClick = { showDiscardConfirm = false }) { Text("لغو") } }
            )
        }
    }
}

@Composable
private fun MessageBubble(message: UiMessage) {
    val isUser = message.role == "user"
    val isTool = message.role == "tool"
    val isSystem = message.role == "system"
    val background = when {
        isUser -> Color(0xFF1D3A31)
        isTool -> Color(0xFF202020)
        isSystem -> Color(0xFF242018)
        else -> Color(0xFF171717)
    }
    val label = when (message.role) {
        "user" -> "تو"
        "tool" -> "ابزار / خروجی"
        "system" -> "وضعیت سیستم"
        else -> "Pocket Agent"
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Column(
            modifier = Modifier.fillMaxWidth(0.94f).widthIn(max = 560.dp)
                .background(background, RoundedCornerShape(16.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Text(message.text, fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

private fun runtimePermissions(name: String): List<String> = when (name.lowercase()) {
    "camera" -> listOf(Manifest.permission.CAMERA)
    "microphone" -> listOf(Manifest.permission.RECORD_AUDIO)
    "location" -> listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    "photos" -> if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.READ_MEDIA_IMAGES) else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    "notifications" -> if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
    else -> emptyList()
}

private fun openSpecialAccessSettings(context: Context, setting: String): String {
    val intent = try {
        when (setting.lowercase()) {
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "all_files" -> if (Build.VERSION.SDK_INT >= 30) {
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
            } else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            "usage_access" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            "notification_listener" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            "app_details" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            else -> return "نوع دسترسی ویژه شناخته‌شده نیست: $setting"
        }
    } catch (e: Exception) {
        return "ساختن صفحهٔ تنظیمات ناموفق بود: ${e.message ?: e.javaClass.simpleName}"
    }
    return try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        "صفحهٔ تنظیم دسترسی «$setting» باز شد. تغییر را خودت در Android تأیید کن و بعد به Pocket Agent برگرد."
    } catch (e: Exception) {
        "باز کردن صفحهٔ دسترسی ناموفق بود: ${e.message ?: e.javaClass.simpleName}"
    }
}

@Composable
private fun TerminalToolsDialog(homePath: String, binPath: String, installedTools: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ابزارهای ترمینال") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("پوشهٔ کاری و حافظهٔ ایجنت:", fontWeight = FontWeight.SemiBold)
                Text(homePath, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                Text("پوشهٔ اجرایی ابزارها:", fontWeight = FontWeight.SemiBold)
                Text(binPath, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                Divider(color = Color(0xFF303030))
                Text("ابزارهای نصب‌شده", fontWeight = FontWeight.SemiBold)
                Text(installedTools, fontSize = 12.sp)
                Text(
                    "ایجنت می‌تواند اسکریپت بسازد یا باینری HTTPS دانلود کند؛ پیش از ذخیره/نصب، پیش‌نمایش، نشانی و SHA-256 برای تأیید نمایش داده می‌شود. باینری باید با معماری و نسخهٔ Android سازگار باشد.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("بستن") } }
    )
}

@Composable
private fun GlobalMemoryDialog(initialValue: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("حافظهٔ کلی ایجنت") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("این یادداشت در همهٔ گفتگوها مشترک است. تاریخچهٔ مکالمه و زمینهٔ مدل هر چت همچنان جدا می‌ماند. رمز، کلید API و اطلاعات حساس را اینجا ننویس.", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 430.dp),
                    placeholder = { Text("ترجیحات پایدار و نکته‌های مفید ایجنت…") },
                    maxLines = 16
                )
                Text("${value.length}/24000", fontSize = 10.sp, color = MaterialTheme.colorScheme.secondary)
            }
        },
        confirmButton = { Button(onClick = { onSave(value) }, enabled = value.length <= 24000) { Text("ذخیره") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("لغو") } }
    )
}

@Composable
private fun ProviderSettingsDialog(
    initialEndpoint: String,
    initialModel: String,
    hasSavedKey: Boolean,
    initialGithubRepo: String,
    initialGithubBranch: String,
    hasSavedGithubToken: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String, String) -> Unit
) {
    var endpoint by remember(initialEndpoint) { mutableStateOf(initialEndpoint) }
    var model by remember(initialModel) { mutableStateOf(initialModel) }
    var apiKey by remember { mutableStateOf("") }
    var githubRepo by remember(initialGithubRepo) { mutableStateOf(initialGithubRepo) }
    var githubBranch by remember(initialGithubBranch) { mutableStateOf(initialGithubBranch) }
    var githubToken by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تنظیمات ایجنت") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("هوش مصنوعی", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                Text(
                    "یک endpoint سازگار با Chat Completions وارد کن. کلید API جدا از اشتراک ChatGPT حساب می‌شود.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.secondary
                )
                OutlinedTextField(
                    value = endpoint, onValueChange = { endpoint = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("آدرس API (پایه یا کامل)") }, placeholder = { Text("https://openrouter.ai/api/v1") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true
                )
                Text(
                    "آدرس پایهٔ OpenRouter یا OpenAI کافی است؛ برنامه مسیر /chat/completions را خودش اضافه می‌کند. اگر این مسیر را از قبل وارد کرده‌ای، دوباره اضافه نمی‌شود.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.secondary
                )
                OutlinedTextField(value = model, onValueChange = { model = it }, modifier = Modifier.fillMaxWidth(), label = { Text("نام مدل") }, singleLine = true)
                OutlinedTextField(
                    value = apiKey, onValueChange = { apiKey = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("کلید API") },
                    placeholder = { Text(if (hasSavedKey) "ذخیره شده؛ برای حفظ خالی بگذار" else "کلید API را وارد کن") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true
                )
                Divider(color = Color(0xFF303030))
                Text("همگام‌سازی GitHub", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                Text("فقط تغییرات بررسی‌شده را می‌توانی به یک شاخه push کنی. توکن هرگز به مدل داده نمی‌شود. برای fine-grained token، دسترسی Contents: Read and write روی همان مخزن کافی است.", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                OutlinedTextField(
                    value = githubRepo, onValueChange = { githubRepo = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("مخزن owner/repo") }, placeholder = { Text("RM007-UI/Your-Project") }, singleLine = true
                )
                OutlinedTextField(
                    value = githubBranch, onValueChange = { githubBranch = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("شاخهٔ مقصد") }, placeholder = { Text("main") }, singleLine = true
                )
                OutlinedTextField(
                    value = githubToken, onValueChange = { githubToken = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("توکن GitHub") },
                    placeholder = { Text(if (hasSavedGithubToken) "توکن ذخیره شده؛ برای حفظ آن خالی بگذار" else "توکن را وارد کن") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true
                )
                Text("کلیدهای جدید داخل Android Keystore رمزگذاری می‌شوند. مخزن عمومی ممکن است اطلاعاتی داشته باشد که نباید ارسال شوند؛ پیش از push، تغییرات را بررسی کن.", fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(endpoint.trim(), model.trim(), apiKey.trim(), githubRepo.trim(), githubBranch.trim(), githubToken.trim()) },
                enabled = endpoint.trim().startsWith("https://", ignoreCase = true) && model.isNotBlank() &&
                    (apiKey.isNotBlank() || hasSavedKey) &&
                    (githubRepo.isBlank() || githubRepo.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) &&
                    githubBranch.isNotBlank()
            ) { Text("ذخیره") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("لغو") } }
    )
}
