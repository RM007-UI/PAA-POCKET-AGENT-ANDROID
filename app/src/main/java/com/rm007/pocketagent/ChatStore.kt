package com.rm007.pocketagent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class ChatInfo(val id: String, val title: String, val updatedAt: Long)
data class StoredMessage(val role: String, val text: String)

object AgentHome {
    fun rootDirectory(context: Context): File {
        val external = context.getExternalFilesDir(null)
        val preferred = if (external != null) File(external, "PocketAgent") else File(context.filesDir, "PocketAgent")
        if (!preferred.exists() && !preferred.mkdirs()) {
            val fallback = File(context.filesDir, "PocketAgent")
            check(fallback.mkdirs() || fallback.isDirectory) { "ساخت پوشهٔ PocketAgent ناموفق بود." }
            return fallback
        }
        check(preferred.isDirectory) { "مسیر PocketAgent پوشه نیست." }
        File(preferred, "chats").mkdirs()
        File(preferred, "workspaces").mkdirs()
        return preferred
    }

    fun privateBinDirectory(context: Context): File =
        File(context.filesDir, "PocketAgent/bin").apply {
            check(mkdirs() || isDirectory) { "ساخت پوشهٔ ابزارهای ترمینال ناموفق بود." }
        }
}

class ChatStore(context: Context) {
    val homeDirectory: File = AgentHome.rootDirectory(context.applicationContext)
    private val chatsDirectory = File(homeDirectory, "chats").apply { mkdirs() }
    private val indexFile = File(homeDirectory, "chat-index.json")
    private val prefs = context.applicationContext.getSharedPreferences("pocket_agent_chats", Context.MODE_PRIVATE)
    private val memoryFile = File(homeDirectory, "GLOBAL_MEMORY.md")

    init {
        if (listChats().isEmpty()) createChat()
        if (!memoryFile.exists()) memoryFile.writeText(
            "# حافظهٔ کلی Pocket Agent\n\nاین حافظه بین همهٔ گفتگوها مشترک است. اطلاعات حساس و رمزها را در آن ذخیره نکن.\n",
            Charsets.UTF_8
        )
    }

