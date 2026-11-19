package com.telegramguardian.app

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {
    private lateinit var manager: TdlibManager
    private lateinit var status: TextView
    private lateinit var phone: EditText
    private lateinit var code: EditText
    private lateinit var password: EditText
    private lateinit var phoneButton: Button
    private lateinit var codeButton: Button
    private lateinit var passwordButton: Button
    private lateinit var sessionsButton: Button
    private lateinit var revokeAllButton: Button
    private lateinit var autoButton: Button
    private lateinit var sessionsContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = TdlibManager.get(this)
        buildUi()
        renderAutoState()
        manager.initialize()

        lifecycleScope.launch {
            manager.authState.collect { state -> renderAuthState(state) }
        }
    }

    private fun buildUi() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 24)
            setBackgroundColor(0xFF102A27.toInt())
        }
        fun text(value: String, size: Float = 15f, color: Int = 0xFFFFFFFF.toInt()) =
            TextView(this).apply {
                this.text = value
                textSize = size
                setTextColor(color)
                setPadding(4, 10, 4, 10)
            }

        content.addView(text("TELEGRAM GUARDIAN 2.0", 23f))
        content.addView(text("محافظ نشست‌های تلگرام", 16f, 0xFFB8D8D2.toInt()))
        status = text("در حال اتصال به تلگرام…")
        content.addView(status)

        phone = EditText(this).apply {
            hint = "شماره تلفن با کد کشور، مانند +98912…"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        phoneButton = Button(this).apply {
            text = "ارسال کد ورود"
            setOnClickListener { runTask("در حال ارسال کد…") { manager.submitPhone(phone.text.toString()) } }
        }
        code = EditText(this).apply {
            hint = "کد تأیید تلگرام"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        codeButton = Button(this).apply {
            text = "تأیید کد"
            setOnClickListener { runTask("در حال بررسی کد…") { manager.submitCode(code.text.toString()) } }
        }
        password = EditText(this).apply {
            hint = "رمز دومرحله‌ای تلگرام"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        passwordButton = Button(this).apply {
            text = "تأیید رمز دومرحله‌ای"
            setOnClickListener { runTask("در حال بررسی رمز…") { manager.submitPassword(password.text.toString()) } }
        }

        listOf(phone, phoneButton, code, codeButton, password, passwordButton).forEach(content::addView)

        sessionsButton = Button(this).apply {
            text = "نمایش / تازه‌سازی نشست‌ها"
            setOnClickListener { refreshSessions() }
        }
        revokeAllButton = Button(this).apply {
            text = "حذف تمام نشست‌های دیگر"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("تأیید حذف نشست‌ها")
                    .setMessage("تمام نشست‌ها به‌جز همین دستگاه فعلی بسته می‌شوند. این کار ممکن است باعث خروج دستگاه‌های دیگر شود. ادامه می‌دهی؟")
                    .setNegativeButton("انصراف", null)
                    .setPositiveButton("حذف نشست‌های دیگر") { _, _ ->
                        runTask("در حال حذف نشست‌های دیگر…") {
                            val count = manager.terminateAllOtherSessions()
                            status.text = "$count نشست دیگر بسته شد."
                            refreshSessions()
                        }
                    }.show()
            }
        }
        autoButton = Button(this).apply {
            setOnClickListener {
                val enable = !GuardianPreferences.isAutoCleanupEnabled(this@MainActivity)
                GuardianPreferences.setAutoCleanupEnabled(this@MainActivity, enable)
                if (enable) GuardianWorkScheduler.schedule(this@MainActivity)
                else GuardianWorkScheduler.cancel(this@MainActivity)
                renderAutoState()
            }
        }
        content.addView(sessionsButton)
        content.addView(revokeAllButton)
        content.addView(autoButton)
        content.addView(text(
            "حذف خودکار فقط نشست‌هایی را می‌بندد که تلگرام آن‌ها را صریحاً غیرجاری اعلام کرده باشد. اگر نشست فعلی قابل تشخیص نباشد، حذف انجام نمی‌شود.",
            12f, 0xFFFFD9A8.toInt()
        ))

        sessionsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(text("نشست‌های فعال", 18f))
        content.addView(sessionsContainer)

        setContentView(ScrollView(this).apply { addView(content) })
        renderAuthState(manager.authState.value)
    }

    private fun renderAuthState(state: GuardianAuthState) {
        phone.visibility = if (state is GuardianAuthState.NeedPhone) View.VISIBLE else View.GONE
        phoneButton.visibility = phone.visibility
        code.visibility = if (state is GuardianAuthState.NeedCode) View.VISIBLE else View.GONE
        codeButton.visibility = code.visibility
        password.visibility = if (state is GuardianAuthState.NeedPassword) View.VISIBLE else View.GONE
        passwordButton.visibility = password.visibility

        status.text = when (state) {
            GuardianAuthState.Starting -> "در حال راه‌اندازی موتور تلگرام…"
            GuardianAuthState.NeedPhone -> "برای ورود، شماره تلفن تلگرام را وارد کن."
            GuardianAuthState.NeedCode -> "کد ارسال‌شده از طرف تلگرام را وارد کن."
            is GuardianAuthState.NeedPassword -> "رمز دومرحله‌ای لازم است. راهنما: ${state.hint}"
            GuardianAuthState.Ready -> "حساب تلگرام متصل شد. می‌توانی نشست‌ها را مشاهده کنی."
            is GuardianAuthState.Failed -> state.message
            is GuardianAuthState.Other -> state.stateName
        }
        val ready = state is GuardianAuthState.Ready
        sessionsButton.isEnabled = ready
        revokeAllButton.isEnabled = ready
        if (ready) refreshSessions()
    }

    private fun refreshSessions() {
        runTask("در حال دریافت نشست‌ها…") {
            val sessions = manager.getSessions()
            sessionsContainer.removeAllViews()
            if (sessions.isEmpty()) {
                sessionsContainer.addView(TextView(this).apply { text = "نشستی پیدا نشد." })
            }
            sessions.forEach { session ->
                val panel = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(12, 12, 12, 12)
                    setBackgroundColor(if (session.isCurrent) 0xFF1D5149.toInt() else 0xFF183B36.toInt())
                }
                val title = if (session.isCurrent) "این دستگاه (نشست فعلی)" else "نشست دیگر"
                val details = buildString {
                    appendLine(title)
                    appendLine("${session.applicationName} ${session.applicationVersion}".trim())
                    appendLine("${session.deviceModel} · ${session.platform} · ${session.systemVersion}")
                    appendLine("IP: ${session.ipAddress.ifBlank { "نامشخص" }}")
                    appendLine("موقعیت تقریبی: ${session.location.ifBlank { "نامشخص" }}")
                    appendLine("زمان ورود: ${formatDate(session.logInDate)}")
                    append("آخرین فعالیت: ${formatDate(session.lastActiveDate)}")
                }
                panel.addView(TextView(this).apply {
                    text = details
                    textSize = 13f
                    setTextColor(0xFFFFFFFF.toInt())
                })
                if (!session.isCurrent) {
                    panel.addView(Button(this).apply {
                        text = "حذف این نشست"
                        setOnClickListener {
                            AlertDialog.Builder(this@MainActivity)
                                .setMessage("نشست ${session.deviceModel} بسته شود؟")
                                .setNegativeButton("انصراف", null)
                                .setPositiveButton("حذف") { _, _ ->
                                    runTask("در حال بستن نشست…") {
                                        manager.terminateSession(session.id)
                                        refreshSessions()
                                    }
                                }.show()
                        }
                    })
                }
                sessionsContainer.addView(panel)
            }
            status.text = "تعداد نشست‌ها: ${sessions.size} · نشست فعلی: ${sessions.count { it.isCurrent }}"
        }
    }

    private fun renderAutoState() {
        val enabled = GuardianPreferences.isAutoCleanupEnabled(this)
        autoButton.text = if (enabled) "غیرفعال‌کردن محافظت خودکار ۲۵ ساعته" else "فعال‌کردن محافظت خودکار ۲۵ ساعته"
    }

    private fun formatDate(seconds: Int): String =
        if (seconds <= 0) "نامشخص" else DateFormat.getDateTimeInstance().format(Date(seconds * 1000L))

    private fun runTask(loading: String, task: suspend () -> Unit) {
        status.text = loading
        lifecycleScope.launch {
            try {
                task()
            } catch (e: Exception) {
                status.text = e.message ?: "عملیات ناموفق بود."
            }
        }
    }
}
