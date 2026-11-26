package io.github.pahpuhbinny.multiview.ui

import android.content.SharedPreferences
import android.os.Bundle
import android.text.format.DateUtils
import android.view.MenuItem
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import io.github.pahpuhbinny.multiview.BuildConfig
import io.github.pahpuhbinny.multiview.Prefs
import io.github.pahpuhbinny.multiview.browser.UrlTools
import io.github.pahpuhbinny.multiview.security.ContentBlocker
import io.github.pahpuhbinny.multiview.security.FilterLists

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(android.R.id.content, Fragment()).commit()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    class Fragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(io.github.pahpuhbinny.multiview.R.xml.preferences, rootKey)
            val ctx = requireContext()

            findPreference<ListPreference>("search_engine")?.apply {
                entries = UrlTools.engines.map { it.label }.toTypedArray()
                entryValues = UrlTools.engines.map { it.id }.toTypedArray()
            }

            val filters = findPreference<PreferenceCategory>("filters")!!
            FilterLists.all.forEachIndexed { i, list ->
                val p = SwitchPreferenceCompat(ctx).apply {
                    key = FilterLists.prefKey(list)
                    title = list.name
                    summary = list.url.substringAfter("://").substringBefore('/')
                    setDefaultValue(list.defaultOn)
                    isIconSpaceReserved = false
                    order = 10 + i
                }
                filters.addPreference(p)
            }
            val update = findPreference<Preference>("update_filters")!!
            update.order = 100
            fun refreshSummary() {
                val at = Prefs.filtersUpdatedAt
                update.summary = (if (at == 0L) "Not downloaded yet" else "Updated " +
                    DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)) +
                    " · %,d domains blocked".format(ContentBlocker.domainCount)
            }
            refreshSummary()
            update.setOnPreferenceClickListener {
                update.summary = "Downloading…"
                FilterLists.update(ctx) { ok, failed ->
                    if (isAdded) {
                        refreshSummary()
                        if (failed > 0) ctx.toast("$ok lists updated, $failed couldn't be downloaded")
                        else if (ok > 0) ctx.toast("$ok lists updated")
                    }
                }
                true
            }

            findPreference<Preference>("clear_now")?.setOnPreferenceClickListener {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
                ctx.toast("Cookies and site data cleared")
                true
            }
            findPreference<Preference>("about")?.summary =
                "Version ${BuildConfig.VERSION_NAME}. A private browser built for watching videos."
        }

        private val listChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            val ctx = context ?: return@OnSharedPreferenceChangeListener
            val list = FilterLists.all.firstOrNull { FilterLists.prefKey(it) == key } ?: return@OnSharedPreferenceChangeListener
            if (FilterLists.isEnabled(list) && !FilterLists.fileFor(ctx, list).isFile) {
                FilterLists.update(ctx, null)
            } else {
                ContentBlocker.loadAsync(ctx)
            }
        }

        override fun onResume() {
            super.onResume()
            Prefs.prefs.registerOnSharedPreferenceChangeListener(listChanged)
        }

        override fun onPause() {
            Prefs.prefs.unregisterOnSharedPreferenceChangeListener(listChanged)
            super.onPause()
        }
    }
}
