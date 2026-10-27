package com.rm007.pocketagent

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Pushes a reviewed working-copy diff as one commit using GitHub's Git Database REST API. */
class GitHubSync(
    private val prefs: SecurePrefs,
    private val workspace: WorkspaceManager
) {
    data class PushResult(val commitSha: String, val commitUrl: String, val filesPushed: Int)

    fun pushChanges(commitMessage: String = "Pocket Agent: update project"): PushResult {
        val repo = prefs.githubRepo().trim()
        val branch = prefs.githubBranch().trim().ifBlank { "main" }
        val token = prefs.githubToken()
        require(token.isNotBlank()) { "برای همگام‌سازی، توکن GitHub را در تنظیمات ذخیره کن." }
        require(REPO_PATTERN.matches(repo)) { "مخزن باید با قالب owner/repo وارد شود؛ برای مثال RM007-UI/Rooyesh-Study-Record-MLKit." }
        require(validBranch(branch)) { "نام branch نامعتبر است." }
        require(workspace.isReady()) { "اول پوشهٔ پروژه را انتخاب کن." }

        val changes = workspace.githubChanges()
        require(changes.isNotEmpty()) { "تغییر تازه‌ای برای ارسال به GitHub وجود ندارد." }
        require(changes.size <= MAX_CHANGES) { "این ارسال ${changes.size} فایل دارد؛ برای جلوگیری از تغییر ناقص، آن را به حداکثر $MAX_CHANGES فایل تقسیم کن." }

        val prepared = changes.map { change ->
            val bytes = workspace.bytesForGitHub(change.path)
            if (change.kind != "حذف‌شده") require(bytes != null) { "فایل برای ارسال پیدا نشد: ${change.path}" }
            change to bytes
        }
        val totalBytes = prepared.sumOf { it.second?.size?.toLong() ?: 0L }
        require(totalBytes <= MAX_TOTAL_BYTES) { "حجم تغییرات ${(totalBytes / 1024 / 1024)} MB است؛ سقف این ارسال ${MAX_TOTAL_BYTES / 1024 / 1024} MB است." }

        val repoPath = "repos/$repo"
        val refPath = "${repoPath}/git/ref/heads/${encodeRef(branch)}"
        val refObject = request("GET", refPath, null, token)
            .optJSONObject("object") ?: error("GitHub ref پاسخ معتبری نداد.")
        val parentSha = refObject.optString("sha", "")
        require(parentSha.isNotBlank()) { "SHA شاخهٔ مقصد از GitHub دریافت نشد." }

        val commit = request("GET", "$repoPath/git/commits/$parentSha", null, token)
        val baseTreeSha = commit.optJSONObject("tree")?.optString("sha", "") ?: ""
        require(baseTreeSha.isNotBlank()) { "درخت پایهٔ commit از GitHub دریافت نشد." }

        val existingModes = readTreeModes(repoPath, baseTreeSha, token)
        val treeEntries = JSONArray()
        for ((change, bytes) in prepared) {
            val entry = JSONObject()
                .put("path", change.path)
                .put("mode", existingModes[change.path] ?: if (change.path.endsWith(".sh")) "100755" else "100644")
                .put("type", "blob")
            if (change.kind == "حذف‌شده") {
                entry.put("sha", JSONObject.NULL)
            } else {
                val payload = JSONObject()
                    .put("content", Base64.encodeToString(bytes ?: error("محتوای فایل ${change.path} موجود نیست."), Base64.NO_WRAP))
                    .put("encoding", "base64")
                val blob = request("POST", "$repoPath/git/blobs", payload, token)
                val blobSha = blob.optString("sha", "")
                require(blobSha.isNotBlank()) { "GitHub برای فایل ${change.path} شناسهٔ blob برنگرداند." }
                entry.put("sha", blobSha)
            }
            treeEntries.put(entry)
        }

        val tree = request(
            "POST",
            "$repoPath/git/trees",
            JSONObject().put("base_tree", baseTreeSha).put("tree", treeEntries),
            token
        )
        val treeSha = tree.optString("sha", "")
        require(treeSha.isNotBlank()) { "GitHub درخت commit جدید را نساخت." }

        val createdCommit = request(
            "POST",
            "$repoPath/git/commits",
            JSONObject()
                .put("message", commitMessage.take(120))
                .put("tree", treeSha)
                .put("parents", JSONArray().put(parentSha)),
            token
        )
        val newCommitSha = createdCommit.optString("sha", "")
        require(newCommitSha.isNotBlank()) { "GitHub commit جدید را نساخت." }

        request(
            "PATCH",
            "$repoPath/git/refs/heads/${encodeRef(branch)}",
            JSONObject().put("sha", newCommitSha).put("force", false),
            token
        )

        // Remember this exact working copy as the GitHub sync point to avoid pushing it twice.
        workspace.markGitHubSynced()
        return PushResult(newCommitSha, "https://github.com/$repo/commit/$newCommitSha", changes.size)
    }

    private fun readTreeModes(repoPath: String, baseTreeSha: String, token: String): Map<String, String> {
        val response = request("GET", "$repoPath/git/trees/$baseTreeSha?recursive=1", null, token)
        val entries = response.optJSONArray("tree") ?: return emptyMap()
        val modes = HashMap<String, String>(entries.length())
        for (index in 0 until entries.length()) {
            val item = entries.optJSONObject(index) ?: continue
            if (item.optString("type") == "blob") {
                val path = item.optString("path", "")
                val mode = item.optString("mode", "")
                if (path.isNotBlank() && mode in setOf("100644", "100755")) modes[path] = mode
            }
        }
        return modes
    }

    private fun request(method: String, path: String, body: JSONObject?, token: String): JSONObject {
        val connection = (URL("https://api.github.com/$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 90_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "PocketAgent-Android")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (body != null) doOutput = true
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val detail = try { JSONObject(responseText).optString("message", responseText) } catch (_: Exception) { responseText }
                throw IllegalStateException("GitHub API ($status): ${detail.take(650)}")
            }
            if (responseText.isBlank()) return JSONObject()
            require(responseText.length <= MAX_RESPONSE_CHARS) { "پاسخ GitHub بیش از حد بزرگ است." }
            return JSONObject(responseText)
        } finally {
            connection.disconnect()
        }
    }

    private fun encodeRef(ref: String): String = ref.split('/').joinToString("/") { encodeSegment(it) }
    private fun encodeSegment(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun validBranch(branch: String): Boolean = branch.length in 1..180 &&
        !branch.startsWith('/') && !branch.endsWith('/') && !branch.contains("..") &&
        !branch.contains("//") && !branch.contains("@{") &&
        branch.split('/').all { segment -> segment.isNotBlank() && !segment.startsWith('.') && !segment.endsWith('.') && !segment.endsWith(".lock") && segment.matches(Regex("[A-Za-z0-9._-]+")) }

    companion object {
        private const val MAX_CHANGES = 40
        private const val MAX_TOTAL_BYTES = 15L * 1024L * 1024L
        private const val MAX_RESPONSE_CHARS = 6_000_000
        private val REPO_PATTERN = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
    }
}
