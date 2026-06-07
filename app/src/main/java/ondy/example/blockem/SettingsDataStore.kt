package ondy.example.blockem

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

val Context.dataStore by preferencesDataStore(name = "blockem_settings")

class SettingsDataStore(private val context: Context) {

    companion object {
        val GLOBAL_ENABLED = booleanPreferencesKey("global_enabled")
        val IG_ENABLED = booleanPreferencesKey("ig_enabled")
        val TT_ENABLED = booleanPreferencesKey("tt_enabled")
        val YT_ENABLED = booleanPreferencesKey("yt_enabled")
        val DAILY_SCROLLS = intPreferencesKey("daily_scrolls")
        val LAST_DATE = stringPreferencesKey("last_date")
    }

    val globalEnabledFlow: Flow<Boolean> = context.dataStore.data.map { it[GLOBAL_ENABLED] ?: true }
    val igEnabledFlow: Flow<Boolean> = context.dataStore.data.map { it[IG_ENABLED] ?: true }
    val ttEnabledFlow: Flow<Boolean> = context.dataStore.data.map { it[TT_ENABLED] ?: true }
    val ytEnabledFlow: Flow<Boolean> = context.dataStore.data.map { it[YT_ENABLED] ?: true }
    val dailyScrollsFlow: Flow<Int> = context.dataStore.data.map { it[DAILY_SCROLLS] ?: 0 }

    suspend fun setSwitch(key: androidx.datastore.preferences.core.Preferences.Key<Boolean>, value: Boolean) {
        context.dataStore.edit { prefs -> prefs[key] = value }
    }

    // Increments the count, but resets to 0 first if it's a new day!
    suspend fun incrementScroll(): Int {
        var newCount = 0
        val today = LocalDate.now().toString()

        context.dataStore.edit { prefs ->
            val lastDate = prefs[LAST_DATE] ?: ""
            var currentCount = prefs[DAILY_SCROLLS] ?: 0

            if (lastDate != today) {
                currentCount = 0
                prefs[LAST_DATE] = today
            }

            newCount = currentCount + 1
            prefs[DAILY_SCROLLS] = newCount
        }
        return newCount

    }
}