package com.rm007.pocketagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect

/**
 * Explicitly opt-in UI control. The user must enable this service manually in Android Settings.
 * Screen content is read only in response to an agent tool call; events are not continuously stored.
 */
class PhoneAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (activeService === this) activeService = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var activeService: PhoneAccessibilityService? = null

        fun isEnabled(): Boolean = activeService != null

        fun readScreenText(): String {
            val service = activeService ?: return "کنترل صفحه فعال نیست. از تنظیمات Android > Accessibility، سرویس Pocket Agent را خودت فعال کن."
            val root = service.rootInActiveWindow ?: return "پنجرهٔ فعال در دسترس نیست؛ برنامهٔ مقصد را در پیش‌زمینه باز کن."
            val output = StringBuilder("برنامهٔ پیش‌زمینه: " + (root.packageName?.toString() ?: "نامشخص") + "\n")
            var visited = 0
            var textItems = 0

            fun visit(node: AccessibilityNodeInfo, depth: Int) {
                if (visited >= 450 || depth > 24 || textItems >= 180) return
                visited++
                if (!node.isPassword) {
                    val text = node.text?.toString()?.trim().orEmpty()
                    val description = node.contentDescription?.toString()?.trim().orEmpty()
                    val value = when {
                        text.isNotBlank() && description.isNotBlank() && !text.equals(description, true) -> text + " · " + description
                        text.isNotBlank() -> text
                        description.isNotBlank() -> description
                        else -> ""
                    }
                    if (value.isNotBlank()) {
                        val bounds = Rect()
                        node.getBoundsInScreen(bounds)
                        output.append("[").append(textItems).append("] ")
                            .append(value.replace("\n", " ").take(240))
                            .append(" {x=").append(bounds.centerX())
                            .append(", y=").append(bounds.centerY())
                            .append(", clickable=").append(node.isClickable)
                            .append(", editable=").append(node.isEditable)
                            .append(", scrollable=").append(node.isScrollable)
                            .append("}\n")
                        textItems++
                    }
                }
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    visit(child, depth + 1)
                    if (visited >= 450 || textItems >= 180) break
                }
            }
            visit(root, 0)
            if (textItems == 0) output.append("(متن قابل‌دسترسی پیدا نشد؛ ممکن است صفحه محتوا را به‌صورت تصویر رسم کند.)")
            if (visited >= 450) output.append("\n… درخت صفحه به محدودیت 450 گره رسید.")
            return output.toString().take(9_000)
        }

        fun tapText(queryValue: String): String {
            val service = activeService ?: return "کنترل صفحه فعال نیست؛ سرویس Pocket Agent را در Accessibility فعال کن."
            val query = queryValue.trim()
            if (query.isBlank() || query.length > 180) return "متن هدف خالی یا بیش از حد طولانی است."
            val root = service.rootInActiveWindow ?: return "پنجرهٔ فعال در دسترس نیست."
            val candidates = try { root.findAccessibilityNodeInfosByText(query) } catch (e: Exception) {
                return "جست‌وجوی متن ناموفق بود: " + (e.message ?: e.javaClass.simpleName)
            }
            val target = candidates.firstOrNull { node ->
                !node.isPassword &&
                    (node.text?.toString()?.contains(query, true) == true ||
                     node.contentDescription?.toString()?.contains(query, true) == true)
            } ?: return "متنی مطابق «" + query + "» در صفحه پیدا نشد؛ ابتدا صفحه را بخوان."
            var clickable: AccessibilityNodeInfo? = target
            var hops = 0
            while (clickable != null && !clickable.isClickable && hops < 8) {
                clickable = clickable.parent
                hops++
            }
            val clickNode = clickable ?: target
            return if (clickNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                "لمس روی کنترل «" + query + "» درخواست شد."
            } else {
                "کنترل «" + query + "» پیدا شد، اما Android کلیک را نپذیرفت."
            }
        }

        fun tapAt(x: Int, y: Int): String {
            val service = activeService ?: return "کنترل صفحه فعال نیست؛ سرویس Pocket Agent را در Accessibility فعال کن."
            if (x < 0 || y < 0 || x >= service.resources.displayMetrics.widthPixels ||
                y >= service.resources.displayMetrics.heightPixels) return "مختصات خارج از محدودهٔ نمایش فعلی است."
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 90)).build()
            return if (service.dispatchGesture(gesture, null, null)) "لمس در مختصات (" + x + ", " + y + ") ارسال شد."
            else "Android حرکت لمس را نپذیرفت."
        }

        fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int): String {
            val service = activeService ?: return "کنترل صفحه فعال نیست؛ سرویس Pocket Agent را در Accessibility فعال کن."
            val width = service.resources.displayMetrics.widthPixels
            val height = service.resources.displayMetrics.heightPixels
            if (listOf(startX, startY, endX, endY).any { it < 0 } ||
                startX >= width || endX >= width || startY >= height || endY >= height) {
                return "یکی از مختصات خارج از محدودهٔ نمایش فعلی است."
            }
            if (durationMs !in 100..2_000) return "مدت حرکت باید بین 100 تا 2000 میلی‌ثانیه باشد."
            val path = Path().apply {
                moveTo(startX.toFloat(), startY.toFloat())
                lineTo(endX.toFloat(), endY.toFloat())
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.toLong())).build()
            return if (service.dispatchGesture(gesture, null, null)) "حرکت صفحه ارسال شد."
            else "Android حرکت صفحه را نپذیرفت."
        }

        fun typeText(value: String): String {
            val service = activeService ?: return "کنترل صفحه فعال نیست؛ سرویس Pocket Agent را در Accessibility فعال کن."
            if (value.length > 4_000) return "متن بیشتر از محدودیت 4000 نویسه است."
            val root = service.rootInActiveWindow ?: return "پنجرهٔ فعال در دسترس نیست."
            val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: return "فیلد متنی فعالی پیدا نشد؛ ابتدا فیلد ورودی را لمس کن."
            if (!field.isEditable || field.isPassword) return "فیلد فعال قابل‌ویرایش نیست یا گذرواژه است؛ متن وارد نشد."
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
            return if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "متن در فیلد فعال وارد شد."
            else "برنامهٔ مقصد اجازهٔ ورود متن از طریق Accessibility را نداد."
        }

        fun pressButton(value: String): String {
            val service = activeService ?: return "کنترل صفحه فعال نیست؛ سرویس Pocket Agent را در Accessibility فعال کن."
            val action = when (value.trim().lowercase()) {
                "back", "بازگشت" -> AccessibilityService.GLOBAL_ACTION_BACK
                "home", "خانه" -> AccessibilityService.GLOBAL_ACTION_HOME
                "recents", "recent", "برنامه‌های اخیر" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                "notifications", "اعلان‌ها" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                "quick_settings", "تنظیمات سریع" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
                else -> return "عمل مجاز ناشناخته است؛ از back, home, recents, notifications یا quick_settings استفاده کن."
            }
            return if (service.performGlobalAction(action)) "عمل سیستمی «" + value + "» اجرا شد."
            else "Android عمل سیستمی «" + value + "» را نپذیرفت."
        }

        fun scroll(directionValue: String): String {
            val service = activeService ?: return "کنترل صفحه فعال نیست؛ سرویس Pocket Agent را در Accessibility فعال کن."
            val root = service.rootInActiveWindow ?: return "پنجرهٔ فعال در دسترس نیست."
            val forward = when (directionValue.trim().lowercase()) {
                "down", "پایین" -> true
                "up", "بالا" -> false
                else -> return "جهت پیمایش باید up یا down باشد."
            }
            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            var visited = 0
            fun findAndScroll(node: AccessibilityNodeInfo): Boolean {
                if (visited++ > 300) return false
                if (node.isScrollable && node.performAction(action)) return true
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    if (findAndScroll(child)) return true
                }
                return false
            }
            return if (findAndScroll(root)) "پیمایش به سمت " + (if (forward) "پایین" else "بالا") + " ارسال شد."
            else "عنصر قابل پیمایش پیدا نشد یا برنامه اجازهٔ پیمایش نداد."
        }
    }
}
