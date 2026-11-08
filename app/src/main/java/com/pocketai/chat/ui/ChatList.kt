package com.pocketai.chat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketai.chat.ChatViewModel
import com.pocketai.chat.UiState
import com.pocketai.chat.data.Conversation
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private sealed interface ListRow {
    data class Header(val label: String) : ListRow
    data class Chat(val c: Conversation, val snippet: String?) : ListRow
}

/** The chat list: search, pinned chats, then chats by date. Drawer when folded, side panel when unfolded. */
@Composable
fun ChatList(
    state: UiState,
    vm: ChatViewModel,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
    onSettings: () -> Unit,
    onUsage: () -> Unit,
) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var renaming by remember { mutableStateOf<Conversation?>(null) }
    var confirmDelete by remember { mutableStateOf<Conversation?>(null) }
    val q = query.trim()
    val rows = remember(state.conversations, q) { buildRows(state.conversations, q) }

    Column(Modifier.fillMaxHeight().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Pocket AI", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            FilledTonalButton(onClick = onNew) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("New chat")
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search chats") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Clear search") }
            },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        )
        LazyColumn(Modifier.weight(1f)) {
            if (rows.isEmpty()) {
                item {
                    Text(
                        if (q.isEmpty()) "No saved chats yet" else "No chats match “$q”",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            rows.forEach { row ->
                when (row) {
                    is ListRow.Header -> item(key = "h:" + row.label) {
                        Text(
                            row.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 24.dp, top = 14.dp, bottom = 4.dp),
                        )
                    }
                    is ListRow.Chat -> item(key = row.c.id) {
                        ChatRow(
                            c = row.c,
                            snippet = row.snippet,
                            selected = row.c.id == state.current.id,
                            running = row.c.id in state.running,
                            onOpen = { onOpen(row.c.id) },
                            onPin = { vm.togglePin(row.c.id) },
                            onRename = { renaming = row.c },
                            onShare = { shareText(context, exportChat(row.c), row.c.title) },
                            onDelete = { confirmDelete = row.c },
                        )
                    }
                }
            }
        }
        HorizontalDivider()
        NavigationDrawerItem(
            label = { Text("Token usage") },
            icon = { Icon(Icons.Filled.BarChart, null) },
            selected = false,
            onClick = onUsage,
            modifier = Modifier.padding(horizontal = 12.dp).padding(top = 8.dp),
        )
        NavigationDrawerItem(
            label = { Text("Settings") },
            icon = { Icon(Icons.Filled.Settings, null) },
            selected = false,
            onClick = onSettings,
            modifier = Modifier.navigationBarsPadding().padding(horizontal = 12.dp).padding(bottom = 12.dp),
        )
    }

    renaming?.let { c ->
        var name by remember(c.id) { mutableStateOf(c.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename chat") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.renameChat(c.id, name); renaming = null }) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    confirmDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this chat?") },
            text = { Text("“${c.title}” will be removed from this phone.") },
            confirmButton = { TextButton(onClick = { vm.deleteChat(c.id); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ChatRow(
    c: Conversation,
    snippet: String?,
    selected: Boolean,
    running: Boolean,
    onOpen: () -> Unit,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = if (selected) colors.secondaryContainer else colors.surface.copy(alpha = 0f),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 1.dp),
    ) {
        Row(
            Modifier.clickable(onClick = onOpen).heightIn(min = 52.dp).padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                running -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                c.pinned -> Icon(Icons.Filled.PushPin, "Pinned", Modifier.size(16.dp), tint = colors.primary)
            }
            if (running || c.pinned) Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    c.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                snippet?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Chat options", tint = colors.onSurfaceVariant) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (c.pinned) "Unpin" else "Pin to top") },
                        leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
                        onClick = { menu = false; onPin() },
                    )
                    DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Share chat") }, leadingIcon = { Icon(Icons.Outlined.Share, null) }, onClick = { menu = false; onShare() })
                    DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Outlined.Delete, null) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

private fun buildRows(all: List<Conversation>, q: String): List<ListRow> {
    val matches: List<Pair<Conversation, String?>> = if (q.isEmpty()) all.map { it to null } else all.mapNotNull { c ->
        when {
            c.title.contains(q, ignoreCase = true) -> c to null
            else -> c.messages.firstNotNullOfOrNull { m -> snippetOf(m.finalContent?.ifBlank { null } ?: m.text, q) }?.let { c to it }
        }
    }
    val out = mutableListOf<ListRow>()
    val pinned = matches.filter { it.first.pinned }
    if (pinned.isNotEmpty()) {
        out += ListRow.Header("Pinned")
        pinned.sortedByDescending { it.first.updatedAt }.forEach { (c, s) -> out += ListRow.Chat(c, s) }
    }
    var last: String? = null
    // Newest first, so each date heading appears once (a heading shown twice would crash the list).
    matches.filterNot { it.first.pinned }.sortedByDescending { it.first.updatedAt }.forEach { (c, s) ->
        val label = dateGroup(c.updatedAt)
        if (label != last) {
            out += ListRow.Header(label)
            last = label
        }
        out += ListRow.Chat(c, s)
    }
    return out
}

/** The words around a search match, e.g. "…the Ole Miss students who…". */
private fun snippetOf(text: String, q: String): String? {
    val i = text.indexOf(q, ignoreCase = true)
    if (i < 0) return null
    val from = (i - 30).coerceAtLeast(0)
    val to = (i + q.length + 60).coerceAtMost(text.length)
    return (if (from > 0) "…" else "") + text.substring(from, to).replace(Regex("""\s+"""), " ").replace(Regex("""[*_#`>|]"""), "") +
        (if (to < text.length) "…" else "")
}

private fun dateGroup(ms: Long): String {
    val day = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val dayMs = 24 * 60 * 60 * 1000L
    return when {
        ms >= day -> "Today"
        ms >= day - dayMs -> "Yesterday"
        ms >= day - 7 * dayMs -> "Previous 7 days"
        ms >= day - 30 * dayMs -> "Previous 30 days"
        else -> SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(ms))
    }
}
