package com.rm007.pocketagent

import android.content.Context
import android.os.Build
import android.system.Os
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/**
 * Embedded Termux userspace for PAA. The bootstrap is built for this application's
 * package/prefix and bundled into the APK by GitHub Actions.
 */
internal object EmbeddedTermuxRuntime {
    private const val BOOTSTRAP_ASSET = "bootstrap-aarch64.zip"
    private const val CHECKSUM_ASSET = "bootstrap-aarch64.zip.sha256"
    private const val MAX_BOOTSTRAP_BYTES = 300L * 1024 * 1024
    private const val MAX_OUTPUT_CHARS = 18_000

    // Termux binaries are built with this exact /data/data prefix; using context.filesDir
    // may return the /data/user/0 alias and leave runtime PREFIX inconsistent.
    private fun appFilesDirectory(context: Context): File =
        File("/data/data/${context.packageName}/files")

    fun prefix(context: Context): File = File(appFilesDirectory(context), "usr")
    fun homeDirectory(context: Context): File = File(appFilesDirectory(context), "home")
    fun tempDirectory(context: Context): File = File(prefix(context), "tmp")
    fun bashFile(context: Context): File = File(prefix(context), "bin/bash")

    private fun systemLinkerPath(): String {
        val linker = if (Build.SUPPORTED_64_BIT_ABIS.any { it == "arm64-v8a" }) {
            "/system/bin/linker64"
        } else {
            "/system/bin/linker"
        }
        check(File(linker).canExecute()) {
            "لینکر سیستمی اندروید پیدا نشد: $linker"
        }
        return linker
    }

    fun isInstalled(context: Context): Boolean {
        val bash = bashFile(context)
        return bash.isFile && bash.canExecute() &&
            File(prefix(context), "bin/apt").isFile &&
            File(prefix(context), "etc/profile").isFile
    }

