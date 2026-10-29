package com.rm007.pocketagent

import android.content.Context
import android.os.Build
import android.os.Environment
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Direct access to the user's shared primary storage (normally /storage/emulated/0).
 * Android's All files access special permission is required on Android 11+.
 */
class SharedStorageAccess(context: Context) {
    private val appContext = context.applicationContext

    fun hasAccess(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        Environment.isExternalStorageManager()

    fun rootPath(): String = Environment.getExternalStorageDirectory().canonicalPath

    fun listFiles(path: String = "", recursive: Boolean = false): String {
        requireAccess()
        val root = storageRoot()
        val target = resolve(root, path)
        require(target.isDirectory) { "مسیر پوشه نیست: $path" }
        val files = if (recursive) {
            target.walkTopDown()
                .onEnter { directory -> directory == root || isInside(root, directory) }
                .drop(1)
                .take(MAX_LIST_ENTRIES + 1)
                .toList()
        } else {
            target.listFiles()?.sortedBy { it.name.lowercase() }
                ?: error("اندروید اجازهٔ فهرست‌کردن این مسیر را نمی‌دهد: ${target.path}")
        }
        val truncated = files.size > MAX_LIST_ENTRIES
        val lines = files.take(MAX_LIST_ENTRIES).map { file ->
            val relative = file.relativeTo(root).invariantSeparatorsPath
            when {
                file.isDirectory -> "$relative/"
                file.isFile -> "$relative (${file.length()} bytes)"
                else -> "$relative (نوع ناشناخته)"
            }
        }
        val header = "ریشهٔ حافظهٔ اشتراکی: ${root.path}\nمسیر: ${if (path.isBlank()) "/" else path}"
        val body = lines.joinToString("\n").ifBlank { "(پوشه خالی است)" }
        val note = if (truncated) "\n… خروجی به $MAX_LIST_ENTRIES مورد محدود شد؛ مسیرهای فرعی را جداگانه فهرست کن." else ""
        return "$header\n$body$note"
    }

    fun readFile(path: String): String {
        requireAccess()
        val root = storageRoot()
        val normalized = normalizePath(path)
        require(normalized.isNotBlank()) { "مسیر فایل خالی است." }
        require(!isSensitivePath(normalized)) {
            "برای جلوگیری از ارسال تصادفی اطلاعات محرمانه به مدل، خواندن این نوع فایل مسدود است؛ نام فایل در فهرست دیده می‌شود."
        }
        val file = resolve(root, normalized)
        require(file.isFile) { "فایل عادی پیدا نشد: $normalized" }
        require(file.length() <= MAX_READ_BYTES) {
            "فایل بزرگ است (${file.length()} بایت). حداکثر خواندن متنی ${MAX_READ_BYTES / 1024} کیلوبایت است."
        }
        val bytes = file.readBytes()
        require(!looksBinary(bytes, file.extension)) { "این فایل باینری است و با ابزار متن باز نمی‌شود: $normalized" }
        return "فایل: ${file.path}\n" + String(bytes, Charsets.UTF_8)
    }

    fun searchFiles(query: String, maxMatches: Int = 30): String {
        requireAccess()
        val needle = query.trim()
        require(needle.isNotEmpty()) { "عبارت جست‌وجو خالی است." }
        val root = storageRoot()
        val matches = mutableListOf<String>()
        val candidates = root.walkTopDown()
            .onEnter { directory -> directory == root || isInside(root, directory) }
            .filter { it.isFile }
            .take(MAX_SEARCH_FILES + 1)
            .toList()
        for (file in candidates.take(MAX_SEARCH_FILES)) {
            if (matches.size >= maxMatches) break
            val relative = runCatching { file.relativeTo(root).invariantSeparatorsPath }.getOrNull() ?: continue
            if (isSensitivePath(relative) || file.length() > MAX_SEARCH_FILE_BYTES) continue
            try {
                file.useLines(Charsets.UTF_8) { lines ->
                    lines.forEachIndexed { index, line ->
                        if (line.contains(needle, ignoreCase = true) && matches.size < maxMatches) {
                            matches += "$relative:${index + 1}: ${line.trim().take(220)}"
                        }
                    }
                }
            } catch (_: Exception) {
                // Binary, unreadable or OS-protected paths are skipped.
            }
        }
        val suffix = if (candidates.size > MAX_SEARCH_FILES) "\n… جست‌وجو به $MAX_SEARCH_FILES فایل محدود شد." else ""
        val result = matches.joinToString("\n").ifBlank { "چیزی برای «$needle» پیدا نشد." }
        return "جست‌وجو در حافظهٔ اشتراکی (${root.path})\n$result$suffix"
    }

    /** Validate and preview the direct write before the approval dialog is shown. */
    fun writePreview(path: String, content: String): String {
        requireAccess()
        val normalized = normalizePath(path)
        require(normalized.isNotBlank()) { "مسیر فایل خالی است." }
        require(!isSensitivePath(normalized)) { "نوشتن در مسیر احتمالا محرمانه از طریق ابزار ایجنت مسدود است." }
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_WRITE_BYTES) {
            "محتوای فایل از حد ${MAX_WRITE_BYTES / 1024 / 1024} مگابایت بیشتر است."
        }
        rejectSystemProtectedPath(normalized)
        val target = resolve(storageRoot(), normalized)
        require(!target.exists() || target.isFile) { "مسیر مقصد پوشه است، نه فایل: $normalized" }
        val operation = if (target.exists()) "جایگزینی فایل موجود" else "ایجاد فایل جدید"
        val preview = content.take(2200) + if (content.length > 2200) "\n… پیش‌نمایش کوتاه شد." else ""
        return "عملیات: $operation\nمسیر دقیق: ${target.path}\nحجم: ${bytes.size} بایت\n\nپیش‌نمایش محتوا:\n$preview"
    }

