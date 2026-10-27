package com.rm007.pocketagent

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

object ShellRunner {
    private const val MAX_OUTPUT_CHARS = 18_000

    fun execute(command: String, workingDirectory: java.io.File?, root: Boolean, timeoutSeconds: Long = 90): String {
        if (command.isBlank()) return "فرمان خالی بود؛ چیزی اجرا نشد."
        if (command.length > 12_000) return "فرمان بیش از حد طولانی است و اجرا نشد."
        return try {
            val processBuilder = if (root) {
                val cd = workingDirectory?.absolutePath?.let { "cd ${shellQuote(it)} && " }.orEmpty()
                ProcessBuilder("su", "-c", cd + command)
            } else {
                ProcessBuilder("sh", "-c", command).apply { if (workingDirectory != null) directory(workingDirectory) }
            }
            processBuilder.redirectErrorStream(true)
            val process = processBuilder.start()
            val output = ConcurrentLinkedQueue<String>()
            val chars = AtomicInteger(0)
            val truncated = AtomicInteger(0)
            val reader = thread(name = "agent-command-output", isDaemon = true) {
                try {
                    BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { input ->
                        while (true) {
                            val line = input.readLine() ?: break
                            val remaining = MAX_OUTPUT_CHARS - chars.get()
                            if (remaining > 0) {
                                val chunk = line.take(remaining)
                                output.add(chunk)
                                chars.addAndGet(chunk.length + 1)
                            }
                            if (line.length + chars.get() > MAX_OUTPUT_CHARS) truncated.incrementAndGet()
                        }
                    }
                } catch (_: Exception) { /* process may have been killed on timeout */ }
            }
            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) {
                process.destroy()
                if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
            }
            reader.join(2_000)
            val outputText = output.joinToString("\n").ifBlank { "(بدون خروجی متنی)" }
            val end = if (truncated.get() > 0) "\n… خروجی به $MAX_OUTPUT_CHARS نویسه محدود شد." else ""
            if (completed) "کد خروج: ${process.exitValue()}\n$outputText$end"
            else "فرمان پس از $timeoutSeconds ثانیه متوقف شد.\n$outputText$end"
        } catch (e: Exception) {
            val hint = if (root) "مجوز KernelSU را بررسی کن." else "فرمان را بررسی کن؛ ابزارهایی مثل git، python یا gradle ممکن است روی Android نصب نباشند."
            "اجرای فرمان ناموفق بود: ${e.javaClass.simpleName}: ${e.message ?: "خطای نامشخص"}\n$hint"
        }
    }

    fun isHighRisk(command: String): Boolean {
        val patterns = listOf(
            Regex("\\brm\\s+(-[^\\s]*r[^\\s]*f|-[^\\s]*f[^\\s]*r)\\b", RegexOption.IGNORE_CASE),
            Regex("\\bdd\\b.*\\bof=/dev/block", RegexOption.IGNORE_CASE),
            Regex("\\b(mkfs|mke2fs|fastboot|flash_image|wipefs|parted|sgdisk)\\b", RegexOption.IGNORE_CASE),
            Regex("\\b(setenforce\\s+0|pm\\s+clear|factory.?reset)\\b", RegexOption.IGNORE_CASE),
            Regex("/dev/block/(bootdevice|by-name|mmcblk|sda)", RegexOption.IGNORE_CASE),
            Regex("\\b(reboot\\s+bootloader|reboot\\s+recovery)\\b", RegexOption.IGNORE_CASE),
            Regex("\\b(chmod\\s+777|chown\\s+root|mount\\s+-o\\s+rw)", RegexOption.IGNORE_CASE)
        )
        return patterns.any { it.containsMatchIn(command) }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
