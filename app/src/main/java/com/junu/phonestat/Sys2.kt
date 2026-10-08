package com.junu.phonestat

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Environment
import android.os.StatFs

object Sys2 {
    private fun rd(p: String) = Shell.readFile(p, 200_000).trim()
    private fun lines(t: String) = t.lines().filter { it.isNotBlank() }.map { Kv("", it) }

    fun network(): List<Section> = listOf(
        Section("/proc/net/dev", lines(rd("/proc/net/dev"))),
        Section("ip addr", lines(Shell.exec("ip addr 2>&1"))),
        Section("ip route", lines(Shell.exec("ip route 2>&1; ip rule 2>&1"))),
        Section("TCP 연결 (/proc/net/tcp)", lines(rd("/proc/net/tcp")).take(200)),
        Section("TCP6", lines(rd("/proc/net/tcp6")).take(200)),
        Section("UDP", lines(rd("/proc/net/udp")).take(100)),
        Section("ARP", lines(rd("/proc/net/arp"))),
        Section("DNS / 프록시 prop", lines(Shell.exec("getprop | grep -Ei 'dns|proxy|net\\.'"))),
    )

    fun storage(): List<Section> {
        fun st(path: String): Kv = try {
            val s = StatFs(path)
            Kv(path, "총 ${s.totalBytes / 1048576} MB / 사용 ${(s.totalBytes - s.availableBytes) / 1048576} MB / 여유 ${s.availableBytes / 1048576} MB")
        } catch (e: Throwable) { Kv(path, "읽기 실패") }
        return listOf(
            Section("용량 (StatFs)", listOf(st(Environment.getDataDirectory().path), st(Environment.getExternalStorageDirectory().path), st("/system"), st("/vendor"), st("/cache"))),
            Section("df -h", lines(Shell.exec("df -h 2>&1"))),
            Section("/proc/mounts", lines(rd("/proc/mounts"))),
            Section("/proc/diskstats", lines(rd("/proc/diskstats"))),
            Section("/proc/partitions", lines(rd("/proc/partitions"))),
            Section("블록 디바이스 by-name", lines(Shell.exec("ls -l /dev/block/by-name 2>&1 | head -80"))),
        )
    }

    fun sensors(ctx: Context): List<Section> {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        return listOf(Section("센서 (${sm.getSensorList(Sensor.TYPE_ALL).size}개)", sm.getSensorList(Sensor.TYPE_ALL).map {
            Kv(it.name, "type=${it.stringType} vendor=${it.vendor} range=${it.maximumRange} res=${it.resolution} power=${it.power}mA minDelay=${it.minDelay}µs")
        }))
    }

    fun apps(ctx: Context): List<Section> {
        val pm = ctx.packageManager
        val all = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        fun row(a: ApplicationInfo): Kv {
            val ver = try { pm.getPackageInfo(a.packageName, 0).versionName } catch (_: Throwable) { "?" }
            val flags = buildList {
                if (a.flags and ApplicationInfo.FLAG_SYSTEM != 0) add("SYSTEM")
                if (a.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0) add("UPDATED")
                if (a.flags and ApplicationInfo.FLAG_PERSISTENT != 0) add("PERSISTENT")
                if (a.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) add("DEBUGGABLE")
                if (!a.enabled) add("DISABLED")
            }.joinToString(",")
            return Kv("${pm.getApplicationLabel(a)} [${a.packageName}]", "v$ver uid=${a.uid} sdk=${a.targetSdkVersion} [$flags] apk=${a.sourceDir} data=${a.dataDir}")
        }
        val sys = all.filter { it.flags and ApplicationInfo.FLAG_SYSTEM != 0 }.sortedBy { it.packageName }
        val usr = all.filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }.sortedBy { it.packageName }
        return listOf(Section("사용자 앱 (${usr.size})", usr.map(::row)), Section("시스템 앱 (${sys.size})", sys.map(::row)))
    }

    fun processes(): List<Section> {
        val ps = Shell.exec("ps -A -o PID,PPID,USER,RSS,VSZ,S,NAME 2>&1")
        val rows = ps.lines().drop(1).filter { it.isNotBlank() }.map { l ->
            val p = l.trim().split(Regex("\\s+"), 7)
            if (p.size >= 7) Kv("${p[0]} ${p[6]}", "ppid=${p[1]} user=${p[2]} rss=${p[3]}KB vsz=${p[4]}KB state=${p[5]}") else Kv("", l)
        }.sortedByDescending { it.v.substringAfter("rss=").substringBefore("KB").toLongOrNull() ?: 0 }
        val kt = rows.count { it.k.contains("[") }
        return listOf(
            Section("프로세스 (${rows.size}개, 커널스레드 추정 ${kt}개) — RSS 내림차순", rows),
            Section("top 요약", lines(Shell.exec("top -b -n 1 -m 25 2>&1"))),
        )
    }

    fun services(): List<Section> = listOf(
        Section("Binder 서비스 (service list)", lines(Shell.exec("service list 2>&1"))),
        Section("dumpsys -l", lines(Shell.exec("dumpsys -l 2>&1"))),
        Section("실행 중 서비스 prop (init.svc)", lines(Shell.exec("getprop | grep 'init.svc'"))),
        Section("실행 중 앱 서비스", lines(Shell.exec("dumpsys activity services 2>&1 | grep -E 'ServiceRecord' | head -150", 25))),
    )

    fun logs(): List<Section> = listOf(
        Section("logcat 최근 500줄", lines(Shell.exec("logcat -d -t 500 -v threadtime 2>&1", 20))),
        Section("dmesg", lines(Shell.exec("dmesg 2>&1 | tail -300"))),
    )
}