    /** Installs once from the APK asset; subsequent calls are a cheap file check. */
    fun ensureInstalled(context: Context) {
        require(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }) {
            "این نسخه از محیط داخلی فعلاً برای گوشی‌های ARM64 ساخته شده است."
        }
        if (!isInstalled(context)) {
            val checksumText = try {
                context.assets.open(CHECKSUM_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
            } catch (e: Exception) {
                throw IllegalStateException(
                    "محیط داخلی هنوز داخل این APK قرار نگرفته است. بیلد تازهٔ PAA را پس از تکمیل ساخت Termux نصب کن.",
                    e
                )
            }
            val expectedHash = checksumText.substringBefore(' ').trim()
            require(expectedHash.matches(Regex("[a-fA-F0-9]{64}"))) {
                "فایل SHA-256 محیط داخلی نامعتبر است."
            }

            val archive = File(context.cacheDir, BOOTSTRAP_ASSET)
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            try {
                context.assets.open(BOOTSTRAP_ASSET).use { input ->
                    FileOutputStream(archive).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_BOOTSTRAP_BYTES) { "فایل محیط داخلی از سقف مجاز بیشتر است." }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                }
                val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                require(actualHash.equals(expectedHash, ignoreCase = true)) {
                    "بررسی SHA-256 محیط داخلی ناموفق بود؛ نصب متوقف شد."
                }
                installArchive(context, archive)
            } finally {
                archive.delete()
            }
            check(isInstalled(context)) { "محیط داخلی نصب شد اما Bash یا فایل‌های پایه پیدا نشدند." }
        }
        ensureBootstrapConfigured(context)
    }

    fun terminalEnvironment(context: Context, extraBin: File? = null): Array<String> {
        val prefix = prefix(context)
        val home = homeDirectory(context)
        val tmp = tempDirectory(context)
        home.mkdirs()
        tmp.mkdirs()
        val path = listOfNotNull(
            prefix.resolve("bin").absolutePath,
            extraBin?.takeIf { it.isDirectory }?.absolutePath,
            "/system/bin",
            "/system/xbin"
        ).joinToString(":")
        val env = linkedMapOf(
            "HOME" to home.absolutePath,
            "PREFIX" to prefix.absolutePath,
            "TERMUX_PREFIX" to prefix.absolutePath,
            "PATH" to path,
            "TMPDIR" to tmp.absolutePath,
            "LD_LIBRARY_PATH" to prefix.resolve("lib").absolutePath,
            "ANDROID_ROOT" to "/system",
            "ANDROID_DATA" to "/data",
            "LANG" to "en_US.UTF-8",
            "TERM" to "xterm-256color",
            "COLORTERM" to "truecolor",
            "SHELL" to bashFile(context).absolutePath,
            "TERMUX_APP__PACKAGE_NAME" to context.packageName,
            "TERMUX_PACKAGE_MANAGER" to "apt",
            "TERMUX_PACKAGE_ARCH" to "aarch64",
            "ANDROID__BUILD_VERSION_SDK" to Build.VERSION.SDK_INT.toString(),
            "TERMUX__PREFIX" to prefix.absolutePath,
            "PS1" to "pocket-agent$ "
        )
        // Created by termux-exec's postinst during bootstrap second-stage.
        val termuxExec = prefix.resolve("lib/libtermux-exec-ld-preload.so")
        if (termuxExec.isFile) env["LD_PRELOAD"] = termuxExec.absolutePath
        return env.map { (key, value) -> "$key=$value" }.toTypedArray()
    }

    fun execute(
        context: Context,
        command: String,
        workingDirectory: File?,
        timeoutSeconds: Long = 120,
        extraBinDirectory: File? = null
    ): String {
        if (command.isBlank()) return "فرمان خالی بود؛ چیزی اجرا نشد."
        if (command.length > 12_000) return "فرمان بیش از حد طولانی است و اجرا نشد."
        return try {
            ensureInstalled(context)
            val prefix = prefix(context)
            val work = workingDirectory?.takeIf { it.isDirectory } ?: homeDirectory(context)
            work.mkdirs()
            // Android 10+ blocks execve() of writable app-private files for targetSdk >= 29.
            // Launch the Termux ELF through the system dynamic linker instead; termux-exec
            // (LD_PRELOAD below) then handles child exec() calls and shebang/path rewriting.
            val builder = ProcessBuilder(
                systemLinkerPath(),
                bashFile(context).absolutePath,
                "--login",
                "-c",
                command
            )
                .directory(work)
                .redirectErrorStream(true)
            builder.environment().putAll(terminalEnvironment(context, extraBinDirectory).associate {
                it.substringBefore('=') to it.substringAfter('=')
            })
            val process = builder.start()
            val output = ConcurrentLinkedQueue<String>()
            val chars = AtomicInteger(0)
            val truncated = AtomicInteger(0)
            val reader = thread(name = "paa-embedded-shell-output", isDaemon = true) {
                try {
                    BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { input ->
                        while (true) {
                            val line = input.readLine() ?: break
                            val before = chars.get()
                            val remaining = MAX_OUTPUT_CHARS - before
                            if (remaining > 0) {
                                output.add(line.take(remaining))
                                chars.addAndGet(minOf(line.length, remaining) + 1)
                            }
                            if (line.length > remaining) truncated.incrementAndGet()
                        }
                    }
                } catch (_: Exception) { }
            }
            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) {
                process.destroy()
                if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
            }
            reader.join(2_000)
            val text = output.joinToString("\n").ifBlank { "(بدون خروجی متنی)" }
            val suffix = if (truncated.get() > 0) "\n… خروجی به $MAX_OUTPUT_CHARS نویسه محدود شد." else ""
            if (completed) "کد خروج: ${process.exitValue()}\n$text$suffix"
            else "فرمان پس از $timeoutSeconds ثانیه متوقف شد.\n$text$suffix"
        } catch (e: Exception) {
            "اجرای فرمان در محیط داخلی ناموفق بود: ${e.javaClass.simpleName}: ${e.message ?: "خطای نامشخص"}"
        }
    }

    /**
     * Bootstrap extraction alone is incomplete: dpkg postinst scripts must run.
     * The generated second-stage script currently has empty package-manager fields.
     */
    private fun ensureBootstrapConfigured(context: Context) {
        val prefix = prefix(context)
        val script = File(
            prefix,
            "etc/termux/termux-bootstrap/second-stage/termux-bootstrap-second-stage.sh"
        )
        check(script.isFile) { "اسکریپت مرحلهٔ دوم bootstrap پیدا نشد." }

        val marker = prefix.resolve("var/lib/pocket-agent/bootstrap-second-stage-v1")
        if (marker.isFile) return

        val original = script.readText(Charsets.UTF_8)
        val lock = File(script.parentFile, script.name + ".lock")
        val legacyNoOpRun = lock.exists() &&
            original.contains("export TERMUX_PACKAGE_MANAGER=\"\"") &&
            original.contains("export TERMUX_PACKAGE_ARCH=\"\"")
        check(!lock.exists() || legacyNoOpRun) {
            "مرحلهٔ دوم قبلاً با تنظیمات کامل اجرا شده اما نشانگر تکمیل وجود ندارد. برای جلوگیری از اجرای دوبارهٔ postinst، داده‌های برنامه را پاک و محیط را از نو نصب کن."
        }
        // Older PAA builds let Termux's login-profile fallback run this script
        // with both values empty. That created the lock but skipped every apt postinst.
        // The untouched template proves that legacy run was a no-op, so it is safe to retry once.
        if (legacyNoOpRun) check(lock.delete()) { "پاک‌کردن قفل اجرای ناقص قبلی ناموفق بود." }

        val configured = original
            .replace("export TERMUX_PACKAGE_MANAGER=\"\"", "export TERMUX_PACKAGE_MANAGER=\"apt\"")
            .replace("export TERMUX_PACKAGE_ARCH=\"\"", "export TERMUX_PACKAGE_ARCH=\"aarch64\"")
        check(configured.contains("export TERMUX_PACKAGE_MANAGER=\"apt\"") &&
            configured.contains("export TERMUX_PACKAGE_ARCH=\"aarch64\"")) {
            "قالب اسکریپت مرحلهٔ دوم با نسخهٔ مورد انتظار سازگار نیست."
        }
        if (configured != original) script.writeText(configured, Charsets.UTF_8)

        // The bootstrap archive already contains this .so before postinst runs.
        // It is therefore NOT a valid signal that package configuration finished.

        // The bootstrap second stage must also start through linker64 before termux-exec
        // has been configured and can intercept normal exec() calls.
        val builder = ProcessBuilder(
            systemLinkerPath(),
            bashFile(context).absolutePath,
            script.absolutePath
        )
            .directory(prefix)
            .redirectErrorStream(true)
        terminalEnvironment(context).forEach { entry ->
            builder.environment()[entry.substringBefore('=')] = entry.substringAfter('=') 
        }
        // Do not preload termux-exec until its postinst has configured it.
        builder.environment().remove("LD_PRELOAD")
        builder.environment()["TERMUX_PACKAGE_MANAGER"] = "apt"
        builder.environment()["TERMUX_PACKAGE_ARCH"] = "aarch64"
        builder.environment()["ANDROID__BUILD_VERSION_SDK"] = Build.VERSION.SDK_INT.toString()

        val process = builder.start()
        val output = StringBuilder()
        val reader = thread(name = "paa-termux-bootstrap-config", isDaemon = true) {
            try {
                BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { input ->
                    while (true) {
                        val line = input.readLine() ?: break
                        synchronized(output) {
                            if (output.length < MAX_OUTPUT_CHARS) {
                                output.append(line.take(MAX_OUTPUT_CHARS - output.length)).append('\n')
                            }
                        }
                    }
                }
            } catch (_: Exception) { }
        }
        val completed = process.waitFor(300, TimeUnit.SECONDS)
        if (!completed) {
            process.destroy()
            if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        reader.join(2_000)
        val details = synchronized(output) { output.toString().takeLast(5_000) }
        val exitCode = if (completed) process.exitValue() else null
        if (!completed || exitCode != 0) {
            val reason = if (!completed) "مهلت ۳۰۰ ثانیه تمام شد." else "کد خروج: $exitCode."
            error("پیکربندی اولیهٔ apt/termux-exec کامل نشد. $reason" +
                if (details.isBlank()) "" else "\n$details")
        }

        check(prefix.resolve("lib/libtermux-exec-ld-preload.so").isFile) {
            "مرحلهٔ دوم تمام شد اما کتابخانهٔ termux-exec پیدا نشد."
        }
        check(marker.parentFile?.mkdirs() == true || marker.parentFile?.isDirectory == true) {
            "ساخت نشانگر تکمیل bootstrap ناموفق بود."
        }
        marker.writeText("completed\n", Charsets.UTF_8)
    }

    private fun installArchive(context: Context, archive: File) {
        val prefixParent = prefix(context).parentFile
            ?: error("مسیر خصوصی محیط داخلی نامعتبر است.")
        val stage = File(prefixParent, "usr-staging")
        val target = prefix(context)
        val backup = File(prefixParent, "usr-backup")
        stage.deleteRecursively()
        check(stage.mkdirs() || stage.isDirectory) { "ساخت پوشهٔ موقت محیط داخلی ناموفق بود." }

        try {
            val symlinks = mutableListOf<Pair<String, String>>()
            ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "SYMLINKS.txt") {
                        val reader = BufferedReader(InputStreamReader(zip, Charsets.UTF_8))
                        while (true) {
                            val line = reader.readLine() ?: break
                            val separator = line.indexOf('←')
                            require(separator > 0 && separator < line.lastIndex) { "ورودی symlink در bootstrap نامعتبر است." }
                            val linkTarget = line.substring(0, separator)
                            val linkName = safeRelativePath(line.substring(separator + 1))
                            symlinks.add(linkTarget to linkName)
                        }
                    } else {
                        if (entry.name == "./" || entry.name.isEmpty()) {
                            zip.closeEntry()
                            continue
                        }
                        val name = safeRelativePath(entry.name)
                        if (name.isEmpty()) continue
                        val out = File(stage, name)
                        val canonicalRoot = stage.canonicalFile.toPath()
                        val canonicalOut = out.canonicalFile.toPath()
                        require(canonicalOut.startsWith(canonicalRoot)) { "مسیر ناامن داخل bootstrap رد شد." }
                        if (entry.isDirectory) {
                            check(out.mkdirs() || out.isDirectory) { "ساخت پوشهٔ bootstrap ناموفق بود: $name" }
                        } else {
                            val parent = out.parentFile
                            check(parent != null && (parent.mkdirs() || parent.isDirectory)) {
                                "ساخت پوشهٔ فایل bootstrap ناموفق بود: $name"
                            }
                            FileOutputStream(out).use { output -> zip.copyTo(output) }
                            if (name.startsWith("bin/") || name.startsWith("libexec") ||
                                name.startsWith("lib/apt/apt-helper") || name.startsWith("lib/apt/methods")) {
                                Os.chmod(out.absolutePath, 448) // 0700
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            require(symlinks.isNotEmpty()) { "فایل bootstrap فاقد فهرست symlinkهاست." }
            for ((linkTarget, relativePath) in symlinks) {
                val link = File(stage, relativePath)
                val root = stage.canonicalFile.toPath()
                require(link.canonicalFile.toPath().startsWith(root)) { "مسیر symlink از محیط داخلی خارج می‌شود." }
                link.parentFile?.mkdirs()
                Os.symlink(linkTarget, link.absolutePath)
            }

            // ZipInputStream does not expose Unix external attributes, so the archive's
            // executable bits are lost during extraction. Restore them explicitly after
            // creating symlinks (not before): bin/bash is commonly a symlink into lib/.
            val stagedBin = File(stage, "bin")
            stagedBin.listFiles()?.forEach { entry ->
                if (entry.isFile) Os.chmod(entry.absolutePath, 448) // 0700
            }
            val stagedBash = File(stage, "bin/bash")
            check(stagedBash.isFile) { "فایل Bash در bootstrap پیدا نشد." }
            Os.chmod(stagedBash.canonicalPath, 448) // 0700, follows bin/bash symlink
            check(stagedBash.canExecute()) { "مجوز اجرای Bash پس از استخراج تنظیم نشد." }

            backup.deleteRecursively()
            if (target.exists()) check(target.renameTo(backup)) { "پوشهٔ قدیمی محیط داخلی قابل جابه‌جایی نیست." }
            if (!stage.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target)
                error("انتقال محیط داخلی به پوشهٔ نهایی ناموفق بود.")
            }
            backup.deleteRecursively()
        } catch (e: Exception) {
            stage.deleteRecursively()
            throw e
        }
    }

    private fun safeRelativePath(raw: String): String {
        val value = raw.removePrefix("./")
        require(value.isNotBlank() && !value.startsWith("/") && !value.contains('\\')) {
            "مسیر نامعتبر در bootstrap پیدا شد."
        }
        val components = value.split('/')
        require(components.none { it == ".." || it == "." }) {
            "مسیر خارج از محیط داخلی در bootstrap پیدا شد."
        }
        return value.trimEnd('/')
    }
}