    private fun rejectSystemProtectedPath(normalized: String) {
        if (normalized.equals("Android/data", true) ||
            normalized.startsWith("Android/data/", true) ||
            normalized.equals("Android/obb", true) ||
            normalized.startsWith("Android/obb/", true)) {
            error("اندروید دسترسی مستقیم به Android/data و Android/obb را مسدود می‌کند؛ این محدودیت با All files access هم برداشته نمی‌شود.")
        }
    }

    /** Called only after MainActivity shows the exact destination and asks the user to approve. */
    fun writeFilePayload(payload: String): String {
        requireAccess()
        val data = JSONObject(payload)
        val normalized = normalizePath(data.optString("path", ""))
        val content = data.optString("content", "")
        require(normalized.isNotBlank()) { "مسیر فایل خالی است." }
        require(!isSensitivePath(normalized)) { "نوشتن در مسیر احتمالا محرمانه از طریق ابزار ایجنت مسدود است." }
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_WRITE_BYTES) {
            "محتوای فایل از حد ${MAX_WRITE_BYTES / 1024 / 1024} مگابایت بیشتر است."
        }
        rejectSystemProtectedPath(normalized)

        val root = storageRoot()
        var target = resolve(root, normalized)
        require(!target.exists() || target.isFile) { "مسیر مقصد پوشه است، نه فایل: $normalized" }
        val parent = target.parentFile ?: error("مسیر والد نامعتبر است.")
        check(parent.mkdirs() || parent.isDirectory) { "ساخت پوشهٔ والد ناموفق بود: ${parent.path}" }
        // Re-resolve after mkdirs so a symlinked parent cannot redirect writes outside shared storage.
        target = resolve(root, normalized)
        require(!target.exists() || target.isFile) { "مسیر مقصد پوشه است، نه فایل: $normalized" }

        val temp = File(parent, ".${target.name}.${System.nanoTime()}.paa-tmp")
        try {
            temp.writeBytes(bytes)
            try {
                Files.move(
                    temp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return "فایل مستقیم در حافظهٔ اشتراکی ذخیره شد: ${target.path} (${target.length()} bytes)."
        } finally {
            temp.delete()
        }
    }

    private fun requireAccess() {
        check(hasAccess()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                "دسترسی All files access فعال نیست. از دکمهٔ «دسترسی حافظه» در برنامه، اجازهٔ «مدیریت همهٔ فایل‌ها» را در تنظیمات روشن کن و دوباره تلاش کن."
            } else {
                "دسترسی مستقیم سراسری در این نسخهٔ اندروید پشتیبانی نمی‌شود؛ یک پوشه را با دکمهٔ انتخاب پروژه انتخاب کن."
            }
        }
    }

