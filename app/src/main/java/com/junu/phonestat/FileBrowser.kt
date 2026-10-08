package com.junu.phonestat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val quick = listOf("/proc", "/sys", "/dev", "/data", "/system", "/vendor", "/sys/class", "/sys/devices", "/proc/sys", "/sdcard", "/")

@Composable
fun FileBrowser(onShare: (String, String) -> Unit, onSave: (String, String) -> Unit) {
    var path by remember { mutableStateOf("/proc") }
    var entries by remember { mutableStateOf<List<Pair<String, Boolean>>>(emptyList()) }
    var content by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun open(p: String) {
        scope.launch {
            busy = true; content = null
            val isDir = withContext(Dispatchers.IO) { File(p).isDirectory || (Shell.hasRoot && Shell.exec("[ -d '$p' ] && echo d").trim() == "d") }
            if (isDir) {
                path = p
                entries = withContext(Dispatchers.IO) { Shell.list(p) }
            } else {
                title = p
                content = withContext(Dispatchers.IO) { Shell.readFile(p).ifEmpty { "(비어있거나 읽을 수 없음 — su 필요할 수 있음)" } }
            }
            busy = false
        }
    }

    fun dumpDir() {
        scope.launch {
            busy = true
            val dir = path
            val text = withContext(Dispatchers.IO) {
                buildString {
                    Shell.list(dir).filter { !it.second }.take(500).forEach { (n, _) ->
                        val v = Shell.readFile("${dir.trimEnd('/')}/$n", 4000).trim()
                        if (v.isNotEmpty()) appendLine("$n = ${v.replace("\n", "\\n")}")
                    }
                }
            }
            title = "$dir (일괄 덤프)"; content = text.ifEmpty { "(읽을 수 있는 파일 없음)" }; busy = false
        }
    }

    LaunchedEffect(Unit) { open(path) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            quick.forEach { AssistChip(onClick = { open(it) }, label = { Text(it, fontSize = 12.sp) }) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(path, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Dim, modifier = Modifier.weight(1f))
            TextButton(onClick = { val p = File(path).parent ?: "/"; open(p) }) { Text("↑ 상위") }
            TextButton(onClick = { dumpDir() }) { Text("값 일괄덤프") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        val c = content
        if (c != null) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 12.sp, color = Accent, modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace)
                TextButton(onClick = { onShare(File(title).name.ifEmpty { "file" }, c) }) { Text("공유") }
                TextButton(onClick = { onSave(File(title).name.ifEmpty { "file" }, c) }) { Text("저장") }
                TextButton(onClick = { content = null }) { Text("닫기") }
            }
            Surface(color = Card, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxSize().padding(12.dp)) {
                SelectionContainer {
                    Text(c, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp,
                        modifier = Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(10.dp))
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 24.dp)) {
                items(entries) { (name, isDir) ->
                    Surface(color = Card, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        .clickable { open("${path.trimEnd('/')}/$name") }) {
                        Text((if (isDir) "📁 " else "📄 ") + name, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.padding(10.dp))
                    }
                }
                if (entries.isEmpty() && !busy) item { Text("비어있거나 접근 불가 (su 필요할 수 있음)", color = Dim, modifier = Modifier.padding(12.dp)) }
            }
        }
    }
}
