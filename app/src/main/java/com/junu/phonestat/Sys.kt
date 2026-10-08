package com.junu.phonestat

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import java.io.File

data class Kv(val k: String, val v: String)
data class Section(val title: String, val items: List<Kv>)

fun Section.toText() = buildString {
    appendLine("## $title")
    items.forEach { appendLine("${it.k} = ${it.v}") }
}

object Sys {
    private fun rd(p: String) = Shell.readFile(p, 200_000).trim()
    private fun kvLines(text: String, sep: Char = ':') = text.lines().filter { it.contains(sep) }
        .map { Kv(it.substringBefore(sep).trim(), it.substringAfter(sep).trim()) }

    // ---------- CPU ----------
    private var prev: Map<String, LongArray> = emptyMap()

    private fun readStat(): Map<String, LongArray> =
        rd("/proc/stat").lines().filter { it.startsWith("cpu") }.associate { l ->
            val p = l.trim().split(Regex("\\s+"))
            p[0] to p.drop(1).map { it.toLongOrNull() ?: 0L }.toLongArray()
        }

    fun cpu(): List<Section> {
        val cur = readStat()
        val usage = cur.map { (name, v) ->
            val o = prev[name]
            val total = v.sum()
            val idle = v.getOrElse(3) { 0 } + v.getOrElse(4) { 0 }
            val pct = if (o == null) 0.0 else {
                val dt = total - o.sum()
                val di = idle - (o.getOrElse(3) { 0 } + o.getOrElse(4) { 0 })
                if (dt > 0) (dt - di) * 100.0 / dt else 0.0
            }
            Kv(if (name == "cpu") "전체" else name, "%.1f %%".format(pct))
        }
        prev = cur
        val cores = File("/sys/devices/system/cpu").list()?.filter { it.matches(Regex("cpu\\d+")) }
            ?.sortedBy { it.removePrefix("cpu").toInt() } ?: emptyList()
        val freq = cores.flatMap { c ->
            val b = "/sys/devices/system/cpu/$c/cpufreq"
            val cur = rd("$b/scaling_cur_freq").toLongOrNull()
            val mx = rd("$b/cpuinfo_max_freq").toLongOrNull()
            val mn = rd("$b/cpuinfo_min_freq").toLongOrNull()
            val gov = rd("$b/scaling_governor")
            val on = rd("/sys/devices/system/cpu/$c/online").ifEmpty { "1" }
            listOf(Kv(c, "${cur?.div(1000) ?: "?"} MHz (min ${mn?.div(1000) ?: "?"} / max ${mx?.div(1000) ?: "?"}) gov=$gov online=$on"))
        }
        return listOf(
            Section("CPU 사용률", usage),
            Section("CPU 주파수", freq),
            Section("CPU 기본 정보", listOf(
                Kv("ABI", Build.SUPPORTED_ABIS.joinToString()),
                Kv("코어 수", Runtime.getRuntime().availableProcessors().toString()),
                Kv("Hardware", Build.HARDWARE), Kv("Board", Build.BOARD),
                Kv("SoC", if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else "N/A"),
                Kv("loadavg", rd("/proc/loadavg")),
            )),
            Section("/proc/cpuinfo", kvLines(rd("/proc/cpuinfo")).distinct()),
        )
    }

    // ---------- MEMORY ----------
    fun memory(): List<Section> {
        val m = kvLines(rd("/proc/meminfo"))
        fun kb(key: String) = m.firstOrNull { it.k == key }?.v?.split(" ")?.firstOrNull()?.toLongOrNull() ?: 0L
        val total = kb("MemTotal"); val avail = kb("MemAvailable")
        val used = total - avail
        val summary = listOf(
            Kv("총 메모리", "${total / 1024} MB"),
            Kv("사용 중", "${used / 1024} MB (${if (total > 0) used * 100 / total else 0}%)"),
            Kv("사용 가능", "${avail / 1024} MB"),
            Kv("스왑(zram) 사용", "${(kb("SwapTotal") - kb("SwapFree")) / 1024} / ${kb("SwapTotal") / 1024} MB"),
        )
        return listOf(
            Section("메모리 요약", summary),
            Section("/proc/meminfo 전체", m),
            Section("/proc/vmstat", kvLines(rd("/proc/vmstat"), ' ')),
            Section("zram / swaps", rd("/proc/swaps").lines().filter { it.isNotBlank() }.map { Kv("", it) }),
        )
    }