    private fun storageRoot(): File = Environment.getExternalStorageDirectory().canonicalFile

    private fun resolve(root: File, path: String): File {
        val normalized = normalizePath(path)
        val target = if (normalized.isBlank()) root.canonicalFile else File(root, normalized).canonicalFile
        require(target == root.canonicalFile || isInside(root, target)) {
            "مسیر از حافظهٔ اشتراکی خارج می‌شود؛ فقط مسیرهای نسبی زیر ${root.path} مجازند."
        }
        return target
    }

    private fun normalizePath(path: String): String {
        require(path.length <= MAX_PATH_CHARS && !path.contains('\u0000')) { "مسیر نامعتبر یا بیش از حد بلند است." }
        val normalized = path.trim().replace('\\', '/').trim('/')
        if (normalized.isBlank()) return ""
        require(!normalized.startsWith("/") && !Regex("^[A-Za-z]:").containsMatchIn(normalized)) {
            "مسیر باید نسبت به ریشهٔ حافظه باشد، نه مسیر مطلق."
        }
        val parts = normalized.split('/')
        require(parts.all { it.isNotBlank() && it != "." && it != ".." }) {
            "مسیر نامعتبر است؛ بخش‌های . و .. مجاز نیستند."
        }
        return parts.joinToString("/")
    }

    private fun isInside(root: File, candidate: File): Boolean = runCatching {
        val base = root.canonicalPath
        val path = candidate.canonicalPath
        path.startsWith(base + File.separator)
    }.getOrDefault(false)

    private fun isSensitivePath(path: String): Boolean {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotBlank() }.map { it.lowercase() }
        return parts.any { name ->
            name in SENSITIVE_NAMES || name.endsWith(".pem") || name.endsWith(".key") ||
                name.endsWith(".p12") || name.endsWith(".pfx") || name.endsWith(".keystore") ||
                name.endsWith(".jks") || name.endsWith(".secret") || name.endsWith(".token") ||
                (name.startsWith(".env.") && name !in SAFE_ENV_EXAMPLES)
        }
    }

    private fun looksBinary(bytes: ByteArray, extension: String): Boolean {
        if (extension.lowercase() in TEXT_EXTENSIONS) return false
        if (bytes.any { it == 0.toByte() }) return true
        if (bytes.isEmpty()) return false
        val controls = bytes.count { (it.toInt() and 0xff) < 9 || ((it.toInt() and 0xff) in 14..31) }
        return controls.toDouble() / bytes.size > 0.08
    }

    companion object {
        private const val MAX_PATH_CHARS = 1024
        private const val MAX_READ_BYTES = 512L * 1024L
        private const val MAX_WRITE_BYTES = 2L * 1024L * 1024L
        private const val MAX_SEARCH_FILE_BYTES = 256L * 1024L
        private const val MAX_SEARCH_FILES = 2_500
        private const val MAX_LIST_ENTRIES = 300
        private val SENSITIVE_NAMES = setOf(
            ".env", ".npmrc", ".pypirc", ".netrc", ".git-credentials",
            "credentials", "credentials.json", "service-account.json", "secrets.json",
            "secret.json", "token", "token.json", "tokens.json", "id_rsa", "id_ed25519"
        )
        private val SAFE_ENV_EXAMPLES = setOf(".env.example", ".env.sample", ".env.template", ".env.dist")
        private val TEXT_EXTENSIONS = setOf(
            "kt", "kts", "java", "xml", "json", "md", "txt", "yml", "yaml", "toml",
            "properties", "gradle", "sh", "bash", "py", "js", "jsx", "ts", "tsx",
            "html", "css", "scss", "sql", "c", "h", "cpp", "hpp", "rs", "go",
            "rb", "php", "swift", "dart", "vue", "svelte", "gitignore", "editorconfig",
            "env", "conf", "ini", "cfg", "log", "csv", "svg", "makefile", "mk"
        )
    }
}
