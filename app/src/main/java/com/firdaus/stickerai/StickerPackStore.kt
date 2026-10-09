package com.firdaus.stickerai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Menyimpan paket stiker di penyimpanan internal aplikasi (filesDir/stickerpack)
 * dalam format yang diminta WhatsApp:
 *  - stiker: WebP 512x512, maksimal 100 KB
 *  - tray icon: PNG 96x96, maksimal 50 KB
 */
object StickerPackStore {
    const val IDENTIFIER = "firdaus_sticker_ai_pack"
    const val PACK_NAME = "Firdaus Sticker AI"
    const val PUBLISHER = "Firdaus"
    const val TRAY_FILE = "tray.png"
    const val DEFAULT_EMOJI = "😀"
    const val MIN_STICKERS = 3
    const val MAX_STICKERS = 30

    private const val PREFS = "stickerpack"
    private const val KEY_VERSION = "image_data_version"
    private const val MAX_STICKER_BYTES = 100 * 1024
    private const val MAX_TRAY_BYTES = 50 * 1024

    fun authority(context: Context): String = context.packageName + ".stickercontentprovider"

    private fun baseUri(context: Context): Uri =
        Uri.parse("content://" + authority(context) + "/metadata")

    fun dir(context: Context): File {
        val d = File(context.filesDir, "stickerpack")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun stickerFiles(context: Context): List<File> {
        val files = dir(context).listFiles { f ->
            f.isFile && f.name.startsWith("sticker_") && f.name.endsWith(".webp")
        }
        return files?.sortedBy { it.name } ?: emptyList()
    }

    fun count(context: Context): Int = stickerFiles(context).size

    fun imageDataVersion(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_VERSION, 1).toString()

    private fun bumpVersion(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putInt(KEY_VERSION, p.getInt(KEY_VERSION, 1) + 1).apply()
        context.contentResolver.notifyChange(baseUri(context), null)
    }

    /** Tambah satu stiker ke paket. Mengembalikan jumlah stiker sekarang. */
    @Synchronized
    fun addSticker(context: Context, bitmap: Bitmap): Int {
        val existing = stickerFiles(context)
        if (existing.size >= MAX_STICKERS) {
            throw IllegalStateException("Paket penuh (maksimal $MAX_STICKERS stiker).")
        }
        val square = renderSquare(bitmap, 512, 8)
        val data = encodeWebp(square)
            ?: throw IllegalStateException("Ukuran stiker lebih dari 100 KB. Coba gambar yang lebih sederhana.")
        val file = File(dir(context), "sticker_" + System.currentTimeMillis() + ".webp")
        file.writeBytes(data)

        val tray = trayFile(context)
        if (existing.isEmpty() || !tray.exists()) {
            writeTray(tray, square)
        }
        bumpVersion(context)
        return existing.size + 1
    }

    @Synchronized
    fun clear(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
        bumpVersion(context)
    }

    fun trayFile(context: Context): File = File(dir(context), TRAY_FILE)

    private fun writeTray(target: File, square: Bitmap) {
        val tray = Bitmap.createScaledBitmap(square, 96, 96, true)
        val out = ByteArrayOutputStream()
        tray.compress(Bitmap.CompressFormat.PNG, 100, out)
        if (out.size() > MAX_TRAY_BYTES) {
            // sangat jarang terjadi; kecilkan dengan skala lebih rendah
            val small = Bitmap.createScaledBitmap(square, 64, 64, true)
            out.reset()
            small.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        target.writeBytes(out.toByteArray())
    }

    /** Gambar bitmap di tengah kanvas persegi transparan. */
    fun renderSquare(bmp: Bitmap, size: Int, margin: Int = 0): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val inner = (size - 2 * margin).toFloat()
        val scale = minOf(inner / bmp.width, inner / bmp.height)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        val dst = RectF((size - dw) / 2f, (size - dh) / 2f, (size + dw) / 2f, (size + dh) / 2f)
        canvas.drawBitmap(bmp, null, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    @Suppress("DEPRECATION")
    private fun webpFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
        else Bitmap.CompressFormat.WEBP

    private fun encodeWebp(bmp: Bitmap): ByteArray? {
        var quality = 90
        while (quality >= 20) {
            val out = ByteArrayOutputStream()
            bmp.compress(webpFormat(), quality, out)
            if (out.size() in 1..MAX_STICKER_BYTES) return out.toByteArray()
            quality -= 10
        }
        return null
    }
}
