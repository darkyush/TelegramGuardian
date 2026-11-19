package com.telegramguardian.app

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

sealed class GuardianAuthState {
    data object Starting : GuardianAuthState()
    data object NeedPhone : GuardianAuthState()
    data object NeedCode : GuardianAuthState()
    data class NeedPassword(val hint: String) : GuardianAuthState()
    data object Ready : GuardianAuthState()
    data class Failed(val message: String) : GuardianAuthState()
    data class Other(val stateName: String) : GuardianAuthState()
}

/**
 * One TDLib client per app process. Authentication and session data stay on-device.
 * All network calls are asynchronous through the official TDLib Java Client API.
 */
class TdlibManager private constructor(private val appContext: Context) {
    private val started = AtomicBoolean(false)
    private val mutableState = MutableStateFlow<GuardianAuthState>(GuardianAuthState.Starting)
    val authState: StateFlow<GuardianAuthState> = mutableState

    @Volatile private var client: Client? = null
    private val initParametersSent = AtomicBoolean(false)

    fun initialize() {
        if (!started.compareAndSet(false, true)) return

        if (BuildConfig.TELEGRAM_API_ID <= 0 || BuildConfig.TELEGRAM_API_HASH.isBlank()) {
            mutableState.value = GuardianAuthState.Failed(
                "اطلاعات API برنامه تنظیم نشده است. TELEGRAM_API_ID و TELEGRAM_API_HASH را در secrets.properties قرار بده."
            )
            return
        }

        try {
            System.loadLibrary("tdjni")
            val created = Client.create(
                { update -> handleUpdate(update) },
                { error -> mutableState.value = GuardianAuthState.Failed(error.message ?: "خطای TDLib") },
                { error -> mutableState.value = GuardianAuthState.Failed(error.message ?: "خطای TDLib") }
            )
            client = created
        } catch (t: Throwable) {
            mutableState.value = GuardianAuthState.Failed(
                "راه‌اندازی TDLib ناموفق بود: ${t.message ?: t.javaClass.simpleName}"
            )
        }
    }

