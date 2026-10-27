package com.rm007.pocketagent

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

internal data class InteractiveWorkspaceSync(
    val privateDirectory: File,
    val sharedDirectory: File
)

internal object TermuxBridge {
    private const val TERMUX_PACKAGE = "com.termux"
    private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    private const val TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash"
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"
    private const val TERMUX_BIN = "/data/data/com.termux/files/usr/bin"
    private const val REQUEST_ID_EXTRA = "pocket_agent_execution_id"
    private const val RESULT_BUNDLE = "result"
    private const val RESULT_STDOUT = "stdout"
    private const val RESULT_STDERR = "stderr"
    private const val RESULT_EXIT_CODE = "exitCode"
    private const val RESULT_ERR = "err"
    private const val RESULT_ERRMSG = "errmsg"
    private const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    private const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
    private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
    private const val EXTRA_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
    private const val EXTRA_DESCRIPTION = "com.termux.RUN_COMMAND_COMMAND_DESCRIPTION"
    private const val EXTRA_HELP = "com.termux.RUN_COMMAND_COMMAND_HELP"
    private const val MAX_OUTPUT_CHARS = 18_000
    private const val MAX_SYNC_FILES = 15_000
    private const val MAX_SYNC_BYTES = 300L * 1024 * 1024
    private val requestIds = AtomicInteger(1000)
    private val pendingResults = ConcurrentHashMap<Int, CompletableDeferred<Bundle>>()
    private val syncLock = Any()
    @Volatile private var pendingInteractiveSync: InteractiveWorkspaceSync? = null

    private val generatedDirectories = setOf(
        ".git", "node_modules", "build", ".gradle", "dist", "target", ".venv", "venv"
    )

