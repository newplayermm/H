package com.example.floatinghud

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.abs

class HudAccessibilityService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private lateinit var hudView: View
    private lateinit var layoutParams: WindowManager.LayoutParams

    private lateinit var btnShowHud: ImageButton
    private lateinit var panelHudBlack: LinearLayout
    private lateinit var btnScan: Button
    private lateinit var btnToggleType: Button
    private lateinit var btnHideHud: Button
    private lateinit var layoutManualInput: LinearLayout
    private lateinit var etManualQuestion: EditText
    private lateinit var btnSendText: Button
    private lateinit var tvAnswerResult: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var isBusy = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        setupHudOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Tidak perlu memproses event latar belakang
    }

    override fun onInterrupt() {
        // Dipanggil sistem jika layanan diinterupsi
    }

    fun refreshSettings() {
        if (!::btnShowHud.isInitialized || !::panelHudBlack.isInitialized) return
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val hudAlphaPercent = prefs.getInt(MainActivity.KEY_HUD_ALPHA, 85).coerceIn(15, 100)
        val showAlphaPercent = prefs.getInt(MainActivity.KEY_SHOW_ALPHA, 20).coerceIn(5, 80)
        panelHudBlack.alpha = hudAlphaPercent / 100f
        btnShowHud.alpha = showAlphaPercent / 100f
    }

    fun setHudVisible(visible: Boolean) {
        if (!::hudView.isInitialized) return
        if (visible) {
            hudView.visibility = View.VISIBLE
            btnShowHud.visibility = View.VISIBLE
            panelHudBlack.visibility = View.GONE
        } else {
            setManualInputVisible(false)
            hudView.visibility = View.GONE
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupHudOverlay() {
        hudView = LayoutInflater.from(this).inflate(R.layout.layout_floating_hud, null)

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 220
        }

        btnShowHud = hudView.findViewById(R.id.btnShowHud)
        panelHudBlack = hudView.findViewById(R.id.panelHudBlack)
        btnScan = hudView.findViewById(R.id.btnScan)
        btnToggleType = hudView.findViewById(R.id.btnToggleType)
        btnHideHud = hudView.findViewById(R.id.btnHideHud)
        layoutManualInput = hudView.findViewById(R.id.layoutManualInput)
        etManualQuestion = hudView.findViewById(R.id.etManualQuestion)
        btnSendText = hudView.findViewById(R.id.btnSendText)
        tvAnswerResult = hudView.findViewById(R.id.tvAnswerResult)

        refreshSettings()

        // Drag + Klik untuk Tombol Show Transparan
        attachDragAndClick(btnShowHud) {
            btnShowHud.visibility = View.GONE
            panelHudBlack.visibility = View.VISIBLE
        }

        // Drag untuk Panel HUD Hitam
        attachDragAndClick(panelHudBlack, onClick = null)

        // Tombol Hide -> tutup keyboard & kembali ke tombol Show transparan
        btnHideHud.setOnClickListener {
            setManualInputVisible(false)
            panelHudBlack.visibility = View.GONE
            btnShowHud.visibility = View.VISIBLE
        }

        // Tombol Toggle Ketik Manual
        btnToggleType.setOnClickListener {
            val willShow = layoutManualInput.visibility != View.VISIBLE
            setManualInputVisible(willShow)
        }

        // Kirim soal manual
        btnSendText.setOnClickListener {
            submitManualQuestion()
        }

        etManualQuestion.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                submitManualQuestion()
                true
            } else {
                false
            }
        }

        // Tombol Scan Soal Layar (Tanpa MediaProjection / Tanpa Kapsul Merah)
        btnScan.setOnClickListener {
            if (!isBusy) {
                setManualInputVisible(false)
                triggerScreenScan(allowCooldownRetry = true)
            }
        }

        windowManager.addView(hudView, layoutParams)
    }

    private fun setManualInputVisible(visible: Boolean) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (visible) {
            layoutManualInput.visibility = View.VISIBLE
            layoutParams.flags =
                layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            windowManager.updateViewLayout(hudView, layoutParams)
            etManualQuestion.requestFocus()
            mainHandler.postDelayed({
                imm.showSoftInput(etManualQuestion, InputMethodManager.SHOW_IMPLICIT)
            }, 80)
        } else {
            imm.hideSoftInputFromWindow(etManualQuestion.windowToken, 0)
            etManualQuestion.clearFocus()
            layoutManualInput.visibility = View.GONE
            layoutParams.flags =
                layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            windowManager.updateViewLayout(hudView, layoutParams)
        }
    }

    private fun submitManualQuestion() {
        if (isBusy) return
        val questionText = etManualQuestion.text.toString().trim()
        if (questionText.isEmpty()) return

        setManualInputVisible(false)
        isBusy = true
        btnScan.isEnabled = false
        tvAnswerResult.text = "Menjawab..."

        ioExecutor.execute {
            val answer = queryGemini(base64Image = null, textPrompt = questionText)
            mainHandler.post {
                tvAnswerResult.text = answer
                etManualQuestion.setText("")
                isBusy = false
                btnScan.isEnabled = true
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragAndClick(targetView: View, onClick: (() -> Unit)?) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        targetView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) {
                        isDragging = true
                    }
                    if (isDragging) {
                        layoutParams.x = initialX + dx
                        layoutParams.y = initialY + dy
                        windowManager.updateViewLayout(hudView, layoutParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        onClick?.invoke()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun triggerScreenScan(allowCooldownRetry: Boolean) {
        isBusy = true
        btnScan.isEnabled = false
        // Sembunyikan HUD hitam sejenak agar tidak ikut terpotret
        panelHudBlack.visibility = View.INVISIBLE

        mainHandler.postDelayed({
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val hardwareBuffer = screenshot.hardwareBuffer
                        val colorSpace = screenshot.colorSpace
                        val hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                        hardwareBuffer.close()

                        panelHudBlack.visibility = View.VISIBLE

                        if (hardwareBitmap == null) {
                            tvAnswerResult.text = "Gagal memproses gambar layar."
                            isBusy = false
                            btnScan.isEnabled = true
                            return
                        }

                        val softBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                        hardwareBitmap.recycle()

                        if (softBitmap == null) {
                            tvAnswerResult.text = "Gagal menyalin gambar layar."
                            isBusy = false
                            btnScan.isEnabled = true
                            return
                        }

                        if (isScreenBlockedBlack(softBitmap)) {
                            softBitmap.recycle()
                            tvAnswerResult.text =
                                "Layar diblokir proteksi anti-screenshot (FLAG_SECURE). Gunakan tombol KETIK di atas."
                            isBusy = false
                            btnScan.isEnabled = true
                            return
                        }

                        tvAnswerResult.text = "Scanning..."

                        ioExecutor.execute {
                            val base64Img = encodeBitmapFast(softBitmap)
                            softBitmap.recycle()

                            val answerText = queryGemini(
                                base64Image = base64Img,
                                textPrompt = "Pilih opsi paling benar dari soal di layar secara instan."
                            )

                            mainHandler.post {
                                tvAnswerResult.text = answerText
                                isBusy = false
                                btnScan.isEnabled = true
                            }
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && allowCooldownRetry) {
                            mainHandler.postDelayed({
                                triggerScreenScan(allowCooldownRetry = false)
                            }, 900)
                            return
                        }

                        panelHudBlack.visibility = View.VISIBLE
                        tvAnswerResult.text = when (errorCode) {
                            ERROR_TAKE_SCREENSHOT_INVALID_WINDOW ->
                                "Layar dilindungi proteksi sistem. Gunakan tombol KETIK."
                            ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
                                "Terlalu cepat. Tunggu 1 detik lalu tekan SCAN lagi."
                            else ->
                                "Gagal mengambil layar (Kode: $errorCode). Coba tekan SCAN lagi."
                        }
                        isBusy = false
                        btnScan.isEnabled = true
                    }
                }
            )
        }, 80)
    }

    private fun isScreenBlockedBlack(bitmap: Bitmap): Boolean {
        val startX = (bitmap.width * 0.1).toInt()
        val endX = (bitmap.width * 0.9).toInt()
        val startY = (bitmap.height * 0.15).toInt()
        val endY = (bitmap.height * 0.85).toInt()

        val stepX = ((endX - startX) / 15).coerceAtLeast(1)
        val stepY = ((endY - startY) / 15).coerceAtLeast(1)

        var total = 0
        var blackPixels = 0

        var y = startY
        while (y < endY) {
            var x = startX
            while (x < endX) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.red(pixel) < 8 && Color.green(pixel) < 8 && Color.blue(pixel) < 8) {
                    blackPixels++
                }
                total++
                x += stepX
            }
            y += stepY
        }

        return total > 0 && blackPixels >= (total * 0.98)
    }

    private fun encodeBitmapFast(bitmap: Bitmap): String {
        val maxWidth = 640
        val scaledBitmap = if (bitmap.width > maxWidth) {
            val ratio = maxWidth.toFloat() / bitmap.width.toFloat()
            val newHeight = (bitmap.height * ratio).toInt()
            Bitmap.createScaledBitmap(bitmap, maxWidth, newHeight, true)
        } else {
            bitmap
        }

        val outputStream = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 60, outputStream)
        if (scaledBitmap != bitmap) {
            scaledBitmap.recycle()
        }
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    private fun queryGemini(base64Image: String?, textPrompt: String): String {
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val apiKey = prefs.getString(MainActivity.KEY_API_KEY, "")?.trim().orEmpty()
        val selectedModel =
            prefs.getString(MainActivity.KEY_MODEL, "gemini-3.5-flash-lite") ?: "gemini-3.5-flash-lite"

        if (apiKey.isEmpty()) {
            return "API Key kosong. Buka aplikasi utama untuk mengisi API Key."
        }

        val candidateModels = listOf(
            selectedModel,
            "gemini-3.5-flash-lite",
            "gemini-3.8-flash",
            "gemini-2.5-flash"
        ).distinct()

        var lastError = "Gagal menghubungi Gemini API"

        for (modelName in candidateModels) {
            var disableThinking = true
            while (true) {
                try {
                    val endpoint =
                        "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"
                    val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                        connectTimeout = 15000
                        readTimeout = 30000
                        doOutput = true
                    }

                    val referenceMaterial = """
                        REFERENSI MATERI WAJIB (gunakan definisi persis ini jika pertanyaan membahas pengertian informasi menurut para ahli berikut):
                        1. Abdul Kadir (2002: 31) mendefinisikan informasi sebagai data yang telah diproses sedemikian rupa sehingga meningkatkan pengetahuan seseorang yang menggunakan data tersebut.
                        2. Anton M. Moeliono (1990: 331) mendefinisikan informasi sebagai data yang telah diproses untuk suatu tujuan tertentu. Tujuan tersebut adalah untuk menghasilkan sebuah keputusan.
                        3. George H. Bodnar (2000: 1) mendefinisikan informasi sebagai data yang diolah sehingga dapat dijadikan dasar untuk mengambil keputusan yang tepat.
                        4. Tata Sutabri (2002: 31) mendefinisikan informasi sebagai data yang telah diklasifikasikan atau diolah atau diinterpretasikan untuk digunakan dalam proses pengambilan keputusan.
                        5. Gordon B. Davis (1991: 28) mendefinisikan informasi sebagai data yang telah diolah menjadi sebuah bentuk yang berarti bagi penerimanya dan bermanfaat bagi pengambilan keputusan saat ini atau mendatang.
                    """.trimIndent()

                    val systemPrompt = if (base64Image != null) {
                        """
                        Jawab soal pilihan ganda SECARA INSTAN & TO THE POINT tanpa salam atau pembuka:
                        JAWABAN: [Huruf Opsi]. [Isi Opsi Paling Benar]
                        ALASAN: [Maksimal 10 kata]
                        Jika tidak ada soal terlihat, balas: Soal tidak ditemukan.

                        $referenceMaterial
                        """.trimIndent()
                    } else {
                        """
                        Jawab langsung inti jawabannya saja (TO THE POINT), tanpa salam, tanpa pembuka, tanpa basa-basi, dan tanpa penjelasan panjang:
                        - Jika soal pilihan ganda, tulis langsung: [Huruf Opsi]. [Isi Opsi Benar]
                        - Jika soal isian, definisi ahli, hitungan, atau pertanyaan singkat, tulis langsung jawaban intinya sesuai referensi materi jika tersedia.

                        $referenceMaterial
                        """.trimIndent()
                    }

                    val userParts = JSONArray().apply {
                        if (base64Image != null) {
                            put(JSONObject().apply {
                                put("inline_data", JSONObject().apply {
                                    put("mime_type", "image/jpeg")
                                    put("data", base64Image)
                                })
                            })
                        }
                        put(JSONObject().put("text", textPrompt))
                    }

                    val genConfig = JSONObject().apply {
                        put("temperature", 0.0)
                        put("maxOutputTokens", 250)
                        if (disableThinking) {
                            put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                        }
                    }

                    val payload = JSONObject().apply {
                        put("system_instruction", JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().put("text", systemPrompt))
                            })
                        })
                        put("contents", JSONArray().apply {
                            put(JSONObject().apply {
                                put("parts", userParts)
                            })
                        })
                        put("generationConfig", genConfig)
                    }

                    OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                        writer.write(payload.toString())
                        writer.flush()
                    }

                    val responseCode = conn.responseCode
                    if (responseCode in 200..299) {
                        val responseStr = conn.inputStream.bufferedReader().use { it.readText() }
                        val text = JSONObject(responseStr)
                            .optJSONArray("candidates")
                            ?.optJSONObject(0)
                            ?.optJSONObject("content")
                            ?.optJSONArray("parts")
                            ?.optJSONObject(0)
                            ?.optString("text")
                            ?.trim()

                        if (!text.isNullOrEmpty()) return text
                        break
                    } else {
                        val errStr = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                        val errMsg = try {
                            JSONObject(errStr).optJSONObject("error")?.optString("message")
                        } catch (_: Exception) {
                            null
                        }

                        if (responseCode == 400 && disableThinking) {
                            disableThinking = false
                            continue
                        }

                        lastError = "Error ($responseCode): ${errMsg ?: "Server sibuk"}"
                        if (responseCode == 503 || responseCode == 429 || responseCode == 404) {
                            Thread.sleep(600)
                            break
                        }
                        return lastError
                    }
                } catch (e: Exception) {
                    lastError = "Gagal terhubung: ${e.localizedMessage ?: "Periksa internet"}"
                    break
                }
            }
        }

        return lastError
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        if (::hudView.isInitialized) {
            try {
                windowManager.removeView(hudView)
            } catch (_: Exception) {
            }
        }
        ioExecutor.shutdownNow()
    }

    companion object {
        @Volatile
        var instance: HudAccessibilityService? = null
    }
}
