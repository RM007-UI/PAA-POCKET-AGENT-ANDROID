package com.rm007.pocketagent

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

class TerminalTools(context: Context) {
    private val appContext = context.applicationContext
    fun binDirectory(): File = AgentHome.privateBinDirectory(appContext)

    fun listTools(): String {
        val files = binDirectory().listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) return "هنوز ابزار محلی نصب نشده است."
        return files.joinToString("\n") { "${it.name} · ${it.length()} بایت" }
    }

    fun installScriptPayload(payload: String): String {
        val data = JSONObject(payload)
        val name = validateName(data.optString("name", ""))
        val script = data.optString("script", "")
        require(script.isNotBlank() && script.length <= MAX_SCRIPT_CHARS && !script.contains('\u0000')) {
            "اسکریپت خالی، نامعتبر یا بیش از حد بزرگ است."
        }
        val target = File(binDirectory(), name)
        val text = if (script.startsWith("#!")) script else "#!/system/bin/sh\n$script"
        val temp = File(binDirectory(), ".$name.tmp")
        temp.writeText(text, Charsets.UTF_8)
        if (target.exists() && !target.delete()) { temp.delete(); error("جایگزینی ابزار قبلی ناموفق بود.") }
        check(temp.renameTo(target)) { "ذخیرهٔ ابزار ناموفق بود." }
        target.setExecutable(true, false)
        return "اسکریپت «$name» در فضای خصوصی برنامه نصب شد: ${target.absolutePath}\nبرای استفاده، نام ابزار در ترمینال در PATH قرار دارد؛ فرمان‌های اجراشده همچنان باید تأیید شوند."
    }

    fun downloadBinaryPayload(payload: String): String {
        val data = JSONObject(payload)
        val name = validateName(data.optString("name", ""))
        val source = data.optString("url", "")
        val expectedHash = data.optString("sha256", "").lowercase()
        require(expectedHash.matches(Regex("[a-f0-9]{64}"))) { "برای نصب باینری، SHA-256 دقیق ۶۴ رقمی از منبع رسمی لازم است." }
        val url = URL(source)
        require(url.protocol.equals("https", true) && url.host.isNotBlank() && url.userInfo == null) {
            "ابزار فقط از URL مستقیم HTTPS و بدون اطلاعات ورود دانلود می‌شود."
        }
        val connection = url.openConnection() as? HttpsURLConnection ?: error("اتصال HTTPS در دسترس نیست.")
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        val target = File(binDirectory(), name)
        val temp = File(binDirectory(), ".$name.download")
        try {
            connection.connect()
            require(connection.responseCode in 200..299) { "سرور دانلود خطا داد: HTTP ${connection.responseCode}" }
            val announcedSize = connection.contentLengthLong
            require(announcedSize <= MAX_BINARY_BYTES) { "فایل بزرگ‌تر از سقف ۷۰ مگابایت است." }
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            connection.inputStream.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= MAX_BINARY_BYTES) { "دانلود از سقف ۷۰ مگابایت عبور کرد." }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            require(actualHash.equals(expectedHash, true)) {
                "بررسی صحت ناموفق بود؛ SHA-256 دریافت‌شده با مقدار اعلام‌شده یکی نیست. فایل نصب نشد."
            }
            if (target.exists() && !target.delete()) error("جایگزینی ابزار قبلی ناموفق بود.")
            check(temp.renameTo(target)) { "ذخیرهٔ فایل دانلودشده ناموفق بود." }
            target.setExecutable(true, false)
            return "ابزار «$name» با بررسی SHA-256 در فضای خصوصی برنامه ذخیره شد: ${target.absolutePath}\nاگر فرمت یا ABI با Android سازگار نباشد ممکن است قابل اجرا نباشد."
        } catch (e: Exception) {
            temp.delete()
            throw IllegalStateException("نصب ابزار شکست خورد: ${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            connection.disconnect()
        }
    }

    private fun validateName(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) && value != "." && value != "..") {
            "نام ابزار نامعتبر است؛ فقط حروف انگلیسی، عدد، نقطه، خط تیره و زیرخط مجاز است."
        }
        return value
    }

    companion object {
        private const val MAX_SCRIPT_CHARS = 64_000
        private const val MAX_BINARY_BYTES = 70L * 1024 * 1024
    }
}
