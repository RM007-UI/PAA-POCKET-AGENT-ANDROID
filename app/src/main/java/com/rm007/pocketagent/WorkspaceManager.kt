package com.rm007.pocketagent

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Keeps a private working copy of the user-selected SAF folder. Agent edits never touch
 * the original project until the user explicitly applies the reviewed change set.
 */
class WorkspaceManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("pocket_agent_workspace", Context.MODE_PRIVATE)
    private val rootCache = File(context.filesDir, "agent-workspaces")

    data class Change(
        val path: String,
        val kind: String,
        val beforeBytes: Long,
        val afterBytes: Long
    )

    private data class Budget(var files: Int = 0, var bytes: Long = 0)

    fun isReady(): Boolean = selectedUri() != null && baselineDir().isDirectory && workDir().isDirectory
    fun displayName(): String = prefs.getString(KEY_NAME, null) ?: "پروژه‌ای انتخاب نشده"
    fun workDirectory(): File? = if (isReady()) workDir() else null

    fun importWorkspace(uri: Uri): String {
        val tree = DocumentFile.fromTreeUri(context, uri) ?: error("پوشهٔ انتخاب‌شده قابل دسترسی نیست.")
        require(tree.isDirectory) { "لطفاً یک پوشه انتخاب کن، نه یک فایل." }
        val id = workspaceId(uri.toString())
        val base = File(rootCache, "$id/base")
        val work = File(rootCache, "$id/work")
        deleteRecursively(File(rootCache, id))
        check(base.mkdirs() || base.isDirectory) { "ساخت پوشهٔ کاری ناموفق بود." }
        val budget = Budget()
        try {
            copyFromDocumentTree(tree, base, "", budget)
            check(work.mkdirs() || work.isDirectory) { "ساخت کپی کاری ناموفق بود." }
            copyInternalTree(base, work)
        } catch (e: Exception) {
            deleteRecursively(File(rootCache, id))
            throw IllegalStateException("خواندن پروژه ناموفق بود: ${e.message ?: e.javaClass.simpleName}", e)
        }
        prefs.edit()
            .putString(KEY_URI, uri.toString())
            .putString(KEY_NAME, tree.name?.takeIf { it.isNotBlank() } ?: "پروژه")
            .putString(KEY_ID, id)
            .apply()
        return "پروژه در پوشهٔ کاری خصوصی آماده شد. ${budget.files} فایل با حدود ${budget.bytes / 1024} کیلوبایت کپی شد. فایل اصلی هنوز تغییر نکرده است."
    }

    fun listFiles(path: String = ""): String {
        requireReady()
        val target = resolveWork(path)
        require(target.isDirectory) { "این مسیر پوشه نیست: $path" }
        val base = workDir()
        val lines = target.walkTopDown()
            .onEnter { directory -> directory == target || !shouldSkipDirectory(directory.name) }
            .filter { it != target }
            .take(MAX_LIST_ENTRIES + 1)
            .map { file ->
                val rel = file.relativeTo(base).invariantSeparatorsPath
                if (file.isDirectory) "$rel/" else "$rel (${file.length()} bytes)"
            }
            .toList()
        val truncated = lines.size > MAX_LIST_ENTRIES
        return (lines.take(MAX_LIST_ENTRIES).joinToString("\n").ifBlank { "(پوشه خالی است)" }) +
            if (truncated) "\n… خروجی به $MAX_LIST_ENTRIES مورد محدود شد." else ""
    }

    fun readFile(path: String): String {
        requireReady()
        val file = resolveWork(path)
        require(file.isFile) { "فایل پیدا نشد: $path" }
        require(!isSensitivePath(normalizePath(path))) { "برای محافظت از اطلاعات، خواندن فایل‌های احتمالا محرمانه از طریق ایجنت مسدود است." }
        require(file.length() <= MAX_READ_BYTES) { "این فایل بزرگ است (${file.length()} بایت). لطفاً بخش موردنیاز را جدا کن یا با ابزار دیگری بررسی‌اش کن." }
        val bytes = file.readBytes()
        if (looksBinary(bytes, file.extension)) return "فایل باینری است و به صورت متن باز نمی‌شود: $path (${bytes.size} bytes)."
        return String(bytes, Charsets.UTF_8)
    }

    fun searchFiles(query: String, maxMatches: Int = 30): String {
        requireReady()
        val needle = query.trim()
        require(needle.isNotEmpty()) { "عبارت جست‌وجو خالی است." }
        val matches = mutableListOf<String>()
        var checked = 0
        workDir().walkTopDown()
            .onEnter { directory -> directory == workDir() || !shouldSkipDirectory(directory.name) }
            .filter { it.isFile }
            .forEach { file ->
                if (matches.size >= maxMatches || checked >= MAX_SEARCH_FILES) return@forEach
                if (isSensitivePath(file.relativeTo(workDir()).invariantSeparatorsPath)) return@forEach
                if (file.length() > MAX_SEARCH_FILE_BYTES || looksBinaryHeader(file)) return@forEach
                checked++
                try {
                    file.useLines(Charsets.UTF_8) { lines ->
                        lines.forEachIndexed { index, line ->
                            if (line.contains(needle, ignoreCase = true) && matches.size < maxMatches) {
                                val rel = file.relativeTo(workDir()).invariantSeparatorsPath
                                matches += "$rel:${index + 1}: ${line.trim().take(260)}"
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Ignore unreadable or non-text files while searching source.
                }
            }
        val suffix = if (checked >= MAX_SEARCH_FILES) "\n… جست‌وجو به $MAX_SEARCH_FILES فایل محدود شد." else ""
        return matches.joinToString("\n").ifBlank { "چیزی برای «$needle» پیدا نشد." } + suffix
    }

    fun writeFile(path: String, content: String): String {
        requireReady()
        val normalized = normalizePath(path)
        require(normalized.isNotBlank()) { "مسیر فایل خالی است." }
        require(!isSensitivePath(normalized)) { "نوشتن در مسیرهای احتمالا محرمانه از طریق ایجنت مجاز نیست." }
        require(content.toByteArray(Charsets.UTF_8).size <= MAX_WRITE_BYTES) { "محتوای فایل بیش از حد بزرگ است." }
        val file = resolveWork(normalized)
        check(isInside(workDir(), file)) { "مسیر از پوشهٔ کاری خارج می‌شود." }
        val parent = file.parentFile ?: error("مسیر والد نامعتبر است.")
        check(parent.mkdirs() || parent.isDirectory) { "ساخت پوشهٔ والد ناموفق بود." }
        file.writeText(content, Charsets.UTF_8)
        return "تغییر در کپی کاری ذخیره شد: $normalized (${file.length()} bytes). فایل اصلی دست‌نخورده است تا زمانی که از بخش «تغییرات» آن را اعمال کنی."
    }

    fun deleteFile(path: String): String {
        requireReady()
        val normalized = normalizePath(path)
        require(normalized.isNotBlank()) { "حذف ریشهٔ پروژه مجاز نیست." }
        require(!isSensitivePath(normalized)) { "حذف فایل‌های احتمالا محرمانه از طریق ایجنت مسدود است." }
        val file = resolveWork(normalized)
        require(file.exists() && file.isFile) { "فایل عادی پیدا نشد؛ حذف پوشه با این ابزار پشتیبانی نمی‌شود." }
        check(isInside(workDir(), file)) { "مسیر از پوشهٔ کاری خارج می‌شود." }
        check(file.delete()) { "حذف از کپی کاری ناموفق بود." }
        return "حذف در کپی کاری ثبت شد: $normalized. فایل اصلی فقط پس از تأیید از بخش «تغییرات» حذف خواهد شد."
    }

    fun changes(): List<Change> = changesBetween(baselineDir(), workDir())

    /** Changes to push remotely: after the first successful push, compare against that pushed snapshot. */
    fun githubChanges(): List<Change> {
        requireReady()
        // Keep the import-time snapshot separate from local apply operations. It represents the
        // expected baseline of the remote repository until the first successful GitHub push.
        val reference = githubBaseDir().takeIf { it.isDirectory }
            ?: remoteBaselineDir().takeIf { it.isDirectory }
            ?: baselineDir()
        return changesBetween(reference, workDir())
    }

    fun bytesForGitHub(path: String): ByteArray? {
        requireReady()
        val file = resolveWork(path)
        if (!file.exists()) return null
        require(file.isFile) { "فایل قابل آپلود نیست: $path" }
        require(file.length() <= MAX_GITHUB_FILE_BYTES) { "فایل ${file.name} از حد آپلود مجاز بیشتر است." }
        return file.readBytes()
    }

    fun markGitHubSynced() {
        requireReady()
        clearDirectory(githubBaseDir())
        copyInternalTree(workDir(), githubBaseDir())
    }

    fun hasGitHubSnapshot(): Boolean = githubBaseDir().isDirectory

    private fun changesBetween(referenceRoot: File, currentRoot: File): List<Change> {
        if (!currentRoot.isDirectory || !referenceRoot.isDirectory) return emptyList()
        val before = fileMap(referenceRoot)
        val after = fileMap(currentRoot)
        return (before.keys + after.keys).toSortedSet().mapNotNull { path ->
            val old = before[path]
            val now = after[path]
            when {
                old == null && now != null -> Change(path, "افزوده", 0, now.length())
                old != null && now == null -> Change(path, "حذف‌شده", old.length(), 0)
                old != null && now != null && sha256(old) != sha256(now) -> Change(path, "ویرایش‌شده", old.length(), now.length())
                else -> null
            }
        }
    }

    fun diffPreview(path: String, maxChars: Int = 12_000, againstGitHub: Boolean = false): String {
        requireReady()
        val normalized = normalizePath(path)
        val referenceRoot = if (againstGitHub) {
            githubBaseDir().takeIf { it.isDirectory }
                ?: remoteBaselineDir().takeIf { it.isDirectory }
                ?: baselineDir()
        } else baselineDir()
        val before = File(referenceRoot, normalized)
        val after = File(workDir(), normalized)
        val beforeText = if (before.isFile && before.length() <= MAX_READ_BYTES && !looksBinaryHeader(before)) before.readText(Charsets.UTF_8) else null
        val afterText = if (after.isFile && after.length() <= MAX_READ_BYTES && !looksBinaryHeader(after)) after.readText(Charsets.UTF_8) else null
        if (beforeText == null && afterText == null) {
            return "فایل باینری، بزرگ یا حذف‌شده است.\nقبل: ${before.length().takeIf { before.exists() } ?: 0} bytes\nبعد: ${after.length().takeIf { after.exists() } ?: 0} bytes"
        }
        val oldLines = beforeText.orEmpty().lines()
        val newLines = afterText.orEmpty().lines()
        var prefix = 0
        while (prefix < oldLines.size && prefix < newLines.size && oldLines[prefix] == newLines[prefix]) prefix++
        var suffix = 0
        while (suffix < oldLines.size - prefix && suffix < newLines.size - prefix &&
            oldLines[oldLines.lastIndex - suffix] == newLines[newLines.lastIndex - suffix]) suffix++
        val out = StringBuilder()
        if (prefix > 0) {
            out.append("  … ${prefix} خط بدون تغییر …\n")
        }
        val oldEnd = oldLines.size - suffix
        val newEnd = newLines.size - suffix
        for (i in prefix until oldEnd) out.append("- ").append(oldLines[i]).append('\n')
        for (i in prefix until newEnd) out.append("+ ").append(newLines[i]).append('\n')
        if (suffix > 0) out.append("  … ${suffix} خط بدون تغییر …\n")
        if (out.isEmpty()) out.append("محتوا یکسان است.")
        if (out.length > maxChars) out.setLength(maxChars)
        if (out.length >= maxChars) out.append("\n… پیش‌نمایش کوتاه شد.")
        return out.toString()
    }

    fun discardChanges() {
        requireReady()
        // Discard only unapplied local edits. Do not roll the workspace back to a different
        // remote snapshot; GitHub sync has its own reference and is tracked independently.
        clearDirectory(workDir())
        copyInternalTree(baselineDir(), workDir())
    }

    fun applyChanges(): Int {
        requireReady()
        val uri = selectedUri() ?: error("پوشهٔ اصلی انتخاب نشده است.")
        val tree = DocumentFile.fromTreeUri(context, uri) ?: error("مجوز دسترسی به پوشهٔ اصلی از بین رفته است؛ دوباره پوشه را انتخاب کن.")
        val changes = changes()
        for (change in changes) {
            val parts = normalizePath(change.path).split('/')
            var parent = tree
            for (segment in parts.dropLast(1)) {
                var next = parent.findFile(segment)
                if (next == null) next = parent.createDirectory(segment) ?: error("ساخت پوشهٔ ${change.path} ناموفق بود.")
                check(next.isDirectory) { "مسیر والد فایل پوشه نیست: ${change.path}" }
                parent = next
            }
            val name = parts.last()
            val existing = parent.findFile(name)
            when (change.kind) {
                "حذف‌شده" -> {
                    if (existing != null) {
                        check(!existing.isDirectory) { "حذف پوشه به صورت مستقیم مجاز نیست: ${change.path}" }
                        check(existing.delete()) { "حذف فایل اصلی ناموفق بود: ${change.path}" }
                    }
                }
                else -> {
                    val source = resolveWork(change.path)
                    check(source.isFile) { "فایل در کپی کاری پیدا نشد: ${change.path}" }
                    val target = existing ?: parent.createFile(mimeFor(source.name), name)
                        ?: error("ساخت فایل اصلی ناموفق بود: ${change.path}")
                    check(!target.isDirectory) { "مسیر مقصد به جای فایل، پوشه است: ${change.path}" }
                    context.contentResolver.openOutputStream(target.uri, "wt")?.use { output -> source.inputStream().use { input -> input.copyTo(output) } }
                        ?: error("نوشتن فایل اصلی ناموفق بود: ${change.path}")
                }
            }
        }
        // Preserve the import-time state the first time local Apply would otherwise replace it;
        // GitHub's first diff is measured against that original project snapshot.
        if (!remoteBaselineDir().isDirectory && !githubBaseDir().isDirectory) {
            copyInternalTree(baselineDir(), remoteBaselineDir())
        }
        clearDirectory(baselineDir())
        copyInternalTree(workDir(), baselineDir())
        return changes.size
    }

    private fun copyFromDocumentTree(source: DocumentFile, destination: File, relative: String, budget: Budget) {
        if (source.isDirectory) {
            if (relative.isNotBlank() && shouldSkipDirectory(source.name.orEmpty())) return
            check(destination.mkdirs() || destination.isDirectory) { "ساخت پوشه ناموفق بود: $relative" }
            val children = source.listFiles().sortedBy { it.name.orEmpty().lowercase() }
            for (child in children) {
                val name = child.name ?: continue
                if (name == "." || name == ".." || name.contains('/') || name.contains('\\')) continue
                copyFromDocumentTree(child, File(destination, name), if (relative.isBlank()) name else "$relative/$name", budget)
            }
        } else {
            if (isSensitivePath(relative)) return
            budget.files++
            check(budget.files <= MAX_FILES) { "تعداد فایل‌های پروژه از حد مجاز ($MAX_FILES) بیشتر است." }
            val input = context.contentResolver.openInputStream(source.uri) ?: error("خواندن فایل ممکن نشد: $relative")
            val destinationParent = destination.parentFile
            check(destinationParent == null || destinationParent.mkdirs() || destinationParent.isDirectory) { "ساخت پوشهٔ والد ناموفق بود." }
            FileOutputStream(destination).use { output ->
                input.use { stream ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        budget.bytes += count
                        check(budget.bytes <= MAX_WORKSPACE_BYTES) { "حجم پروژه از حد مجاز (${MAX_WORKSPACE_BYTES / 1024 / 1024} MB) بیشتر است." }
                        output.write(buffer, 0, count)
                    }
                }
            }
        }
    }

    private fun copyInternalTree(source: File, destination: File) {
        check(destination.mkdirs() || destination.isDirectory) { "ساخت کپی داخلی ناموفق بود." }
        source.listFiles()?.forEach { child ->
            val target = File(destination, child.name)
            if (child.isDirectory) copyInternalTree(child, target)
            else child.inputStream().use { input -> FileOutputStream(target).use { input.copyTo(it) } }
        }
    }

    private fun fileMap(root: File): Map<String, File> = root.walkTopDown()
        .onEnter { directory -> directory == root || !shouldSkipDirectory(directory.name) }
        .filter { it.isFile && it.relativeTo(root).invariantSeparatorsPath.split('/').none { part -> shouldSkipDirectory(part) } }
        .filter { !isSensitivePath(it.relativeTo(root).invariantSeparatorsPath) }
        .associateBy { it.relativeTo(root).invariantSeparatorsPath }

    private fun resolveWork(path: String): File {
        val normalized = normalizePath(path)
        val base = workDir().canonicalFile
        val target = if (normalized.isBlank()) base else File(base, normalized).canonicalFile
        require(target == base || target.path.startsWith(base.path + File.separator)) { "مسیر از پوشهٔ کاری خارج می‌شود." }
        return target
    }

    private fun normalizePath(path: String): String {
        require(path.length <= 1024 && !path.contains('\u0000')) { "مسیر نامعتبر یا بیش از حد بلند است." }
        val normalized = path.trim().replace('\\', '/').trim('/')
        if (normalized.isBlank()) return ""
        val parts = normalized.split('/')
        require(parts.all { it.isNotBlank() && it != "." && it != ".." }) { "مسیر باید نسبی باشد و نباید شامل .. یا بخش‌های خالی شود." }
        require(parts.none { shouldSkipDirectory(it) }) { "دسترسی به پوشه‌های تولیدشده یا فرادادهٔ داخلی مانند .git مجاز نیست." }
        require(!isSensitivePath(parts.joinToString("/"))) { "مسیر احتمالا محرمانه است و از طریق ایجنت در دسترس نیست." }
        return parts.joinToString("/")
    }

    private fun requireReady() = check(isReady()) { "اول از بالای برنامه یک پوشهٔ پروژه انتخاب کن." }
    private fun selectedUri(): Uri? = prefs.getString(KEY_URI, null)?.let { Uri.parse(it) }
    private fun id(): String = prefs.getString(KEY_ID, null) ?: "none"
    private fun baselineDir() = File(rootCache, "${id()}/base")
    private fun remoteBaselineDir() = File(rootCache, "${id()}/remote-base")
    private fun workDir() = File(rootCache, "${id()}/work")
    private fun githubBaseDir() = File(rootCache, "${id()}/github-base")
    private fun workspaceId(value: String): String = sha256(value.toByteArray()).take(24)
    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun isInside(root: File, target: File): Boolean = runCatching {
        val base = root.canonicalPath
        val path = target.canonicalPath
        path.startsWith(base + File.separator)
    }.getOrDefault(false)

    private fun looksBinary(bytes: ByteArray, extension: String): Boolean {
        if (extension.lowercase() in TEXT_EXTENSIONS) return false
        if (bytes.any { it == 0.toByte() }) return true
        if (bytes.isEmpty()) return false
        val controls = bytes.count { (it.toInt() and 0xff) < 9 || ((it.toInt() and 0xff) in 14..31) }
        return controls.toDouble() / bytes.size > 0.08
    }
    private fun looksBinaryHeader(file: File): Boolean {
        if (file.extension.lowercase() in BINARY_EXTENSIONS) return true
        return try { file.inputStream().use { input -> val bytes = ByteArray(1024); val n = input.read(bytes); n > 0 && bytes.take(n).any { it == 0.toByte() } } } catch (_: Exception) { true }
    }
    private fun shouldSkipDirectory(name: String): Boolean = name.lowercase() in IGNORED_DIRECTORIES
    private fun isSensitivePath(path: String): Boolean {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotBlank() }.map { it.lowercase() }
        return parts.any { name ->
            name in SENSITIVE_NAMES ||
                name.endsWith(".pem") || name.endsWith(".key") || name.endsWith(".p12") ||
                name.endsWith(".pfx") || name.endsWith(".keystore") || name.endsWith(".jks") ||
                name.endsWith(".secret") || name.endsWith(".token") ||
                (name.startsWith(".env.") && name !in SAFE_ENV_EXAMPLES)
        }
    }
    private fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: if (ext in TEXT_EXTENSIONS) "text/plain" else "application/octet-stream"
    }
    private fun clearDirectory(directory: File) { if (directory.exists()) directory.listFiles()?.forEach(::deleteRecursively) }
    private fun deleteRecursively(file: File) { if (file.isDirectory) file.listFiles()?.forEach(::deleteRecursively); file.delete() }

    companion object {
        private const val KEY_URI = "selected_tree_uri"
        private const val KEY_NAME = "selected_tree_name"
        private const val KEY_ID = "selected_tree_id"
        private const val MAX_FILES = 10_000
        private const val MAX_WORKSPACE_BYTES = 150L * 1024L * 1024L
        private const val MAX_READ_BYTES = 512L * 1024L
        private const val MAX_WRITE_BYTES = 2L * 1024L * 1024L
        private const val MAX_GITHUB_FILE_BYTES = 5L * 1024L * 1024L
        private const val MAX_SEARCH_FILE_BYTES = 256L * 1024L
        private const val MAX_SEARCH_FILES = 2_500
        private const val MAX_LIST_ENTRIES = 180
        private val IGNORED_DIRECTORIES = setOf(".git", ".gradle", ".idea", ".kotlin", ".next", ".expo", "node_modules", "build", "dist", "target", "out", "__pycache__", ".venv", "venv", "pods", "deriveddata")
        private val TEXT_EXTENSIONS = setOf("kt", "kts", "java", "xml", "json", "md", "txt", "yml", "yaml", "toml", "properties", "gradle", "sh", "bash", "py", "js", "jsx", "ts", "tsx", "html", "css", "scss", "sql", "c", "h", "cpp", "hpp", "rs", "go", "rb", "php", "swift", "dart", "vue", "svelte", "gitignore", "editorconfig", "env", "conf", "ini", "cfg", "log", "csv", "svg", "makefile", "mk")
        private val SENSITIVE_NAMES = setOf(".env", ".npmrc", ".pypirc", ".netrc", ".git-credentials", "credentials", "credentials.json", "service-account.json", "secrets.json", "secret.json", "token", "token.json", "tokens.json", "id_rsa", "id_ed25519", "known_hosts.private")
        private val SAFE_ENV_EXAMPLES = setOf(".env.example", ".env.sample", ".env.template", ".env.dist")
        private val BINARY_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "ico", "icns", "heic", "mp3", "m4a", "ogg", "wav", "mp4", "mkv", "mov", "pdf", "zip", "gz", "7z", "rar", "jar", "class", "dex", "so", "apk", "aab", "woff", "woff2", "ttf", "otf", "db", "sqlite", "keystore", "jks", "bin", "dat", "pb", "model", "tflite")
    }
}
