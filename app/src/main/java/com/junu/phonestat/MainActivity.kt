package com.junu.phonestat

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Page(val label: String, val everyMs: Long, val load: (Context) -> List<Section>) {
    OVERVIEW("개요", 1000, { c -> listOf(Sys.cpu()[0], Sys.memory()[0], Sys.battery(c)[0], Sys.thermal()[0]) }),
    CPU("CPU", 1000, { Sys.cpu() }),
    MEM("메모리", 1500, { Sys.memory() }),
    GPU("GPU", 1000, { Sys.gpu() }),
    BATTERY("배터리", 2000, { Sys.battery(it) }),
    THERMAL("온도", 2000, { Sys.thermal() }),
    APPS("앱", 0, { Sys2.apps(it) }),
    PROCS("프로세스", 3000, { Sys2.processes() }),
    SERVICES("서비스", 0, { Sys2.services() }),
    NET("네트워크", 2000, { Sys2.network() }),
    STORAGE("저장소", 5000, { Sys2.storage() }),
    SENSORS("센서", 0, { Sys2.sensors(it) }),
    KERNEL("커널", 0, { Sys.kernel() }),
    DEVICE("기기", 0, { Sys.device() }),
    LOGS("로그", 0, { Sys2.logs() }),
    FILES("파일 탐색", 0, { emptyList() }),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PhoneTheme { App() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var root by remember { mutableStateOf<Boolean?>(null) }
    var page by remember { mutableStateOf(Page.OVERVIEW) }
    var sections by remember { mutableStateOf<List<Section>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var live by remember { mutableStateOf(true) }
    var tick by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }

    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()

    LaunchedEffect(tick) { root = withContext(Dispatchers.IO) { Shell.checkRoot(force = tick > 0) } }

    LaunchedEffect(page, live, tick, root) {
        if (root == null || page == Page.FILES) return@LaunchedEffect
        while (true) {
            sections = withContext(Dispatchers.IO) { runCatching { page.load(ctx) }.getOrElse { listOf(Section("오류", listOf(Kv("", it.toString())))) } }
            if (!live || page.everyMs <= 0) break
            delay(page.everyMs)
        }
    }

    fun exportAll(json: Boolean) = scope.launch {
        toast("전체 수집 중…")
        val all = withContext(Dispatchers.IO) { Page.entries.filter { it != Page.FILES }.flatMap { p -> listOf(Section("════ ${p.label} ════", emptyList())) + runCatching { p.load(ctx) }.getOrDefault(emptyList()) } }
        val ext = if (json) "json" else "txt"
        toast("저장됨: " + Export.saveToDownloads(ctx, "phonestat_all", Export.sectionsToText("전체", all, json), ext))
    }

    Scaffold(
        containerColor = Bg,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("PhoneStat", fontSize = 20.sp) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg),
                    actions = {
                        Text("실시간", fontSize = 12.sp, color = Dim)
                        Switch(live, { live = it }, Modifier.padding(horizontal = 6.dp))
                        TextButton(onClick = { menu = true }) { Text("내보내기") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("이 탭 공유 (txt)") }, onClick = { menu = false; Export.share(ctx, page.name.lowercase(), Export.sectionsToText(page.label, sections)) })
                            DropdownMenuItem(text = { Text("이 탭 저장 (txt)") }, onClick = { menu = false; toast("저장됨: " + Export.saveToDownloads(ctx, page.name.lowercase(), Export.sectionsToText(page.label, sections))) })
                            DropdownMenuItem(text = { Text("이 탭 저장 (JSON)") }, onClick = { menu = false; toast("저장됨: " + Export.saveToDownloads(ctx, page.name.lowercase(), Export.sectionsToText(page.label, sections, true), "json")) })
                            DropdownMenuItem(text = { Text("전체 탭 저장 (txt)") }, onClick = { menu = false; exportAll(false) })
                            DropdownMenuItem(text = { Text("전체 탭 저장 (JSON)") }, onClick = { menu = false; exportAll(true) })
                            DropdownMenuItem(text = { Text("새로고침 / su 재확인") }, onClick = { menu = false; tick++ })
                        }
                    }
                )
                when (root) {
                    null -> Banner("su 확인 중…")
                    false -> Banner("Non-su 모드: su 권한 없음 — 타 앱 프로세스, 커널 로그, 일부 /proc·/sys 노드는 제한됩니다.")
                    true -> Banner("su 모드: 루트 권한으로 전체 정보를 읽는 중", ok = true)
                }
                ScrollableTabRow(selectedTabIndex = page.ordinal, edgePadding = 8.dp, containerColor = Bg) {
                    Page.entries.forEach { p ->
                        Tab(selected = p == page, onClick = { page = p; sections = emptyList(); query = "" }, text = { Text(p.label, fontSize = 13.sp) })
                    }
                }
                if (page != Page.FILES) OutlinedTextField(
                    query, { query = it }, singleLine = true, placeholder = { Text("검색 / 필터") },
                    modifier = Modifier.fillMaxWidth().padding(12.dp, 6.dp), shape = RoundedCornerShape(12.dp)
                )
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (page == Page.FILES) FileBrowser(
                onShare = { n, t -> Export.share(ctx, n, t) },
                onSave = { n, t -> toast("저장됨: " + Export.saveToDownloads(ctx, n, t)) }
            ) else if (sections.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            } else SectionList(sections, query)
        }
    }
}

@Composable
private fun Banner(text: String, ok: Boolean = false) {
    Surface(color = if (ok) Accent.copy(alpha = 0.15f) else Card, modifier = Modifier.fillMaxWidth()) {
        Text(text, fontSize = 12.sp, color = if (ok) Accent else Dim, modifier = Modifier.padding(12.dp, 6.dp))
    }
}
