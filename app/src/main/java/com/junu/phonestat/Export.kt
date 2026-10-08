package com.junu.phonestat

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Export {
    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    fun sectionsToText(title: String, sections: List<Section>, asJson: Boolean = false): String {
        if (!asJson) return buildString {
            appendLine("# PhoneStat - $title  (${Date()})")
            appendLine("# root=${Shell.hasRoot}")
            sections.forEach { appendLine(); append(it.toText()) }
        }
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t")
        return buildString {
            append("{\"title\":\"${esc(title)}\",\"time\":\"${Date()}\",\"root\":${Shell.hasRoot},\"sections\":[")
            sections.forEachIndexed { i, s ->
                if (i > 0) append(',')
                append("{\"name\":\"${esc(s.title)}\",\"items\":[")
                s.items.forEachIndexed { j, kv -> if (j > 0) append(','); append("{\"k\":\"${esc(kv.k)}\",\"v\":\"${esc(kv.v)}\"}") }
                append("]}")
            }
            append("]}")
        }
    }

    /** 공유 시트로 내보내기 */
    fun share(ctx: Context, baseName: String, text: String, ext: String = "txt") {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, "${baseName}_${stamp()}.$ext").apply { writeText(text) }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = if (ext == "json") "application/json" else "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, "내보내기").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Download 폴더에 저장, 저장 경로 반환 */
    fun saveToDownloads(ctx: Context, baseName: String, text: String, ext: String = "txt"): String {
        val name = "${baseName}_${stamp()}.$ext"
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, if (ext == "json") "application/json" else "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/PhoneStat")
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
                ctx.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
                "Download/PhoneStat/$name"
            } else {
                val f = File(ctx.getExternalFilesDir(null), name).apply { writeText(text) }
                f.absolutePath
            }
        } catch (e: Throwable) { "저장 실패: ${e.message}" }
    }
}
