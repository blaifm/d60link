package app.d60link

import android.content.Context

/** Qué bajar cuando una foto tiene RAW y JPG. */
enum class PairMode { BOTH, JPG, RAW }

/** Cómo mostrar la fecha en el visor. */
enum class DateStyle { OFF, BAR, STAMP }

data class Settings(
    val pairMode: PairMode = PairMode.BOTH,
    val dateStyle: DateStyle = DateStyle.BAR,
    val showTime: Boolean = true,
    val offerDelete: Boolean = false,
) {
    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("pairMode", pairMode.name)
            .putString("dateStyle", dateStyle.name)
            .putBoolean("showTime", showTime)
            .putBoolean("offerDelete", offerDelete)
            .apply()
    }

    companion object {
        private const val PREFS = "settings"

        fun load(context: Context): Settings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val d = Settings()
            return Settings(
                pairMode = runCatching { PairMode.valueOf(p.getString("pairMode", null)!!) }.getOrDefault(d.pairMode),
                dateStyle = runCatching { DateStyle.valueOf(p.getString("dateStyle", null)!!) }.getOrDefault(d.dateStyle),
                showTime = p.getBoolean("showTime", d.showTime),
                offerDelete = p.getBoolean("offerDelete", d.offerDelete),
            )
        }
    }
}