    private fun handleUpdate(update: TdApi.Object) {
        if (update !is TdApi.UpdateAuthorizationState) return
        when (val state = update.authorizationState) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> sendTdlibParameters()
            is TdApi.AuthorizationStateWaitPhoneNumber -> mutableState.value = GuardianAuthState.NeedPhone
            is TdApi.AuthorizationStateWaitCode -> mutableState.value = GuardianAuthState.NeedCode
            is TdApi.AuthorizationStateWaitPassword ->
                mutableState.value = GuardianAuthState.NeedPassword(state.passwordHint ?: "")
            is TdApi.AuthorizationStateReady -> mutableState.value = GuardianAuthState.Ready
            is TdApi.AuthorizationStateLoggingOut -> mutableState.value =
                GuardianAuthState.Other("در حال خروج از حساب…")
            is TdApi.AuthorizationStateClosing -> mutableState.value =
                GuardianAuthState.Other("در حال بستن اتصال…")
            is TdApi.AuthorizationStateClosed -> mutableState.value =
                GuardianAuthState.Failed("اتصال TDLib بسته شد.")
            else -> mutableState.value = GuardianAuthState.Other(state.javaClass.simpleName)
        }
    }

    private fun sendTdlibParameters() {
        if (!initParametersSent.compareAndSet(false, true)) return
        val dbDir = File(appContext.filesDir, "telegram_guardian/tdlib")
        val filesDir = File(dbDir, "files")
        dbDir.mkdirs()
        filesDir.mkdirs()

        val parameters = TdApi.SetTdlibParameters().apply {
            useTestDc = false
            databaseDirectory = dbDir.absolutePath
            filesDirectory = filesDir.absolutePath
            databaseEncryptionKey = SecureDatabaseKey.getOrCreate(appContext)
            useFileDatabase = true
            useChatInfoDatabase = true
            useMessageDatabase = false
            useSecretChats = false
            apiId = BuildConfig.TELEGRAM_API_ID
            apiHash = BuildConfig.TELEGRAM_API_HASH
            systemLanguageCode = Locale.getDefault().toLanguageTag().ifBlank { "en" }
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
            systemVersion = Build.VERSION.RELEASE ?: "Android"
            applicationVersion = BuildConfig.VERSION_NAME
        }
        sendRaw(parameters) { result ->
            if (result is TdApi.Error) {
                mutableState.value = GuardianAuthState.Failed("تنظیمات TDLib رد شد: ${result.message}")
            }
        }
    }

    suspend fun submitPhone(phone: String) = sendChecked(
        TdApi.SetAuthenticationPhoneNumber(phone.trim(), null)
    )

    suspend fun submitCode(code: String) = sendChecked(TdApi.CheckAuthenticationCode(code.trim()))

    suspend fun submitPassword(password: String) =
        sendChecked(TdApi.CheckAuthenticationPassword(password))

    suspend fun awaitAuthorized(timeoutMs: Long = 90_000) {
        val state = withTimeout(timeoutMs) {
            authState.first {
                it is GuardianAuthState.Ready || it is GuardianAuthState.Failed
            }
        }
        if (state is GuardianAuthState.Failed) {
            error(state.message)
        }
    }

    suspend fun getSessions(): List<GuardianSession> = withContext(Dispatchers.IO) {
        ensureReady()
        val result = request(TdApi.GetActiveSessions())
        val sessions = result as? TdApi.Sessions
            ?: error("پاسخ نشست‌های تلگرام معتبر نبود.")
        sessions.sessions.map { s ->
            GuardianSession(
                id = s.id,
                isCurrent = s.isCurrent,
                applicationName = s.applicationName.orEmpty(),
                applicationVersion = s.applicationVersion.orEmpty(),
                deviceModel = s.deviceModel.orEmpty(),
                platform = s.platform.orEmpty(),
                systemVersion = s.systemVersion.orEmpty(),
                ipAddress = s.ipAddress.orEmpty(),
                location = s.location.orEmpty(),
                lastActiveDate = s.lastActiveDate,
                logInDate = s.logInDate
            )
        }
    }

    suspend fun terminateSession(sessionId: Long) = withContext(Dispatchers.IO) {
        ensureReady()
        val current = getSessions()
        val selected = current.firstOrNull { it.id == sessionId }
            ?: error("این نشست دیگر فعال نیست.")
        require(!selected.isCurrent) { "حذف نشست فعلی مجاز نیست." }
        request(TdApi.TerminateSession(sessionId))
    }

    /**
     * Fail-closed: do nothing unless Telegram explicitly identifies exactly one current session.
     */
    suspend fun terminateAllOtherSessions(): Int = withContext(Dispatchers.IO) {
        ensureReady()
        val sessions = getSessions()
        val currentSessions = sessions.count { it.isCurrent }
        check(currentSessions == 1) {
            "نشست فعلی به‌طور قطعی شناسایی نشد؛ برای جلوگیری از حذف اشتباه اقدامی انجام نشد."
        }
        val others = sessions.filterNot { it.isCurrent }
        for (session in others) {
            request(TdApi.TerminateSession(session.id))
        }
        others.size
    }

    private suspend fun ensureReady() {
        if (client == null) initialize()
        awaitAuthorized()
    }

    private suspend fun sendChecked(function: TdApi.Function<*>) {
        val result = request(function)
        if (result is TdApi.Error) error(result.message)
    }

    private suspend fun request(function: TdApi.Function<*>): TdApi.Object =
        withContext(Dispatchers.IO) {
            val activeClient = client ?: error(
                (mutableState.value as? GuardianAuthState.Failed)?.message
                    ?: "اتصال تلگرام آماده نیست."
            )
            val deferred = CompletableDeferred<TdApi.Object>()
            activeClient.send(function) { result ->
                if (!deferred.isCompleted) deferred.complete(result)
            }
            val result = withTimeout(45_000) { deferred.await() }
            if (result is TdApi.Error) error("Telegram ${result.code}: ${result.message}")
            result
        }

    private fun sendRaw(function: TdApi.Function<*>, callback: (TdApi.Object) -> Unit) {
        val activeClient = client ?: return
        activeClient.send(function, Client.ResultHandler { result -> callback(result) })
    }

    companion object {
        @Volatile private var instance: TdlibManager? = null
        fun get(context: Context): TdlibManager =
            instance ?: synchronized(this) {
                instance ?: TdlibManager(context.applicationContext).also { instance = it }
            }
    }
}
