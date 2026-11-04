package com.pocketai.chat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketai.chat.ChatViewModel
import com.pocketai.chat.data.ChatMessage
import com.pocketai.chat.data.Conversation
import com.pocketai.chat.data.ModelRates
import com.pocketai.chat.data.ReplyUsage

private data class Part(val label: String, val tokens: Long, val color: Color)

@Composable
private fun usageParts(u: ReplyUsage): List<Part> {
    val c = MaterialTheme.colorScheme
    return listOf(
        Part("Chat history", u.estHistory, c.primary),
        Part("Web results", u.estWeb, c.tertiary),
        Part("Photos", u.estPhotos, c.secondary),
        Part("Shared pages", u.estShared, c.error),
        Part("AI's reply (output)", u.output, c.outline),
    )
}

private fun sum(list: List<ReplyUsage>) = ReplyUsage(
    input = list.sumOf { it.input },
    output = list.sumOf { it.output },
    cached = list.sumOf { it.cached },
    steps = list.sumOf { it.steps },
    searches = list.sumOf { it.searches },
    pages = list.sumOf { it.pages },
    estHistory = list.sumOf { it.estHistory },
    estWeb = list.sumOf { it.estWeb },
    estPhotos = list.sumOf { it.estPhotos },
    estShared = list.sumOf { it.estShared },
    estimated = list.any { it.estimated },
)

/** Reply line for lists: billed tokens when the provider uses a multiplier. */
private fun shown(u: ReplyUsage) = formatTokens(u.billed)

/** Bars showing where the tokens went. */
@Composable
fun UsageBreakdown(u: ReplyUsage) {
    val parts = usageParts(u)
    val total = parts.sumOf { it.tokens }.coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))) {
            parts.filter { it.tokens > 0 }.forEach { p ->
                Box(Modifier.weight(p.tokens.toFloat()).fillMaxHeight().background(p.color))
            }
        }
        parts.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(p.color))
                Spacer(Modifier.width(8.dp))
                Text(p.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    "${formatTokens(p.tokens)}  (${p.tokens * 100 / total}%)",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        val details = buildList {
            if (u.seconds > 0) add("took ${u.seconds} s")
            add("${u.steps} step${if (u.steps == 1) "" else "s"}")
            if (u.searches > 0) add("${u.searches} search${if (u.searches == 1) "" else "es"}")
            if (u.pages > 0) add("${u.pages} page${if (u.pages == 1) "" else "s"} read")
            if (u.cached > 0) add("${formatTokens(u.cached)} input served from cache (cheaper)")
        }
        Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            if (u.estimated) "This provider didn't report token counts, so all numbers are estimates."
            else "Totals come from the provider. The split between history, web, photos and pages is the app's estimate.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun UsageDialog(u: ReplyUsage, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                u.multiplier?.let { "This reply cost ${formatTokens(u.billed)} billed tokens" }
                    ?: "This reply used ${formatTokens(u.total)} tokens"
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "${formatTokens(u.input)} sent to the AI · ${formatTokens(u.output)} written by the AI",
                    style = MaterialTheme.typography.bodyMedium,
                )
                u.multiplier?.let { m ->
                    Text(
                        "NT Media bills this model at ${ModelRates.label(m)}: ${formatTokens(u.total)} tokens × $m = ${formatTokens(u.billed)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
                UsageBreakdown(u)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

private data class Costly(val conv: Conversation, val msg: ChatMessage, val usage: ReplyUsage)

/** Token usage across all saved chats: totals, where tokens went, and the most expensive replies. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(vm: ChatViewModel, onOpenChat: (String) -> Unit, onClose: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    BackHandler { onClose() }
    val convs = remember(state.conversations, state.current) {
        (listOf(state.current) + state.conversations.filterNot { it.id == state.current.id }).filter { it.messages.isNotEmpty() }
    }
    val replies = remember(convs) {
        convs.flatMap { c -> c.messages.mapNotNull { m -> m.usage?.let { Costly(c, m, it) } } }
    }
    val all = remember(replies) { sum(replies.map { it.usage }) }
    val billedAll = remember(replies) { replies.sumOf { it.usage.billed } }
    val anyBilled = remember(replies) { replies.any { it.usage.multiplier != null } }
    val byModel = remember(replies) {
        replies.groupBy { it.msg.model ?: "?" }
            .map { (m, v) -> Triple(m, v.sumOf { it.usage.billed }, v.firstNotNullOfOrNull { it.usage.multiplier }) }
            .sortedByDescending { it.second }
    }
    val byChat = remember(replies) {
        replies.groupBy { it.conv.id }.map { (_, v) -> v.first().conv to v.sumOf { it.usage.billed } }.sortedByDescending { it.second }
    }
    val top = remember(replies) { replies.sortedByDescending { it.usage.billed }.take(10) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Token usage") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (replies.isEmpty()) {
                item { Text("No usage yet — send a message first.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                return@LazyColumn
            }
            item {
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (anyBilled) "${formatTokens(billedAll)} billed tokens" else "${formatTokens(all.total)} tokens",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (anyBilled) Text(
                        "${formatTokens(all.total)} raw tokens; NT Media replies are counted × their model's multiplier.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${plural(replies.size, "reply", "replies")} in ${plural(byChat.size, "chat")} · ${formatTokens(all.input)} sent · ${formatTokens(all.output)} written",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item { Section("Where the tokens went") { UsageBreakdown(all) } }
            item {
                Section("Most expensive replies") {
                    top.forEachIndexed { i, r ->
                        if (i > 0) HorizontalDivider()
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpenChat(r.conv.id) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(r.conv.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(
                                        r.msg.model,
                                        plural(r.usage.steps, "step"),
                                        if (r.usage.searches > 0) plural(r.usage.searches, "search", "searches") else null,
                                        if (r.usage.pages > 0) plural(r.usage.pages, "page") else null,
                                        biggestPart(r.usage),
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(shown(r.usage), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            item {
                Section("By chat") {
                    byChat.forEach { (c, t) ->
                        Row(Modifier.fillMaxWidth().clickable { onOpenChat(c.id) }.padding(vertical = 6.dp)) {
                            Text(c.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            Text(formatTokens(t), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            item {
                Section("By model") {
                    byModel.forEach { (m, t, rate) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(m + (rate?.let { "  ${ModelRates.label(it)}" } ?: ""), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text(formatTokens(t), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

private fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

private fun biggestPart(u: ReplyUsage): String? {
    val parts = listOf("history" to u.estHistory, "web" to u.estWeb, "photos" to u.estPhotos, "shared page" to u.estShared)
    val (name, v) = parts.maxBy { it.second }
    if (u.input <= 0 || v <= 0) return null
    return "mostly $name (${v * 100 / u.input}%)"
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        content()
    }
}