    @Synchronized
    fun listChats(): List<ChatInfo> {
        val array = try { JSONArray(if (indexFile.isFile) indexFile.readText(Charsets.UTF_8) else "[]") }
            catch (_: Exception) { JSONArray() }
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val id = item.optString("id", "")
            if (!validId(id)) return@mapNotNull null
            ChatInfo(id, item.optString("title", "گفت‌وگوی تازه"), item.optLong("updatedAt", 0L))
        }.sortedByDescending { it.updatedAt }
    }

    @Synchronized
    fun currentChatId(): String {
        val stored = prefs.getString(KEY_CURRENT_CHAT, null)
        if (stored != null && listChats().any { it.id == stored }) return stored
        val next = listChats().firstOrNull()?.id ?: createChat()
        prefs.edit().putString(KEY_CURRENT_CHAT, next).apply()
        return next
    }

    @Synchronized
    fun createChat(): String {
        val id = UUID.randomUUID().toString()
        val chats = listChats().toMutableList()
        chats += ChatInfo(id, "گفت‌وگوی تازه", System.currentTimeMillis())
        writeIndex(chats)
        saveMessages(id, emptyList())
        saveModelHistory(id, JSONArray())
        prefs.edit().putString(KEY_CURRENT_CHAT, id).apply()
        return id
    }

    @Synchronized
    fun selectChat(id: String) {
        require(validId(id) && listChats().any { it.id == id }) { "گفت‌وگو پیدا نشد." }
        prefs.edit().putString(KEY_CURRENT_CHAT, id).apply()
    }

    @Synchronized
    fun updateTitleFromFirstMessage(id: String, text: String) {
        val chat = listChats().firstOrNull { it.id == id } ?: return
        if (chat.title != "گفت‌وگوی تازه") return
        val title = text.replace(Regex("\\s+"), " ").trim().take(44).ifBlank { return }
        writeIndex(listChats().map { if (it.id == id) it.copy(title = title, updatedAt = System.currentTimeMillis()) else it })
    }

    @Synchronized
    fun loadMessages(id: String): List<StoredMessage> {
        val file = chatFile(id, "messages.json")
        val array = try { JSONArray(if (file.isFile) file.readText(Charsets.UTF_8) else "[]") }
            catch (_: Exception) { JSONArray() }
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val role = item.optString("role", "")
            if (role !in setOf("user", "assistant", "system", "tool")) return@mapNotNull null
            StoredMessage(role, item.optString("text", ""))
        }
    }

    @Synchronized
    fun saveMessages(id: String, messages: List<StoredMessage>) {
        val array = JSONArray()
        messages.takeLast(MAX_VISIBLE_MESSAGES).forEach { array.put(JSONObject().put("role", it.role).put("text", it.text.take(40_000))) }
        atomicWrite(chatFile(id, "messages.json"), array.toString())
        writeIndex(listChats().map { if (it.id == id) it.copy(updatedAt = System.currentTimeMillis()) else it })
    }

    @Synchronized
    fun loadModelHistory(id: String): JSONArray {
        val file = chatFile(id, "model-history.json")
        return try { JSONArray(if (file.isFile) file.readText(Charsets.UTF_8) else "[]") }
            catch (_: Exception) { JSONArray() }
    }

    @Synchronized
    fun saveModelHistory(id: String, history: JSONArray) {
        atomicWrite(chatFile(id, "model-history.json"), history.toString())
    }

    @Synchronized
    fun readGlobalMemory(): String = if (memoryFile.isFile) memoryFile.readText(Charsets.UTF_8).take(MAX_MEMORY_CHARS) else ""

    @Synchronized
    fun replaceGlobalMemory(value: String) {
        require(value.length <= MAX_MEMORY_CHARS) { "حافظهٔ کلی حداکثر $MAX_MEMORY_CHARS نویسه است." }
        atomicWrite(memoryFile, value)
    }

    @Synchronized
    fun rememberGlobal(note: String): String {
        val clean = note.trim().replace(Regex("[\\t ]+"), " ").take(700)
        require(clean.isNotBlank()) { "یادداشت حافظه خالی است." }
        require(!clean.contains('\u0000')) { "یادداشت حافظه نامعتبر است." }
        val current = readGlobalMemory()
        if (current.contains(clean, ignoreCase = true)) return "این نکته از قبل در حافظهٔ کلی وجود دارد."
        val updated = (current.trimEnd() + "\n- " + clean + "\n").let {
            if (it.length <= MAX_MEMORY_CHARS) it else it.takeLast(MAX_MEMORY_CHARS)
        }
        atomicWrite(memoryFile, updated)
        return "نکته به حافظهٔ کلی مشترک بین گفتگوها اضافه شد."
    }

    private fun chatFile(id: String, name: String): File {
        require(validId(id)) { "شناسهٔ گفت‌وگو نامعتبر است." }
        return File(File(chatsDirectory, id).apply { mkdirs() }, name)
    }

    private fun writeIndex(chats: List<ChatInfo>) {
        val array = JSONArray()
        chats.distinctBy { it.id }.take(MAX_CHATS).forEach { chat ->
            array.put(JSONObject().put("id", chat.id).put("title", chat.title.take(80)).put("updatedAt", chat.updatedAt))
        }
        atomicWrite(indexFile, array.toString())
    }

    private fun atomicWrite(file: File, contents: String) {
        check(file.parentFile?.mkdirs() != false) { "ساخت پوشهٔ داده ناموفق بود." }
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(contents, Charsets.UTF_8)
        if (!temporary.renameTo(file)) {
            file.writeText(contents, Charsets.UTF_8)
            temporary.delete()
        }
    }

    private fun validId(id: String) = id.matches(Regex("[a-fA-F0-9-]{36}"))

    companion object {
        private const val KEY_CURRENT_CHAT = "current_chat_id"
        private const val MAX_CHATS = 100
        private const val MAX_VISIBLE_MESSAGES = 800
        private const val MAX_MEMORY_CHARS = 24_000
    }
}
