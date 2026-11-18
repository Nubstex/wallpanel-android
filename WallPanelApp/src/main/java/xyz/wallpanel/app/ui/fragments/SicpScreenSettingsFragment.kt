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
import android.content.SharedPreferences
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.navigation.Navigation
import androidx.preference.Preference
import androidx.preference.SwitchPreference
import dagger.android.support.AndroidSupportInjection
import xyz.wallpanel.app.R
import xyz.wallpanel.app.persistence.Configuration
import xyz.wallpanel.app.ui.activities.SettingsActivity
import xyz.wallpanel.app.utils.SicpClient

class SicpScreenSettingsFragment : BaseSettingsFragment() {

    private var autoPowerPreference: SwitchPreference? = null

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
            (activity as SettingsActivity).supportActionBar!!.title = getString(R.string.title_sicp_screen_settings)
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
        addPreferencesFromResource(R.xml.pref_sicp_screen)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        autoPowerPreference = findPreference<SwitchPreference>(Configuration.PREF_SICP_AUTO_SCREEN_POWER) as SwitchPreference
        autoPowerPreference?.isChecked = configuration.sicpAutoScreenPower

        findPreference<Preference>("button_sicp_screen_on")?.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                SicpClient.screenOn()
                Toast.makeText(requireContext(), R.string.toast_sicp_screen_on_sent, Toast.LENGTH_SHORT).show()
                true
            }
        findPreference<Preference>("button_sicp_screen_off")?.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                SicpClient.screenOff()
                Toast.makeText(requireContext(), R.string.toast_sicp_screen_off_sent, Toast.LENGTH_SHORT).show()
                true
            }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String) {
        if (key == Configuration.PREF_SICP_AUTO_SCREEN_POWER) {
            configuration.sicpAutoScreenPower = autoPowerPreference?.isChecked ?: false
        }
    }
}
