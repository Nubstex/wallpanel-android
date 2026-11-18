/*
 * Copyright (c) 2022 WallPanel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package xyz.wallpanel.app.ui.fragments

import android.content.Context
import android.content.Context.WIFI_SERVICE
import android.content.SharedPreferences
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.format.Formatter
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.navigation.Navigation
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreference
import dagger.android.support.AndroidSupportInjection
import xyz.wallpanel.app.R
import xyz.wallpanel.app.network.WledEmulationServer
import xyz.wallpanel.app.persistence.Configuration
import xyz.wallpanel.app.ui.activities.SettingsActivity
import xyz.wallpanel.app.utils.LedController

class SicpLedSettingsFragment : BaseSettingsFragment() {

    private var wledInfoPreference: Preference? = null
    private var wledSimPreference: SwitchPreference? = null
    private var ledModePreference: ListPreference? = null
    private var ledStaticOnPreference: SwitchPreference? = null
    private var ledStaticColorPreference: EditTextPreference? = null
    private var ledActiveColorPreference: EditTextPreference? = null
    private var ledActiveLoopPreference: SwitchPreference? = null
    private var ledInactiveColorPreference: EditTextPreference? = null
    private var ledInactiveLoopPreference: SwitchPreference? = null

    override fun onAttach(context: Context) {
        AndroidSupportInjection.inject(this)
        super.onAttach(context)
        setHasOptionsMenu(true)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        if ((activity as SettingsActivity).supportActionBar != null) {
            (activity as SettingsActivity).supportActionBar!!.setDisplayHomeAsUpEnabled(true)
            (activity as SettingsActivity).supportActionBar!!.setDisplayShowHomeEnabled(true)
            (activity as SettingsActivity).supportActionBar!!.title = getString(R.string.title_sicp_led_settings)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            view?.let { Navigation.findNavController(it).navigate(R.id.sicp_action) }
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_sicp_led)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        wledInfoPreference = findPreference("pref_wled_info")
        wledSimPreference = findPreference<SwitchPreference>(Configuration.PREF_WLED_SIMULATION_ENABLED) as SwitchPreference
        ledModePreference = findPreference<ListPreference>(Configuration.PREF_LED_MODE) as ListPreference
        ledStaticOnPreference = findPreference<SwitchPreference>(Configuration.PREF_LED_STATIC_ON) as SwitchPreference
        ledStaticColorPreference = findPreference<EditTextPreference>(Configuration.PREF_LED_STATIC_COLOR) as EditTextPreference
        ledActiveColorPreference = findPreference<EditTextPreference>(Configuration.PREF_LED_ACTIVE_COLOR) as EditTextPreference
        ledActiveLoopPreference = findPreference<SwitchPreference>(Configuration.PREF_LED_ACTIVE_LOOP) as SwitchPreference
        ledInactiveColorPreference = findPreference<EditTextPreference>(Configuration.PREF_LED_INACTIVE_COLOR) as EditTextPreference
        ledInactiveLoopPreference = findPreference<SwitchPreference>(Configuration.PREF_LED_INACTIVE_LOOP) as SwitchPreference

        val wm = requireActivity().applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val ip = Formatter.formatIpAddress(wm.connectionInfo.ipAddress)
        wledInfoPreference?.summary = getString(R.string.summary_wled_info, ip)

        wledSimPreference?.isChecked = configuration.wledSimulationEnabled

        ledModePreference?.value = configuration.ledMode.toString()
        ledModePreference?.summary = ledModePreference?.entry

        ledStaticOnPreference?.isChecked = configuration.ledStaticOn
        ledStaticColorPreference?.text = colorToHex(configuration.ledStaticColor)
        ledStaticColorPreference?.summary = ledStaticColorPreference?.text

        ledActiveColorPreference?.text = colorToHex(configuration.ledActiveColor)
        ledActiveColorPreference?.summary = ledActiveColorPreference?.text
        ledActiveLoopPreference?.isChecked = configuration.ledActiveLoop

        ledInactiveColorPreference?.text = colorToHex(configuration.ledInactiveColor)
        ledInactiveColorPreference?.summary = ledInactiveColorPreference?.text
        ledInactiveLoopPreference?.isChecked = configuration.ledInactiveLoop

        updateVisibility()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String) {
        when (key) {
            Configuration.PREF_WLED_SIMULATION_ENABLED -> {
                val enabled = wledSimPreference?.isChecked ?: false
                configuration.wledSimulationEnabled = enabled
                if (enabled) WledEmulationServer.start() else WledEmulationServer.stop()
                LedController.refresh()
                updateVisibility()
            }
            Configuration.PREF_LED_MODE -> {
                val value = ledModePreference?.value?.toIntOrNull()
                if (value != null) {
                    configuration.ledMode = value
                    ledModePreference?.summary = ledModePreference?.entry
                    // Manual mode and WLED simulation are mutually exclusive: picking a mode
                    // here turns simulation off.
                    if (configuration.wledSimulationEnabled) {
                        configuration.wledSimulationEnabled = false
                        wledSimPreference?.isChecked = false
                        WledEmulationServer.stop()
                    }
                    LedController.refresh()
                    updateVisibility()
                }
            }
            Configuration.PREF_LED_STATIC_ON -> {
                configuration.ledStaticOn = ledStaticOnPreference?.isChecked ?: false
                LedController.refresh()
            }
            Configuration.PREF_LED_STATIC_COLOR -> applyColor(ledStaticColorPreference) { configuration.ledStaticColor = it }
            Configuration.PREF_LED_ACTIVE_COLOR -> applyColor(ledActiveColorPreference) { configuration.ledActiveColor = it }
            Configuration.PREF_LED_ACTIVE_LOOP -> {
                configuration.ledActiveLoop = ledActiveLoopPreference?.isChecked ?: false
                LedController.refresh()
            }
            Configuration.PREF_LED_INACTIVE_COLOR -> applyColor(ledInactiveColorPreference) { configuration.ledInactiveColor = it }
            Configuration.PREF_LED_INACTIVE_LOOP -> {
                configuration.ledInactiveLoop = ledInactiveLoopPreference?.isChecked ?: false
                LedController.refresh()
            }
        }
    }

    private fun applyColor(preference: EditTextPreference?, setter: (Int) -> Unit) {
        val text = preference?.text.orEmpty().trim()
        try {
            val hex = if (text.startsWith("#")) text else "#$text"
            val color = Color.parseColor(hex)
            setter(color)
            preference?.summary = hex
            LedController.refresh()
        } catch (e: IllegalArgumentException) {
            Toast.makeText(requireContext(), getString(R.string.toast_error_bad_color), Toast.LENGTH_SHORT).show()
        }
    }

    private fun colorToHex(color: Int): String {
        return String.format("#%06X", 0xFFFFFF and color)
    }

    private fun updateVisibility() {
        val wledOn = configuration.wledSimulationEnabled
        ledModePreference?.isEnabled = !wledOn

        val mode = configuration.ledMode
        findPreference<PreferenceCategory>("pref_led_static_category")?.isVisible =
            !wledOn && mode == Configuration.LED_MODE_STATIC
        findPreference<PreferenceCategory>("pref_led_active_category")?.isVisible =
            !wledOn && (mode == Configuration.LED_MODE_ACTIVE_ONLY || mode == Configuration.LED_MODE_TWO_STATE)
        findPreference<PreferenceCategory>("pref_led_inactive_category")?.isVisible =
            !wledOn && mode == Configuration.LED_MODE_TWO_STATE
    }
}