    // ---------- GPU ----------
    fun gpu(): List<Section> {
        val out = mutableListOf<Kv>()
        val files = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpuclk", "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", "/sys/class/kgsl/kgsl-3d0/gpubusy",
            "/sys/class/kgsl/kgsl-3d0/max_gpuclk", "/sys/class/kgsl/kgsl-3d0/temp",
            "/sys/class/kgsl/kgsl-3d0/gpu_model", "/sys/class/kgsl/kgsl-3d0/thermal_pwrlevel",
            "/sys/kernel/gpu/gpu_busy", "/sys/kernel/gpu/gpu_clock", "/sys/kernel/gpu/gpu_model",
            "/sys/module/ged/parameters/gpu_loading", "/sys/class/misc/mali0/device/utilization",
            "/sys/class/misc/mali0/device/clock",
        )
        files.forEach { f -> rd(f).takeIf { it.isNotEmpty() }?.let { out += Kv(f, it) } }
        File("/sys/class/devfreq").list()?.sorted()?.forEach { d ->
            val b = "/sys/class/devfreq/$d"
            val cur = rd("$b/cur_freq")
            if (cur.isNotEmpty()) out += Kv(d, "cur=$cur min=${rd("$b/min_freq")} max=${rd("$b/max_freq")} gov=${rd("$b/governor")}")
        }
        if (out.isEmpty()) out += Kv("GPU", "읽을 수 있는 노드 없음 (su 필요하거나 SoC 미지원)")
        return listOf(Section("GPU", out))
    }

    // ---------- BATTERY ----------
    fun battery(ctx: Context): List<Section> {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scl = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val api = listOf(
            Kv("잔량", "${lvl * 100 / scl} %"),
            Kv("온도", "${(i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0} °C"),
            Kv("전압", "${i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)} mV"),
            Kv("상태", "status=${i?.getIntExtra(BatteryManager.EXTRA_STATUS, 0)} health=${i?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)} plugged=${i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)}"),
            Kv("기술", i?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "?"),
            Kv("현재 전류", "${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)} µA"),
            Kv("평균 전류", "${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)} µA"),
            Kv("충전 카운터", "${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)} µAh"),
            Kv("에너지", "${bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)} nWh"),
        )
        val ps = File("/sys/class/power_supply").list()?.sorted()?.map { n ->
            val d = "/sys/class/power_supply/$n"
            Section("power_supply/$n", File(d).list()?.sorted()?.mapNotNull { f ->
                val v = rd("$d/$f"); if (v.isNotEmpty() && v.length < 200 && !v.contains('\n')) Kv(f, v) else null
            } ?: emptyList())
        } ?: emptyList()
        return listOf(Section("배터리 (API)", api)) + ps
    }

    // ---------- THERMAL ----------
    fun thermal(): List<Section> {
        val zones = File("/sys/class/thermal").list()?.filter { it.startsWith("thermal_zone") }
            ?.sortedBy { it.removePrefix("thermal_zone").toIntOrNull() ?: 0 }?.map { z ->
                val b = "/sys/class/thermal/$z"
                val t = rd("$b/temp").toLongOrNull()
                val shown = when { t == null -> "?"; Math.abs(t) > 1000 -> "%.1f °C".format(t / 1000.0); else -> "$t °C" }
                Kv("$z (${rd("$b/type")})", shown)
            } ?: emptyList()
        val cool = File("/sys/class/thermal").list()?.filter { it.startsWith("cooling_device") }?.sorted()?.map { c ->
            Kv("$c (${rd("/sys/class/thermal/$c/type")})", "cur=${rd("/sys/class/thermal/$c/cur_state")} max=${rd("/sys/class/thermal/$c/max_state")}")
        } ?: emptyList()
        return listOf(Section("온도 존", zones), Section("쿨링 디바이스", cool))
    }

    // ---------- KERNEL / DEVICE ----------
    fun kernel(): List<Section> = listOf(
        Section("커널", listOf(
            Kv("version", rd("/proc/version")), Kv("cmdline", rd("/proc/cmdline")),
            Kv("uptime(proc)", rd("/proc/uptime")),
            Kv("uptime(boot)", "${SystemClock.elapsedRealtime() / 1000} s"),
            Kv("SELinux", Shell.exec("getenforce").trim()),
            Kv("uname", Shell.exec("uname -a").trim()),
        )),
        Section("로드된 모듈 (/proc/modules)", rd("/proc/modules").lines().filter { it.isNotBlank() }.map { Kv(it.substringBefore(' '), it.substringAfter(' ')) }),
        Section("파일시스템 (/proc/filesystems)", rd("/proc/filesystems").lines().filter { it.isNotBlank() }.map { Kv("", it.trim()) }),
        Section("인터럽트 (/proc/interrupts)", rd("/proc/interrupts").lines().take(150).map { Kv("", it.trim()) }),
    )

    fun device(): List<Section> = listOf(
        Section("기기", listOf(
            Kv("모델", "${Build.MANUFACTURER} ${Build.MODEL}"), Kv("제품", Build.PRODUCT), Kv("디바이스", Build.DEVICE),
            Kv("Android", "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"),
            Kv("보안 패치", Build.VERSION.SECURITY_PATCH), Kv("빌드", Build.DISPLAY), Kv("Fingerprint", Build.FINGERPRINT),
            Kv("Bootloader", Build.BOOTLOADER), Kv("Tags/Type", "${Build.TAGS}/${Build.TYPE}"),
        )),
        Section("getprop 전체", kvLines(Shell.exec("getprop"), ']').map { Kv(it.k.trim('[', ' '), it.v.trim('[', ']', ' ', ':')) }),
    )
}
