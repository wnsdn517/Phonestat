package com.junu.phonestat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Bg = Color(0xFF0E1116)
val Card = Color(0xFF171C24)
val Accent = Color(0xFF4DD0A8)
val Dim = Color(0xFF8A94A6)

@Composable
fun PhoneTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(
        primary = Accent, background = Bg, surface = Card,
        onSurface = Color(0xFFE6EAF0), onBackground = Color(0xFFE6EAF0),
    ), content = content
)

private sealed interface Entry {
    data class Head(val title: String, val count: Int, val open: Boolean) : Entry
    data class Row(val kv: Kv) : Entry
}

private val pctRegex = Regex("(\\d+(?:\\.\\d+)?)\\s?%")

@Composable
fun SectionList(sections: List<Section>, query: String, modifier: Modifier = Modifier) {
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    val q = query.trim().lowercase()
    val entries = remember(sections, q, collapsed.toMap()) {
        buildList {
            for (s in sections) {
                val rows = if (q.isEmpty()) s.items else s.items.filter { "${it.k} ${it.v}".lowercase().contains(q) }
                if (q.isNotEmpty() && rows.isEmpty()) continue
                val open = collapsed[s.title] != true
                add(Entry.Head(s.title, rows.size, open))
                if (open) rows.forEach { add(Entry.Row(it)) }
            }
        }
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 24.dp)) {
        items(entries) { e ->
            when (e) {
                is Entry.Head -> Row(
                    Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp)
                        .clickable { collapsed[e.title] = e.open },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (e.open) "▾" else "▸", color = Accent, fontSize = 16.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(e.title, color = Accent, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text("${e.count}", color = Dim, fontSize = 12.sp)
                }
                is Entry.Row -> KvRow(e.kv)
            }
        }
    }
}

@Composable
private fun KvRow(kv: Kv) {
    val pct = pctRegex.find(kv.v)?.groupValues?.get(1)?.toFloatOrNull()
    Surface(color = Card, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Column(Modifier.padding(10.dp, 8.dp)) {
            if (kv.k.isNotEmpty()) Text(kv.k, color = Dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Text(kv.v, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp)
            if (pct != null && pct in 0f..100f && kv.v.length < 40) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { pct / 100f }, modifier = Modifier.fillMaxWidth().height(5.dp),
                    color = when { pct > 85 -> Color(0xFFFF6B6B); pct > 60 -> Color(0xFFFFC857); else -> Accent },
                    trackColor = Color(0xFF232A35),
                )
            }
        }
    }
}
