package com.junu.phonestat

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

object Shell {
    @Volatile var rootChecked = false
    @Volatile var hasRoot = false

    /** su 사용 가능 여부 확인 (최초 1회 캐시) */
    fun checkRoot(force: Boolean = false): Boolean {
        if (rootChecked && !force) return hasRoot
        hasRoot = try {
            val r = run(arrayOf("su", "-c", "id"), 6)
            r.contains("uid=0")
        } catch (e: Throwable) { false }
        rootChecked = true
        return hasRoot
    }

    private fun run(cmd: Array<String>, timeoutSec: Long): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = StringBuilder()
        val t = Thread {
            try {
                BufferedReader(InputStreamReader(p.inputStream)).useLines { ls ->
                    var n = 0
                    for (l in ls) { out.append(l).append('\n'); if (++n > 20000) break }
                }
            } catch (_: Throwable) {}
        }
        t.start()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) p.destroyForcibly()
        t.join(1500)
        return out.toString()
    }

    /** 명령 실행: root면 su, 아니면 sh */
    fun exec(cmd: String, timeoutSec: Long = 15): String = try {
        if (hasRoot) run(arrayOf("su", "-c", cmd), timeoutSec)
        else run(arrayOf("sh", "-c", cmd), timeoutSec)
    } catch (e: Throwable) { "ERR: ${e.message}" }

    /** 파일 읽기: 먼저 직접, 실패하면 root cat */
    fun readFile(path: String, max: Int = 400_000): String {
        try {
            val f = File(path)
            if (f.isFile && f.canRead()) {
                val txt = f.inputStream().use { it.readNBytes(max).toString(Charsets.UTF_8) }
                if (txt.isNotEmpty()) return txt
            }
        } catch (_: Throwable) {}
        return if (hasRoot) exec("cat '$path' 2>&1 | head -c $max") else ""
    }

    /** 디렉터리 목록 (이름, 디렉터리 여부) */
    fun list(path: String): List<Pair<String, Boolean>> {
        val f = File(path)
        val names = f.list()
        if (names != null) {
            return names.sorted().map { it to File(f, it).isDirectory }
        }
        if (!hasRoot) return emptyList()
        return exec("ls -1Ap '$path' 2>/dev/null").lines().filter { it.isNotBlank() }
            .map { if (it.endsWith("/")) it.dropLast(1) to true else it to false }
    }
}
