package com.example.floatinghud

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var etApiKey: EditText
    private lateinit var spinnerModel: Spinner
    private lateinit var seekHudAlpha: SeekBar
    private lateinit var tvHudAlphaValue: TextView
    private lateinit var seekAlpha: SeekBar
    private lateinit var tvAlphaValue: TextView
    private lateinit var tvServiceStatus: TextView

    private val models = listOf(
        "gemini-3.5-flash-lite",
        "gemini-3.8-flash",
        "gemini-2.5-flash"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etApiKey = findViewById(R.id.etApiKey)
        spinnerModel = findViewById(R.id.spinnerModel)
        seekHudAlpha = findViewById(R.id.seekHudAlpha)
        tvHudAlphaValue = findViewById(R.id.tvHudAlphaValue)
        seekAlpha = findViewById(R.id.seekAlpha)
        tvAlphaValue = findViewById(R.id.tvAlphaValue)
        tvServiceStatus = findViewById(R.id.tvServiceStatus)
        val btnStartHud = findViewById<Button>(R.id.btnStartHud)
        val btnAppInfo = findViewById<Button>(R.id.btnAppInfo)
        val btnStopHud = findViewById<Button>(R.id.btnStopHud)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        etApiKey.setText(prefs.getString(KEY_API_KEY, ""))

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, models).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerModel.adapter = adapter
        val savedModel = prefs.getString(KEY_MODEL, models[0])
        val modelIndex = models.indexOf(savedModel).takeIf { it >= 0 } ?: 0
        spinnerModel.setSelection(modelIndex)

        val savedHudAlpha = prefs.getInt(KEY_HUD_ALPHA, 85)
        seekHudAlpha.progress = savedHudAlpha
        tvHudAlphaValue.text = "$savedHudAlpha%"

        seekHudAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val clamped = progress.coerceAtLeast(15)
                tvHudAlphaValue.text = "$clamped%"
                prefs.edit().putInt(KEY_HUD_ALPHA, clamped).apply()
                HudAccessibilityService.instance?.refreshSettings()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val savedAlpha = prefs.getInt(KEY_SHOW_ALPHA, 20)
        seekAlpha.progress = savedAlpha
        tvAlphaValue.text = "$savedAlpha%"

        seekAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val clamped = progress.coerceAtLeast(5)
                tvAlphaValue.text = "$clamped%"
                prefs.edit().putInt(KEY_SHOW_ALPHA, clamped).apply()
                HudAccessibilityService.instance?.refreshSettings()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        updateServiceStatus()

        btnStartHud.setOnClickListener {
            val apiKey = etApiKey.text.toString().trim()
            if (apiKey.isEmpty()) {
                Toast.makeText(this, "Masukkan Gemini API Key dulu!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val hudAlpha = seekHudAlpha.progress.coerceAtLeast(15)
            val alpha = seekAlpha.progress.coerceAtLeast(5)
            prefs.edit()
                .putString(KEY_API_KEY, apiKey)
                .putString(KEY_MODEL, models[spinnerModel.selectedItemPosition])
                .putInt(KEY_HUD_ALPHA, hudAlpha)
                .putInt(KEY_SHOW_ALPHA, alpha)
                .apply()

            val runningService = HudAccessibilityService.instance
            if (runningService != null) {
                runningService.refreshSettings()
                runningService.setHudVisible(true)
                Toast.makeText(this, "Floating HUD Aktif!", Toast.LENGTH_SHORT).show()
                moveTaskToBack(true)
            } else {
                Toast.makeText(
                    this,
                    "Aktifkan 'AI Floating HUD' di menu Aplikasi Terinstal (Installed apps)",
                    Toast.LENGTH_LONG
                ).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        btnAppInfo.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        btnStopHud.setOnClickListener {
            val runningService = HudAccessibilityService.instance
            if (runningService != null) {
                runningService.setHudVisible(false)
                Toast.makeText(this, "HUD Disembunyikan", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "HUD belum aktif", Toast.LENGTH_SHORT).show()
            }
            updateServiceStatus()
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }

    private fun updateServiceStatus() {
        val isActive = HudAccessibilityService.instance != null
        tvServiceStatus.text = if (isActive) {
            "Status Aksesibilitas: AKTIF (Tanpa tanda rekam layar)"
        } else {
            "Status Aksesibilitas: BELUM AKTIF\nKlik MULAI FLOATING HUD lalu aktifkan AI Floating HUD."
        }
    }

    companion object {
        const val PREFS_NAME = "ai_hud_prefs"
        const val KEY_API_KEY = "gemini_api_key"
        const val KEY_MODEL = "gemini_model"
        const val KEY_HUD_ALPHA = "hud_panel_alpha"
        const val KEY_SHOW_ALPHA = "show_button_alpha"
    }
}
