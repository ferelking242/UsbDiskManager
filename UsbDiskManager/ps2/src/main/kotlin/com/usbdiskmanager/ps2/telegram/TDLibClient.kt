package com.usbdiskmanager.ps2.telegram

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TDLibException(val code: Int, override val message: String) :
    Exception("TDLib $code: $message")

@Singleton
class TDLibClient @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var client: Client? = null

    private val _authState = MutableStateFlow<TdApi.AuthorizationState?>(null)
    val authState: StateFlow<TdApi.AuthorizationState?> = _authState.asStateFlow()

    private val _fileUpdates = MutableSharedFlow<TdApi.UpdateFile>(
        replay = 0,
        extraBufferCapacity = 256
    )
    val fileUpdates: SharedFlow<TdApi.UpdateFile> = _fileUpdates.asSharedFlow()

    val isReady: Boolean
        get() = _authState.value is TdApi.AuthorizationStateReady

    private var pendingApiId: Int = 0
    private var pendingApiHash: String = ""

    fun initialize(apiId: Int, apiHash: String) {
        if (client != null) return
        pendingApiId = apiId
        pendingApiHash = apiHash
        client = Client.create(
            { update -> handleUpdate(update) },
            null,
            null
        )
        Timber.d("TDLib client created")
    }

    private fun handleUpdate(update: TdApi.Object) {
        when (update) {
            is TdApi.UpdateAuthorizationState -> {
                val state = update.authorizationState
                _authState.value = state
                Timber.d("Auth state: ${state::class.simpleName}")
                when (state) {
                    is TdApi.AuthorizationStateWaitTdlibParameters ->
                        sendTdlibParameters()
                    else -> Unit
                }
            }
            is TdApi.UpdateFile -> scope.launch { _fileUpdates.emit(update) }
            else -> Unit
        }
    }

    private val emptyHandler = Client.ResultHandler { }

    private fun sendTdlibParameters() {
        val dbDir = File(context.filesDir, "tdlib_db").also { it.mkdirs() }
        val filesDir = File(context.filesDir, "tdlib_files").also { it.mkdirs() }
        val params = TdApi.SetTdlibParameters(
            false,
            dbDir.absolutePath,
            filesDir.absolutePath,
            ByteArray(0),
            true,
            true,
            true,
            false,
            pendingApiId,
            pendingApiHash,
            "fr",
            "Android",
            "Android",
            "1.0"
        )
        client?.send(params, emptyHandler)
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : TdApi.Object> send(function: TdApi.Function<T>): T =
        suspendCancellableCoroutine { cont ->
            client?.send(function) { result ->
                when (result) {
                    is TdApi.Error -> cont.resumeWithException(
                        TDLibException(result.code, result.message)
                    )
                    else -> cont.resume(result as T)
                }
            } ?: cont.resumeWithException(IllegalStateException("TDLib not initialized"))
        }

    suspend fun setPhoneNumber(phone: String) =
        send(TdApi.SetAuthenticationPhoneNumber(phone, null))

    suspend fun checkCode(code: String) =
        send(TdApi.CheckAuthenticationCode(code))

    suspend fun checkPassword(password: String) =
        send(TdApi.CheckAuthenticationPassword(password))

    suspend fun logOut() = send(TdApi.LogOut())

    suspend fun waitForReady() {
        authState.first { it is TdApi.AuthorizationStateReady }
    }

    suspend fun searchPublicChat(username: String): TdApi.Chat =
        send(TdApi.SearchPublicChat(username))

    suspend fun getMessage(chatId: Long, messageId: Long): TdApi.Message =
        send(TdApi.GetMessage(chatId, messageId))

    suspend fun getChatHistory(
        chatId: Long,
        fromMessageId: Long,
        limit: Int
    ): TdApi.Messages = send(TdApi.GetChatHistory(chatId, fromMessageId, 0, limit, false))

    suspend fun startDownload(fileId: Int, priority: Int = 32): TdApi.File =
        send(TdApi.DownloadFile(fileId, priority, 0, 0, false))

    suspend fun cancelDownload(fileId: Int) = runCatching {
        send(TdApi.CancelDownloadFile(fileId, false))
    }

    suspend fun getFile(fileId: Int): TdApi.File = send(TdApi.GetFile(fileId))

    fun close() {
        runCatching { client?.send(TdApi.Close(), emptyHandler) }
        client = null
        _authState.value = null
    }
}