    fun isInstalled(context: Context): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: Exception) {
        false
    }

    fun hasPendingInteractiveSync(): Boolean = pendingInteractiveSync != null

    fun takePendingInteractiveSync(): InteractiveWorkspaceSync? = synchronized(syncLock) {
        val result = pendingInteractiveSync
        pendingInteractiveSync = null
        result
    }

    fun deliverResult(id: Int, bundle: Bundle?) {
        val deferred = pendingResults.remove(id) ?: return
        deferred.complete(bundle ?: Bundle().apply {
            putInt(RESULT_ERR, 1)
            putString(RESULT_ERRMSG, "Termux sent an empty result bundle.")
            putInt(RESULT_EXIT_CODE, 1)
        })
    }

    fun openInteractiveTerminal(context: Context, workingDirectory: File?) {
        checkInstalled(context)
        val mirror = workingDirectory?.let {
            val shared = sharedWorkspaceDirectory(it)
            syncDirectory(it, shared)
            shared
        }
        val cwd = mirror ?: File(TERMUX_HOME)
        val shellCommand = "cd ${shellQuote(cwd.absolutePath)} && exec bash -i"
        val intent = commandIntent(shellCommand, cwd.absolutePath, background = false).apply {
            putExtra(EXTRA_SESSION_ACTION, "0")
            putExtra(EXTRA_LABEL, "Pocket Agent terminal")
            putExtra(EXTRA_DESCRIPTION, "Open an interactive Termux shell from Pocket Agent.")
            putExtra(EXTRA_HELP, RUN_COMMAND_HELP)
        }

        if (workingDirectory != null && mirror != null) {
            synchronized(syncLock) {
                pendingInteractiveSync = InteractiveWorkspaceSync(workingDirectory, mirror)
            }
        }
        try {
            check(context.startService(intent) != null) { "Termux did not accept the terminal request." }
        } catch (e: Exception) {
            synchronized(syncLock) { pendingInteractiveSync = null }
            throw IllegalStateException(setupInstructions(e.message ?: e.javaClass.simpleName, workingDirectory != null), e)
        }
    }

    suspend fun execute(
        context: Context,
        command: String,
        workingDirectory: File?,
        timeoutSeconds: Long = 120
    ): String {
        checkInstalled(context)
        require(command.isNotBlank() && command.length <= 12_000) {
            "Termux command is empty or too long."
        }
        val timeout = timeoutSeconds.coerceIn(1, 300)
        val mirror = workingDirectory?.let {
            val shared = sharedWorkspaceDirectory(it)
            syncDirectory(it, shared)
            shared
        }
        val cwd = mirror ?: File(TERMUX_HOME)
        val id = requestIds.incrementAndGet()
        val deferred = CompletableDeferred<Bundle>()
        pendingResults[id] = deferred
        val callback = Intent(context, TermuxResultReceiver::class.java).apply {
            action = "com.rm007.pocketagent.TERMUX_RESULT"
            putExtra(REQUEST_ID_EXTRA, id)
        }
        val flags = PendingIntent.FLAG_ONE_SHOT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val pendingIntent = PendingIntent.getBroadcast(context, id, callback, flags)
        val wrappedCommand = "cd ${shellQuote(cwd.absolutePath)} && timeout ${timeout}s bash -lc ${shellQuote(command)}"
        val intent = commandIntent(wrappedCommand, cwd.absolutePath, background = true).apply {
            putExtra(EXTRA_PENDING_INTENT, pendingIntent)
            putExtra(EXTRA_LABEL, "Pocket Agent command")
            putExtra(EXTRA_DESCRIPTION, "A user-approved shell command requested by Pocket Agent.")
            putExtra(EXTRA_HELP, RUN_COMMAND_HELP)
        }

        var started = false
        var output = ""
        var syncError: String? = null
        try {
            check(context.startService(intent) != null) { "Termux did not accept the command request." }
            started = true
            val bundle = withTimeoutOrNull((timeout + 20) * 1000L) { deferred.await() }
            output = if (bundle == null) {
                "Termux command timed out after ${timeout}s. Check the Termux session to see whether a process is still running."
            } else {
                formatResult(bundle)
            }
        } catch (e: SecurityException) {
            output = setupInstructions(
                "Android denied the Termux RUN_COMMAND request: ${e.message ?: e.javaClass.simpleName}",
                workingDirectory != null
            )
        } catch (e: Exception) {
            output = "Termux command could not be started: ${e.message ?: e.javaClass.simpleName}\n" +
                setupInstructions(e.message ?: "unknown error", workingDirectory != null)
        } finally {
            pendingResults.remove(id)
            if (!started) pendingIntent.cancel()
            if (started && mirror != null && workingDirectory != null) {
                try {
                    syncDirectory(mirror, workingDirectory)
                } catch (e: Exception) {
                    syncError = "Project files could not be fully copied back from Termux: ${e.message ?: e.javaClass.simpleName}. Shared copy remains at ${mirror.absolutePath}."
                }
            }
        }
        if (syncError != null) output += "\n\n$syncError"
        return output.take(MAX_OUTPUT_CHARS) + if (output.length > MAX_OUTPUT_CHARS) "\n… output was truncated." else ""
    }

    fun syncBack(sync: InteractiveWorkspaceSync) {
        syncDirectory(sync.sharedDirectory, sync.privateDirectory)
    }

    suspend fun importTool(context: Context, localFile: File): String {
        if (!isInstalled(context)) return ""
        val name = localFile.name
        require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))) { "Invalid Termux tool name." }
        val sharedFile = try {
            ensureSharedStorageAccess()
            val staging = File(sharedRoot(), "tool-staging")
            check(staging.mkdirs() || staging.isDirectory) { "Could not create Termux tool staging directory." }
            File(staging, name).also { localFile.copyTo(it, overwrite = true) }
        } catch (e: Exception) {
            return "Tool saved in Pocket Agent, but could not be imported into Termux. Enable All files access for Pocket Agent and shared-storage access for Termux, then retry. Details: ${e.message ?: e.javaClass.simpleName}"
        }

        return try {
            val destination = File(TERMUX_BIN, name).absolutePath
            val command = "cp -- ${shellQuote(sharedFile.absolutePath)} ${shellQuote(destination)} && chmod 700 ${shellQuote(destination)}"
            val result = execute(context, command, null, 45)
            sharedFile.delete()
            if (!result.contains("Termux exit code: 0")) {
                "Tool remains in Pocket Agent's private folder, but importing it into Termux failed:\n$result"
            } else {
                "Imported «$name» into Termux's bin directory.\n$result"
            }
        } catch (e: Exception) {
            sharedFile.delete()
            "Tool remains in Pocket Agent's private folder, but importing it into Termux failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun setupInstructions(reason: String, needsProjectSharing: Boolean): String = buildString {
        append("اتصال Termux آماده نشد.\n")
        append(reason.take(700))
        append("\n\n۱. خود برنامهٔ Termux باید نصب و یک‌بار باز شده باشد.\n")
        append("۲. در تنظیمات Android برای Pocket Agent مجوز «Run commands in Termux environment» را فعال کن.\n")
        append("۳. در Termux فایل ~/.termux/termux.properties را باز کن و مقدار allow-external-apps = true را اضافه کن؛ سپس Termux را کاملاً ببند و دوباره باز کن.")
        if (needsProjectSharing) {
            append("\n۴. برای اشتراک پروژه، All files access را برای Pocket Agent فعال کن و در Termux دستور termux-setup-storage را اجرا کن.")
        }
        append("\nراهنمای رسمی: https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent")
    }

    private fun commandIntent(command: String, workingDirectory: String, background: Boolean): Intent =
        Intent(ACTION_RUN_COMMAND).apply {
            setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
            putExtra(EXTRA_PATH, TERMUX_BASH)
            putExtra(EXTRA_ARGUMENTS, arrayOf("-lc", command))
            putExtra(EXTRA_WORKDIR, workingDirectory)
            putExtra(EXTRA_BACKGROUND, background)
        }

    private fun formatResult(bundle: Bundle): String = buildString {
        val exitCode = bundle.getInt(RESULT_EXIT_CODE, -1)
        val errorCode = bundle.getInt(RESULT_ERR, 0)
        append("Termux exit code: $exitCode")
        val errorMessage = bundle.getString(RESULT_ERRMSG).orEmpty()
        if (errorCode != 0 || errorMessage.isNotBlank()) {
            append("\nTermux bridge error")
            if (errorCode != 0) append(" ($errorCode)")
            if (errorMessage.isNotBlank()) append(": ${errorMessage.take(2500)}")
            append("\nCheck the RUN_COMMAND permission and allow-external-apps setting.")
        }
        val stdout = bundle.getString(RESULT_STDOUT).orEmpty()
        val stderr = bundle.getString(RESULT_STDERR).orEmpty()
        if (stdout.isNotBlank()) append("\n\nstdout:\n").append(stdout)
        if (stderr.isNotBlank()) append("\n\nstderr:\n").append(stderr)
        if (stdout.isBlank() && stderr.isBlank() && errorMessage.isBlank()) append("\n(no text output)")
    }

    private fun sharedWorkspaceDirectory(source: File): File {
        require(source.isDirectory) { "The selected staged project directory is missing." }
        ensureSharedStorageAccess()
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(source.canonicalPath.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            .take(24)
        val directory = File(File(sharedRoot(), "workspaces"), hash)
        check(directory.mkdirs() || directory.isDirectory) {
            "Could not create the shared Termux project directory: ${directory.absolutePath}"
        }
        return directory
    }

    private fun sharedRoot(): File {
        @Suppress("DEPRECATION")
        val publicStorage = Environment.getExternalStorageDirectory()
        return File(publicStorage, "PocketAgentTermux")
    }

    private fun ensureSharedStorageAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw IllegalStateException("Project sharing requires Android 11 or newer with All files access.")
        }
        if (!Environment.isExternalStorageManager()) {
            throw IllegalStateException("Enable All files access for Pocket Agent in Android Settings to share the staged project with Termux.")
        }
    }

    private fun syncDirectory(source: File, destination: File) {
        require(source.isDirectory) { "Sync source is not a directory: ${source.absolutePath}" }
        check(destination.mkdirs() || destination.isDirectory) {
            "Could not create sync destination: ${destination.absolutePath}"
        }

        val sourcePaths = HashSet<String>()
        var fileCount = 0
        var totalBytes = 0L

        source.walkTopDown()
            .onEnter { directory ->
                directory == source ||
                    (!Files.isSymbolicLink(directory.toPath()) && directory.name !in generatedDirectories)
            }
            .forEach { entry ->
                if (Files.isSymbolicLink(entry.toPath())) return@forEach
                val relative = if (entry == source) "" else entry.relativeTo(source).invariantSeparatorsPath
                if (relative.split('/').any { it in generatedDirectories }) return@forEach
                sourcePaths += relative
                val target = if (relative.isBlank()) destination else File(destination, relative)

                if (entry.isDirectory) {
                    if (target.exists() && !target.isDirectory) target.deleteRecursively()
                    check(target.mkdirs() || target.isDirectory) { "Could not create directory ${target.absolutePath}" }
                } else if (entry.isFile) {
                    fileCount++
                    totalBytes += entry.length()
                    require(fileCount <= MAX_SYNC_FILES && totalBytes <= MAX_SYNC_BYTES) {
                        "Termux sync stopped at the 15,000-file / 300-MB safety limit."
                    }
                    if (target.isDirectory) target.deleteRecursively()
                    if (!target.isFile || target.length() != entry.length() || target.lastModified() != entry.lastModified()) {
                        check(target.parentFile?.let { it.mkdirs() || it.isDirectory } != false) {
                            "Could not create file parent for ${target.absolutePath}"
                        }
                        entry.copyTo(target, overwrite = true)
                        target.setLastModified(entry.lastModified())
                    }
                }
            }

        destination.walkBottomUp().forEach { existing ->
            if (existing == destination) return@forEach
            val relative = existing.relativeTo(destination).invariantSeparatorsPath
            if (relative.split('/').any { it in generatedDirectories }) return@forEach
            if (relative !in sourcePaths) {
                check(existing.deleteRecursively()) {
                    "Could not remove stale synced item: ${existing.absolutePath}"
                }
            }
        }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun checkInstalled(context: Context) {
        check(isInstalled(context)) {
            "Termux is not installed. Install and open Termux first, then return to Pocket Agent."
        }
    }

    private const val RUN_COMMAND_HELP = "https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent"
}

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("pocket_agent_execution_id", -1)
        if (id <= 0) return
        TermuxBridge.deliverResult(id, intent.getBundleExtra("result"))
    }
}
