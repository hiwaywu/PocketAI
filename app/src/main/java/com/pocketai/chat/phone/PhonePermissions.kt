package com.pocketai.chat.phone

import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lets a tool running in the view model ask for a runtime permission (calendar) in the middle of a reply:
 * the request is posted here and whichever Pocket AI screen is showing ([PermissionRequester]) shows
 * Android's permission dialog. If no Pocket AI screen is showing, it isn't asked and counts as refused.
 */
object PhonePermissions {
    class Request(val permissions: Array<String>) {
        val result = CompletableDeferred<Boolean>()
        val shown = AtomicBoolean(false)
    }

    val pending = MutableStateFlow<Request?>(null)

    fun has(context: Context, permissions: Array<String>) =
        permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private val lock = Mutex()

    /** One request at a time, so two calendar tools called together share one dialog. */
    suspend fun ensure(context: Context, permissions: Array<String>, visible: () -> Boolean): Boolean {
        lock.withLock {
        if (has(context, permissions)) return true
        if (!visible()) return false
        val r = Request(permissions)
        pending.value = r
        return try {
            withTimeoutOrNull(120_000) { r.result.await() } ?: false
        } finally {
            pending.compareAndSet(r, null)
        }
        }
    }
}

/** Put in every Pocket AI screen; shows the permission dialog a tool is waiting for. */
@Composable
fun PermissionRequester() {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val request by PhonePermissions.pending.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    var asked by remember { mutableStateOf<PhonePermissions.Request?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        asked?.result?.complete(res.isNotEmpty() && res.values.all { it })
        asked = null
    }
    LaunchedEffect(request) {
        val r = request ?: return@LaunchedEffect
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        if (!r.shown.compareAndSet(false, true)) return@LaunchedEffect
        asked = r
        launcher.launch(r.permissions)
    }
}
