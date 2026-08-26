package net.klimakontrol.data.settings

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Lingua scelta dall'utente: "system" (default, segue il telefono), "it" o "en".
 * Salvata in SharedPreferences; applicata avvolgendo il Context dell'Activity
 * (vedi MainActivity.attachBaseContext) — nessuna dipendenza AppCompat.
 */
class LocaleStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun language(): String = prefs.getString(KEY, SYSTEM) ?: SYSTEM

    fun setLanguage(lang: String) {
        prefs.edit().putString(KEY, lang).apply()
    }

    companion object {
        const val SYSTEM = "system"
        private const val PREFS = "klima_locale"
        private const val KEY = "language"

        /** Avvolge il Context con la lingua scelta (o lo restituisce invariato se "system"). */
        fun wrap(base: Context): Context {
            val lang = LocaleStore(base).language()
            if (lang == SYSTEM) return base
            val locale = Locale(lang)
            Locale.setDefault(locale)
            val config = Configuration(base.resources.configuration)
            config.setLocale(locale)
            return base.createConfigurationContext(config)
        }
    }
}
