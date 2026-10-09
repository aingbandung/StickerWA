package com.firdaus.stickerai

import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlin.math.abs
import kotlin.math.hypot

private const val SHAPE_STRAIGHT = 0
private const val SHAPE_ARC = 1
private const val SHAPE_WAVE = 2
private const val SHAPE_DRAWN = 3

class MainActivity : AppCompatActivity() {
    private var source: Bitmap? = null        // foto asli resolusi lebih besar (untuk crop)
    private var original: Bitmap? = null      // foto yang dipakai (sudah dipotong & diperkecil)
    private var processed: Bitmap? = null     // hasil proses (transparan / kartun)
    private var currentFinal: Bitmap? = null  // hasil akhir + teks

    private lateinit var preview: ImageView
    private lateinit var status: TextView
    private lateinit var caption: EditText
    private lateinit var mode: RadioGroup
    private lateinit var fontSpinner: Spinner
    private lateinit var colorSpinner: Spinner
    private lateinit var boldCheck: CheckBox
    private lateinit var italicCheck: CheckBox
    private lateinit var sizeBar: SeekBar
    private lateinit var sizeLabel: TextView
    private lateinit var processBtn: Button
    private lateinit var packInfo: TextView

    // ---- posisi & bentuk teks ----
    private lateinit var shapeSpinner: Spinner
    private lateinit var rotateBar: SeekBar
    private lateinit var rotateLabel: TextView
    private lateinit var curveBar: SeekBar
    private lateinit var curveLabel: TextView
    private lateinit var drawBtn: Button
    private var textCx = 0.5f            // posisi tengah teks (0..1 dari lebar gambar)
    private var textCy = 0.85f           // posisi tengah teks (0..1 dari tinggi gambar)
    private val drawnPoints = mutableListOf<PointF>()  // garis gambar (koordinat 0..1)
    private var drawMode = false
    private var lastNx = 0f
    private var lastNy = 0f
    private val shapeOptions = listOf("Lurus", "Lengkung", "Gelombang", "Ikuti garis gambar")

    private val black = Color.rgb(20, 20, 20)

    private val fontOptions = listOf(
        "Sans Serif" to "sans-serif",
        "Serif" to "serif",
        "Monospace" to "monospace",
        "Tulisan tangan" to "cursive",
        "Casual" to "casual",
        "Condensed" to "sans-serif-condensed",
        "Medium" to "sans-serif-medium"
    )

