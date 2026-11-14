package com.pocketai.chat.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString

/**
 * Selectable text with Pocket AI's own selection menu (2.21): **Web search** first, then Copy, Select all and the
 * other apps' text actions (Translate, Read aloud, …), without "Save to Samsung Pass" and without our own
 * "Ask Pocket AI" (meant for other apps). Compose builds this menu itself; a filter can drop its items but not
 * reorder them, so all of Compose's items are dropped and ours are added in the order we want. This also drops
 * the phone's smart-selection actions (e.g. "Call" on a selected number).
 */
@Composable
fun SelectableText(onWebSearch: ((String) -> Unit)?, content: @Composable () -> Unit) {
    val state = rememberSelectionState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val apps = remember(context) { textActionApps(context) }
    fun selected() = state.selectedTexts.joinToString("\n") { it.text }.trim()
    SelectionContainer(
        state = state,
        modifier = Modifier
            .filterTextContextMenuComponents { it.key is MenuKey }
            .appendTextContextMenuComponents {
                val text = selected()
                if (text.isEmpty()) return@appendTextContextMenuComponents
                if (onWebSearch != null) item(MenuKey.WebSearch, "Web search") {
                    close()
                    state.clear()
                    onWebSearch(text)
                }
                item(MenuKey.Copy, context.getString(android.R.string.copy)) {
                    clipboard.setText(AnnotatedString(text))
                    close()
                    state.clear()
                }
                item(MenuKey.SelectAll, context.getString(android.R.string.selectAll)) { state.selectAll() }
                separator()
                apps.forEachIndexed { i, app ->
                    item(MenuKey.App(i), app.label) {
                        close()
                        app.open(context, text)
                    }
                }
            },
    ) { content() }
}

/** What a selection menu's "Web search" sends in the chat. */
fun webSearchRequest(selection: String): String = "Search the web for “${selection.take(500).trim()}”"

private sealed interface MenuKey {
    data object WebSearch : MenuKey
    data object Copy : MenuKey
    data object SelectAll : MenuKey
    data class App(val index: Int) : MenuKey
}

private class TextActionApp(val label: String, val packageName: String, val activity: String) {
    fun open(context: Context, text: String) {
        val intent = Intent(Intent.ACTION_PROCESS_TEXT)
            .setType("text/plain")
            .setClassName(packageName, activity)
            .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        try { context.startActivity(intent) } catch (_: ActivityNotFoundException) {} catch (_: SecurityException) {}
    }
}

/** Other apps' "process text" actions (Translate, Read aloud, …), in the order Android lists them. */
private fun textActionApps(context: Context): List<TextActionApp> {
    val pm = context.packageManager
    val found = try {
        pm.queryIntentActivities(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain"), 0)
    } catch (_: Exception) {
        emptyList()
    }
    return found.mapNotNull { info ->
        val a = info.activityInfo ?: return@mapNotNull null
        if (a.packageName == context.packageName) return@mapNotNull null // our own "Ask Pocket AI"
        if (!a.exported) return@mapNotNull null
        if (a.permission != null && context.checkSelfPermission(a.permission) != PackageManager.PERMISSION_GRANTED) {
            return@mapNotNull null
        }
        val label = info.loadLabel(pm).toString()
        if (isSamsungPass(a.packageName, label)) return@mapNotNull null
        TextActionApp(label, a.packageName, a.name)
    }
}

internal fun isSamsungPass(packageName: String, label: String): Boolean =
    packageName.contains("samsungpass", ignoreCase = true) ||
        packageName.contains("samsung.android.authfw", ignoreCase = true) ||
        label.contains("Samsung Pass", ignoreCase = true)
