package com.example.hoarder.ui.managers

import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.hoarder.R
import com.example.hoarder.data.storage.app.Prefs

class PowerSettingsManager(
    private val a: AppCompatActivity,
    private val p: Prefs,
    private val onModeChanged: (Int) -> Unit
) {

    private lateinit var powerSavingSubtitle: TextView
    private lateinit var powerModeRadioGroup: RadioGroup

    fun setup() {
        powerSavingSubtitle = a.findViewById(R.id.powerSavingSubtitle)
        powerModeRadioGroup = a.findViewById(R.id.powerModeRadioGroup)

        updatePowerSavingUI()

        powerModeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val newMode = when (checkedId) {
                R.id.radioOptimized -> Prefs.POWER_MODE_OPTIMIZED
                R.id.radioPassive -> Prefs.POWER_MODE_PASSIVE
                else -> Prefs.POWER_MODE_CONTINUOUS
            }
            p.setPowerMode(newMode)
            updatePowerSavingUI()
            onModeChanged(newMode)
        }
    }

    private fun updatePowerSavingUI() {
        val currentMode = p.getPowerMode()
        val (subtitle, checkedRadioId) = when (currentMode) {
            Prefs.POWER_MODE_OPTIMIZED -> "Optimized" to R.id.radioOptimized
            Prefs.POWER_MODE_PASSIVE -> "Passive" to R.id.radioPassive
            else -> "Continuous" to R.id.radioContinuous
        }
        powerSavingSubtitle.text = subtitle
        if (powerModeRadioGroup.checkedRadioButtonId != checkedRadioId) {
            powerModeRadioGroup.check(checkedRadioId)
        }
    }
}