    private val colorOptions = listOf(
        "Putih" to Color.WHITE,
        "Hitam" to Color.BLACK,
        "Kuning" to Color.rgb(255, 214, 0),
        "Merah" to Color.rgb(229, 57, 53),
        "Biru" to Color.rgb(30, 136, 229),
        "Hijau" to Color.rgb(67, 160, 71)
    )

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val bmp = loadBitmap(uri, 1280)
            if (bmp == null) {
                status.text = "Gagal membuka foto."
            } else {
                source = bmp
                original = downscale(bmp, 768)
                processed = null
                refreshPreview()
                status.text = "Foto dipilih. Potong foto sesuai keinginan, lalu pilih mode dan tekan Proses dengan AI."
                showCropDialog()
            }
        }
    }

    private val addPackLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK) {
            status.text = "Paket stiker berhasil ditambahkan ke WhatsApp. Buka WhatsApp > ikon stiker untuk memakainya."
        } else {
            val err = res.data?.getStringExtra("validation_error")
            status.text = if (err != null) "WhatsApp menolak paket: $err" else "Penambahan paket ke WhatsApp dibatalkan."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(6, 78, 71)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            setBackgroundColor(Color.rgb(247, 250, 249))
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        val title = TextView(this).apply {
            text = "Firdaus Sticker AI"
            textSize = 26f
            setTextColor(Color.rgb(7, 94, 84))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(4))
            setTypeface(null, Typeface.BOLD)
        }
        val subtitle = TextView(this).apply {
            text = "Ubah foto jadi stiker WhatsApp"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, dp(16))
        }
        content.addView(title)
        content.addView(subtitle)

        preview = ImageView(this).apply {
            background = checkerDrawable()
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Preview foto atau stiker"
        }
        content.addView(preview, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(280)
        ).apply { bottomMargin = dp(4) })
        setupPreviewTouch()
        content.addView(TextView(this).apply {
            text = "Tip: geser pada gambar untuk memindahkan teks."
            textSize = 12f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(10))
        })

        content.addView(button("Pilih foto dari galeri") { pickImage.launch("image/*") })
        content.addView(button("Potong foto (crop)") {
            if (source == null) status.text = "Pilih foto terlebih dahulu."
            else showCropDialog()
        })

        // ---- Mode stiker ----
        content.addView(label("Pilih mode stiker"))
        mode = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        listOf("Full Picture", "Make It Transparent", "Cartoon & Transparent").forEachIndexed { i, text ->
            mode.addView(RadioButton(this).apply {
                id = i + 1
                this.text = text
                textSize = 16f
                setTextColor(black)
                setPadding(dp(4), dp(5), 0, dp(5))
            })
        }
        mode.check(1)
        content.addView(mode)

        // ---- Teks ----
        content.addView(label("Tambahkan teks (opsional)"))
        caption = EditText(this).apply {
            hint = "Contoh: HAI BRO! 😂"
            textSize = 16f
            setTextColor(black)
            setHintTextColor(Color.GRAY)
            setSingleLine(false)
            minLines = 1
            maxLines = 3
        }
        content.addView(caption, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        caption.doAfterTextChanged { refreshPreview() }

        content.addView(label("Gaya font"))
        fontSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                fontOptions.map { it.first }
            )
            onItemSelectedListener = refreshOnSelect()
        }
        content.addView(fontSpinner)

        val styleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        boldCheck = CheckBox(this).apply {
            text = "Tebal"
            setTextColor(black)
            setOnCheckedChangeListener { _, _ -> refreshPreview() }
        }
        italicCheck = CheckBox(this).apply {
            text = "Miring"
            setTextColor(black)
            setOnCheckedChangeListener { _, _ -> refreshPreview() }
        }
        styleRow.addView(boldCheck, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        styleRow.addView(italicCheck, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(styleRow)

        sizeLabel = label("Ukuran teks: 50")
        content.addView(sizeLabel)
        sizeBar = SeekBar(this).apply {
            max = 100
            progress = 50
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    sizeLabel.text = "Ukuran teks: ${progress.coerceAtLeast(10)}"
                    refreshPreview()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        content.addView(sizeBar)

        content.addView(label("Warna teks"))
        colorSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                colorOptions.map { it.first }
            )
            onItemSelectedListener = refreshOnSelect()
        }
        content.addView(colorSpinner)

        // ---- Bentuk & posisi teks ----
        content.addView(label("Bentuk teks"))
        shapeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                shapeOptions
            )
            onItemSelectedListener = refreshOnSelect()
        }
        content.addView(shapeSpinner)

        rotateLabel = label("Kemiringan teks: 0°")
        content.addView(rotateLabel)
        rotateBar = SeekBar(this).apply {
            max = 360
            progress = 180
            setOnSeekBarChangeListener(seekListener { p ->
                rotateLabel.text = "Kemiringan teks: ${p - 180}°"
                refreshPreview()
            })
        }
        content.addView(rotateBar)

        curveLabel = label("Lengkungan / gelombang: 30")
        content.addView(curveLabel)
        curveBar = SeekBar(this).apply {
            max = 200
            progress = 130
            setOnSeekBarChangeListener(seekListener { p ->
                curveLabel.text = "Lengkungan / gelombang: ${p - 100}"
                refreshPreview()
            })
        }
        content.addView(curveBar)

        content.addView(button("Reset posisi teks") {
            textCx = 0.5f
            textCy = 0.85f
            refreshPreview()
            status.text = "Posisi teks dikembalikan. Geser pada gambar untuk memindahkan teks."
        })
        drawBtn = button("Gambar garis untuk teks") { toggleDrawMode() }
        content.addView(drawBtn)
        content.addView(button("Hapus garis gambar") {
            drawnPoints.clear()
            if (shapeSpinner.selectedItemPosition == SHAPE_DRAWN) {
                shapeSpinner.setSelection(SHAPE_STRAIGHT)
            }
            refreshPreview()
        })

        // ---- Tombol aksi ----
        val spacer = View(this)
        content.addView(spacer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14)))

        processBtn = button("Proses dengan AI") { process() }
        content.addView(processBtn)
        content.addView(button("Simpan stiker (PNG)") {
            val bmp = currentFinal
            if (bmp == null) {
                status.text = "Pilih foto dan proses terlebih dahulu."
            } else {
                saveToGallery(bmp)
            }
        })

        // ---- Paket stiker WhatsApp ----
        content.addView(label("Paket stiker WhatsApp"))
        packInfo = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(70, 80, 78))
            setPadding(0, 0, 0, dp(8))
        }
        content.addView(packInfo)
        content.addView(button("Tambah stiker ini ke paket") { addToPack() })
        content.addView(button("Tambahkan paket ke WhatsApp") { sendPackToWhatsApp() })
        content.addView(button("Kosongkan paket") {
            AlertDialog.Builder(this)
                .setTitle("Kosongkan paket?")
                .setMessage("Semua stiker di paket akan dihapus dari aplikasi ini.")
                .setPositiveButton("Hapus") { _, _ ->
                    StickerPackStore.clear(applicationContext)
                    updatePackInfo()
                    status.text = "Paket dikosongkan."
                }
                .setNegativeButton("Batal", null)
                .show()
        })

        status = TextView(this).apply {
            text = "Siap. Pilih foto untuk memulai."
            textSize = 14f
            setTextColor(Color.rgb(70, 80, 78))
            setPadding(0, dp(12), 0, dp(8))
        }
        content.addView(status)
        val note = TextView(this).apply {
            text = "Catatan: pemrosesan AI berjalan di perangkat (ML Kit). Saat pertama kali, internet diperlukan untuk mengunduh model dari Google Play Services."
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, dp(8), 0, 0)
        }
        content.addView(note)
        scroll.addView(content)
        root.addView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(dp(20) + bars.left, dp(18) + bars.top, dp(20) + bars.right, dp(18) + bars.bottom)
            insets
        }
        setContentView(root)
        updatePackInfo()
    }

    // ------------------------------------------------------------------
    // Proses
    // ------------------------------------------------------------------

    private fun process() {
        val src = original
        if (src == null) {
            status.text = "Pilih foto terlebih dahulu."
            return
        }
        when (mode.checkedRadioButtonId) {
            1 -> {
                processed = src
                refreshPreview()
                status.text = "Mode Full Picture: foto dipakai apa adanya. Atur teks lalu simpan."
            }
            else -> runSegmentation(src, mode.checkedRadioButtonId == 3)
        }
    }

    private fun runSegmentation(src: Bitmap, cartoon: Boolean) {
        processBtn.isEnabled = false
        status.text = "Memproses di perangkat... (pertama kali bisa lebih lama karena mengunduh model)"
        val segmenter = SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder().enableForegroundBitmap().build()
        )
        val request = ModuleInstallRequest.newBuilder().addApi(segmenter).build()
        ModuleInstall.getClient(this).installModules(request)
            .addOnSuccessListener {
                segmenter.process(InputImage.fromBitmap(src, 0))
                    .addOnSuccessListener { result ->
                        val fg = result.foregroundBitmap
                        Thread {
                            val out: Bitmap? = try {
                                if (fg == null) null else buildResult(src, fg, cartoon)
                            } catch (e: Throwable) {
                                null
                            }
                            runOnUiThread {
                                segmenter.close()
                                processBtn.isEnabled = true
                                if (out == null) {
                                    status.text = "Gagal membuat stiker. Coba foto lain."
                                } else {
                                    processed = out
                                    refreshPreview()
                                    status.text = "Selesai. Atur teks, font, ukuran, lalu Simpan stiker (PNG)."
                                }
                            }
                        }.start()
                    }
                    .addOnFailureListener { e -> failProcessing(segmenter, e) }
            }
            .addOnFailureListener { e -> failProcessing(segmenter, e) }
    }

    private fun failProcessing(segmenter: com.google.mlkit.vision.segmentation.subject.SubjectSegmenter, e: Exception) {
        segmenter.close()
        processBtn.isEnabled = true
        status.text = "Gagal memproses: ${e.message}. Pastikan internet aktif dan Google Play Services terbaru, lalu coba lagi."
    }

    private fun buildResult(src: Bitmap, fg: Bitmap, cartoon: Boolean): Bitmap {
        val w = src.width
        val h = src.height
        val fgScaled = if (fg.width == w && fg.height == h) fg
        else Bitmap.createScaledBitmap(fg, w, h, true)
        if (!cartoon) return fgScaled

        val cart = cartoonize(src)
        val colors = IntArray(w * h)
        cart.getPixels(colors, 0, w, 0, 0, w, h)
        val alpha = IntArray(w * h)
        fgScaled.getPixels(alpha, 0, w, 0, 0, w, h)
        for (i in colors.indices) {
            colors[i] = (alpha[i] and (0xFF shl 24)) or (colors[i] and 0x00FFFFFF)
        }
        return Bitmap.createBitmap(colors, w, h, Bitmap.Config.ARGB_8888)
    }

    // ------------------------------------------------------------------
    // Efek kartun (di perangkat): haluskan warna, posterisasi, garis tepi
    // ------------------------------------------------------------------

    private fun cartoonize(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        var smooth = boxBlur(px, w, h, 2)
        smooth = boxBlur(smooth, w, h, 2)

        val gray = IntArray(w * h)
        for (i in gray.indices) {
            val p = smooth[i]
            gray[i] = (((p shr 16) and 0xFF) * 30 + ((p shr 8) and 0xFF) * 59 + (p and 0xFF) * 11) / 100
        }

        val levels = 6
        val step = 255f / (levels - 1)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val p = smooth[i]
                val r0 = ((p shr 16) and 0xFF).toFloat()
                val g0 = ((p shr 8) and 0xFF).toFloat()
                val b0 = (p and 0xFF).toFloat()
                val avg = (r0 + g0 + b0) / 3f
                val r1 = (avg + (r0 - avg) * 1.3f).coerceIn(0f, 255f)
                val g1 = (avg + (g0 - avg) * 1.3f).coerceIn(0f, 255f)
                val b1 = (avg + (b0 - avg) * 1.3f).coerceIn(0f, 255f)
                val r = (Math.round(r1 / step) * step).toInt().coerceIn(0, 255)
                val g = (Math.round(g1 / step) * step).toInt().coerceIn(0, 255)
                val b = (Math.round(b1 / step) * step).toInt().coerceIn(0, 255)

                var edge = false
                if (x in 1 until w - 1 && y in 1 until h - 1) {
                    val tl = gray[i - w - 1]
                    val t = gray[i - w]
                    val tr = gray[i - w + 1]
                    val l = gray[i - 1]
                    val rr = gray[i + 1]
                    val bl = gray[i + w - 1]
                    val bt = gray[i + w]
                    val br = gray[i + w + 1]
                    val gx = -tl - 2 * l - bl + tr + 2 * rr + br
                    val gy = -tl - 2 * t - tr + bl + 2 * bt + br
                    edge = abs(gx) + abs(gy) > 70
                }
                out[i] = if (edge) {
                    (0xFF shl 24) or (30 shl 16) or (30 shl 8) or 30
                } else {
                    (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun boxBlur(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        val div = 2 * r + 1
        val tmp = IntArray(w * h)
        val out = IntArray(w * h)

        // horizontal
        for (y in 0 until h) {
            var sr = 0
            var sg = 0
            var sb = 0
            for (k in -r..r) {
                val p = src[y * w + k.coerceIn(0, w - 1)]
                sr += (p shr 16) and 0xFF
                sg += (p shr 8) and 0xFF
                sb += p and 0xFF
            }
            for (x in 0 until w) {
                tmp[y * w + x] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val addP = src[y * w + (x + r + 1).coerceAtMost(w - 1)]
                val subP = src[y * w + (x - r).coerceAtLeast(0)]
                sr += ((addP shr 16) and 0xFF) - ((subP shr 16) and 0xFF)
                sg += ((addP shr 8) and 0xFF) - ((subP shr 8) and 0xFF)
                sb += (addP and 0xFF) - (subP and 0xFF)
            }
        }
        // vertical
        for (x in 0 until w) {
            var sr = 0
            var sg = 0
            var sb = 0
            for (k in -r..r) {
                val p = tmp[k.coerceIn(0, h - 1) * w + x]
                sr += (p shr 16) and 0xFF
                sg += (p shr 8) and 0xFF
                sb += p and 0xFF
            }
            for (y in 0 until h) {
                out[y * w + x] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val addP = tmp[(y + r + 1).coerceAtMost(h - 1) * w + x]
                val subP = tmp[(y - r).coerceAtLeast(0) * w + x]
                sr += ((addP shr 16) and 0xFF) - ((subP shr 16) and 0xFF)
                sg += ((addP shr 8) and 0xFF) - ((subP shr 8) and 0xFF)
                sb += (addP and 0xFF) - (subP and 0xFF)
            }
        }
        return out
    }

    // ------------------------------------------------------------------
    // Teks di atas gambar
    // ------------------------------------------------------------------

    private fun refreshPreview() {
        val base = processed ?: original ?: return
        val out = composeWithText(base)
        currentFinal = out
        preview.setImageBitmap(if (drawMode && drawnPoints.size >= 2) withGuide(out) else out)
    }

    private fun composeWithText(base: Bitmap): Bitmap {
        val text = caption.text.toString().trim()
        if (text.isEmpty()) return base

        val result = base.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        val w = base.width.toFloat()
        val h = base.height.toFloat()

        val textPx = w * sizeBar.progress.coerceAtLeast(10) / 400f
        val fillColor = colorOptions[colorSpinner.selectedItemPosition].second
        val lum = 0.299f * Color.red(fillColor) + 0.587f * Color.green(fillColor) + 0.114f * Color.blue(fillColor)
        val outlineColor = if (lum > 140f) Color.BLACK else Color.WHITE

        val face = when {
            boldCheck.isChecked && italicCheck.isChecked -> Typeface.BOLD_ITALIC
            boldCheck.isChecked -> Typeface.BOLD
            italicCheck.isChecked -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        val typeface = Typeface.create(fontOptions[fontSpinner.selectedItemPosition].second, face)

        val fillPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        fillPaint.textSize = textPx
        fillPaint.color = fillColor
        fillPaint.typeface = typeface
        fillPaint.style = Paint.Style.FILL

        val strokePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        strokePaint.textSize = textPx
        strokePaint.color = outlineColor
        strokePaint.typeface = typeface
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = textPx * 0.14f
        strokePaint.strokeJoin = Paint.Join.ROUND

        val cx = textCx * w
        val cy = textCy * h
        val angle = (rotateBar.progress - 180).toFloat()
        val curve = (curveBar.progress - 100) / 100f

        var shape = shapeSpinner.selectedItemPosition
        if (shape == SHAPE_DRAWN && drawnPoints.size < 2) shape = SHAPE_STRAIGHT

        // ---- Teks lurus (boleh miring) ----
        if (shape == SHAPE_STRAIGHT) {
            val maxW = (w * 0.92f).toInt()
            val fillLayout = StaticLayout.Builder.obtain(text, 0, text.length, fillPaint, maxW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).build()
            val strokeLayout = StaticLayout.Builder.obtain(text, 0, text.length, strokePaint, maxW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).build()
            canvas.save()
            canvas.rotate(angle, cx, cy)
            canvas.translate(cx - maxW / 2f, cy - fillLayout.height / 2f)
            strokeLayout.draw(canvas)
            fillLayout.draw(canvas)
            canvas.restore()
            return result
        }

        // ---- Teks mengikuti garis (lengkung / gelombang / gambar sendiri) ----
        val line = text.replace('\n', ' ')
        val path = if (shape == SHAPE_DRAWN) buildDrawnPath(w, h) else Path()
        var textWidth = fillPaint.measureText(line)
        val limit = if (shape == SHAPE_DRAWN) PathMeasure(path, false).length else w * 0.92f
        if (limit < 8f || textWidth <= 0f) return result
        if (textWidth > limit) {
            val k = limit / textWidth
            fillPaint.textSize = textPx * k
            strokePaint.textSize = textPx * k
            strokePaint.strokeWidth = textPx * k * 0.14f
            textWidth = limit
        }

        var hOffset = 0f
        var rotate = 0f
        if (shape != SHAPE_DRAWN) {
            buildShapePath(path, shape, cx, cy, textWidth, curve)
            val len = PathMeasure(path, false).length
            hOffset = ((len - textWidth) / 2f).coerceAtLeast(0f)
            rotate = angle
        }
        val vOffset = fillPaint.textSize * 0.33f

        canvas.save()
        if (rotate != 0f) canvas.rotate(rotate, cx, cy)
        canvas.drawTextOnPath(line, path, hOffset, vOffset, strokePaint)
        canvas.drawTextOnPath(line, path, hOffset, vOffset, fillPaint)
        canvas.restore()
        return result
    }

    private fun buildShapePath(path: Path, shape: Int, cx: Float, cy: Float, width: Float, curve: Float) {
        if (shape == SHAPE_ARC) {
            val apex = 0.4f * width * curve          // + = tengah naik, - = tengah turun
            val baseY = cy + apex / 2f
            path.moveTo(cx - width / 2f, baseY)
            path.quadTo(cx, baseY - 2f * apex, cx + width / 2f, baseY)
        } else {
            val amp = 0.18f * width * curve
            val steps = 120
            for (i in 0..steps) {
                val x = cx - width / 2f + width * i / steps
                val rad = 2.0 * Math.PI * 1.5 * i / steps
                val y = cy + amp * Math.sin(rad).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
        }
    }

    private fun buildDrawnPath(w: Float, h: Float): Path {
        val path = Path()
        val pts = drawnPoints
        if (pts.size < 2) return path
        path.moveTo(pts[0].x * w, pts[0].y * h)
        for (i in 1 until pts.size - 1) {
            val mx = (pts[i].x + pts[i + 1].x) / 2f * w
            val my = (pts[i].y + pts[i + 1].y) / 2f * h
            path.quadTo(pts[i].x * w, pts[i].y * h, mx, my)
        }
        val last = pts[pts.size - 1]
        path.lineTo(last.x * w, last.y * h)
        return path
    }

    /** Garis bantu (hanya untuk pratinjau, tidak ikut tersimpan). */
    private fun withGuide(src: Bitmap): Bitmap {
        val copy = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(copy)
        val w = copy.width.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.STROKE
        paint.color = Color.rgb(255, 64, 129)
        paint.strokeWidth = w * 0.006f
        paint.pathEffect = DashPathEffect(floatArrayOf(w * 0.02f, w * 0.015f), 0f)
        c.drawPath(buildDrawnPath(w, copy.height.toFloat()), paint)
        return copy
    }

    private fun seekListener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            onChange(progress)
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }

    private fun toggleDrawMode() {
        if ((processed ?: original) == null) {
            status.text = "Pilih foto terlebih dahulu."
            return
        }
        drawMode = !drawMode
        drawBtn.text = if (drawMode) "Selesai menggambar garis" else "Gambar garis untuk teks"
        status.text = if (drawMode) {
            "Mode gambar aktif: gambar garis di atas foto dengan jari. Teks akan mengikuti garis (dari kiri ke kanan)."
        } else {
            "Mode geser aktif: geser pada gambar untuk memindahkan teks."
        }
        refreshPreview()
    }

    private fun setupPreviewTouch() {
        preview.setOnTouchListener { v, ev ->
            val base = processed ?: original
            if (base == null || v.width == 0 || v.height == 0) {
                false
            } else {
                v.parent?.requestDisallowInterceptTouchEvent(true)
                val scale = minOf(v.width.toFloat() / base.width, v.height.toFloat() / base.height)
                val offX = (v.width - base.width * scale) / 2f
                val offY = (v.height - base.height * scale) / 2f
                val nx = ((ev.x - offX) / (base.width * scale)).coerceIn(0f, 1f)
                val ny = ((ev.y - offY) / (base.height * scale)).coerceIn(0f, 1f)

                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (drawMode) {
                            drawnPoints.clear()
                            drawnPoints.add(PointF(nx, ny))
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (drawMode) {
                            val last = drawnPoints.lastOrNull()
                            if (last == null || hypot(nx - last.x, ny - last.y) > 0.012f) {
                                drawnPoints.add(PointF(nx, ny))
                            }
                        } else {
                            val dx = nx - lastNx
                            val dy = ny - lastNy
                            textCx = (textCx + dx).coerceIn(0f, 1f)
                            textCy = (textCy + dy).coerceIn(0f, 1f)
                            if (shapeSpinner.selectedItemPosition == SHAPE_DRAWN) {
                                for (pt in drawnPoints) pt.offset(dx, dy)
                            }
                        }
                        refreshPreview()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (drawMode) finishDrawing()
                    }
                }
                lastNx = nx
                lastNy = ny
                true
            }
        }
    }

    private fun finishDrawing() {
        if (drawnPoints.size >= 2) {
            if (drawnPoints[drawnPoints.size - 1].x < drawnPoints[0].x) drawnPoints.reverse()
            shapeSpinner.setSelection(SHAPE_DRAWN)
            status.text = "Garis dibuat. Ketik teks, lalu tekan Selesai menggambar garis untuk memindahkan teks."
        } else {
            drawnPoints.clear()
        }
        refreshPreview()
    }

    private fun refreshOnSelect() = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            refreshPreview()
        }
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }

    // ------------------------------------------------------------------
    // Simpan PNG 512x512 (transparan) ke galeri
    // ------------------------------------------------------------------

    private fun saveToGallery(bmp: Bitmap) {
        try {
            val square = StickerPackStore.renderSquare(bmp, 512, 0)

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "stiker_${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FirdausStickerAI")
                }
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                status.text = "Gagal menyimpan stiker."
                return
            }
            contentResolver.openOutputStream(uri)?.use { square.compress(Bitmap.CompressFormat.PNG, 100, it) }
            status.text = "Stiker disimpan di galeri: Pictures/FirdausStickerAI"
            Toast.makeText(this, "Stiker disimpan", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            status.text = "Gagal menyimpan stiker: ${e.message}"
        }
    }

    // ------------------------------------------------------------------
    // Crop
    // ------------------------------------------------------------------

    private fun showCropDialog() {
        val src = source ?: return
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val cropView = CropView(this)
        cropView.setBitmap(src)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val hint = TextView(this).apply {
            text = "Geser kotak untuk memindah, tarik sudut/sisi untuk mengubah ukuran."
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(6))
        }
        root.addView(hint)
        root.addView(cropView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val toolRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), 0)
        }
        val gray = Color.rgb(70, 80, 78)
        toolRow.addView(dialogButton("Bebas", gray) { cropView.setAspect(0f) })
        toolRow.addView(dialogButton("1:1", gray) { cropView.setAspect(1f) })
        toolRow.addView(dialogButton("Putar 90°", gray) { cropView.rotate90() })
        root.addView(toolRow)

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(8), dp(8))
        }
        actionRow.addView(dialogButton("Batal", Color.rgb(120, 60, 60)) { dialog.dismiss() })
        actionRow.addView(dialogButton("Potong", Color.rgb(7, 94, 84)) {
            val cropped = cropView.getCroppedBitmap()
            if (cropped != null) {
                cropView.currentBitmap()?.let { source = it }
                original = downscale(cropped, 768)
                processed = null
                refreshPreview()
                status.text = "Foto dipotong. Pilih mode, lalu tekan Proses dengan AI."
            }
            dialog.dismiss()
        })
        root.addView(actionRow)

        dialog.setContentView(root)
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun downscale(bmp: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(bmp.width, bmp.height)
        if (longest <= maxSide) return bmp
        val scale = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bmp,
            (bmp.width * scale).toInt().coerceAtLeast(1),
            (bmp.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun dialogButton(textValue: String, bg: Int, action: () -> Unit): Button =
        Button(this).apply {
            text = textValue
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.WHITE)
            setBackgroundColor(bg)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
        }

    // ------------------------------------------------------------------
    // Paket stiker WhatsApp
    // ------------------------------------------------------------------

    private fun updatePackInfo() {
        val n = StickerPackStore.count(this)
        packInfo.text = "Isi paket: $n/${StickerPackStore.MAX_STICKERS} stiker " +
            "(minimal ${StickerPackStore.MIN_STICKERS} untuk ditambahkan ke WhatsApp)"
    }

    private fun addToPack() {
        val bmp = currentFinal
        if (bmp == null) {
            status.text = "Pilih foto dan proses terlebih dahulu."
            return
        }
        val ctx = applicationContext
        status.text = "Menambahkan stiker ke paket..."
        Thread {
            val msg: String = try {
                val n = StickerPackStore.addSticker(ctx, bmp)
                val need = StickerPackStore.MIN_STICKERS - n
                "Stiker ditambahkan ke paket ($n/${StickerPackStore.MAX_STICKERS})." +
                    if (need > 0) " Tambah $need stiker lagi sebelum dikirim ke WhatsApp."
                    else " Paket siap ditambahkan ke WhatsApp."
            } catch (e: Exception) {
                e.message ?: "Gagal menambahkan stiker."
            }
            runOnUiThread {
                status.text = msg
                updatePackInfo()
            }
        }.start()
    }

    private fun isInstalled(pkg: String): Boolean =
        packageManager.getLaunchIntentForPackage(pkg) != null

    private fun sendPackToWhatsApp() {
        val n = StickerPackStore.count(this)
        if (n < StickerPackStore.MIN_STICKERS) {
            status.text = "Paket baru berisi $n stiker. WhatsApp membutuhkan minimal ${StickerPackStore.MIN_STICKERS} stiker."
            return
        }
        val consumer = isInstalled("com.whatsapp")
        val business = isInstalled("com.whatsapp.w4b")
        if (!consumer && !business) {
            Toast.makeText(this, "WhatsApp tidak ditemukan", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent("com.whatsapp.intent.action.ENABLE_STICKER_PACK").apply {
            putExtra("sticker_pack_id", StickerPackStore.IDENTIFIER)
            putExtra("sticker_pack_authority", StickerPackStore.authority(this@MainActivity))
            putExtra("sticker_pack_name", StickerPackStore.PACK_NAME)
        }
        try {
            if (consumer && business) {
                addPackLauncher.launch(Intent.createChooser(intent, "Tambahkan ke WhatsApp"))
            } else {
                intent.setPackage(if (consumer) "com.whatsapp" else "com.whatsapp.w4b")
                addPackLauncher.launch(intent)
            }
        } catch (e: ActivityNotFoundException) {
            status.text = "Versi WhatsApp Anda belum mendukung paket stiker pihak ketiga. Perbarui WhatsApp lalu coba lagi."
        } catch (e: Exception) {
            status.text = "Tidak dapat membuka WhatsApp: ${e.message}"
        }
    }

    // ------------------------------------------------------------------
    // Util
    // ------------------------------------------------------------------

    private fun loadBitmap(uri: Uri, maxSide: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null

            val rotation = contentResolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f

            val scale = maxSide.toFloat() / maxOf(decoded.width, decoded.height)
            val m = Matrix()
            if (scale < 1f) m.postScale(scale, scale)
            if (rotation != 0f) m.postRotate(rotation)
            if (m.isIdentity) decoded
            else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
        } catch (e: Exception) {
            null
        }
    }

    private fun checkerDrawable(): BitmapDrawable {
        val s = dp(12)
        val bmp = Bitmap.createBitmap(s * 2, s * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint()
        p.color = Color.rgb(245, 245, 245)
        c.drawRect(0f, 0f, (s * 2).toFloat(), (s * 2).toFloat(), p)
        p.color = Color.rgb(215, 215, 215)
        c.drawRect(0f, 0f, s.toFloat(), s.toFloat(), p)
        c.drawRect(s.toFloat(), s.toFloat(), (s * 2).toFloat(), (s * 2).toFloat(), p)
        val d = BitmapDrawable(resources, bmp)
        d.setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        return d
    }

    private fun button(textValue: String, action: () -> Unit): Button =
        Button(this).apply {
            text = textValue
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(7, 94, 84))
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

    private fun label(value: String) = TextView(this).apply {
        text = value
        textSize = 16f
        setTextColor(Color.rgb(40, 55, 52))
        setTypeface(null, Typeface.BOLD)
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
