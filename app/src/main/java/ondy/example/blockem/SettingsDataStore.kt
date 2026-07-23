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

        val IGNORE_IG_HOME = booleanPreferencesKey("ignore_ig_home")
        val IGNORE_DM_GLOBAL = booleanPreferencesKey("ignore_dm_global")
        val IGNORE_DM_IG = booleanPreferencesKey("ignore_dm_ig")
        val IGNORE_DM_TT = booleanPreferencesKey("ignore_dm_tt")

        val MAX_SCROLLS = intPreferencesKey("max_scrolls")
        val DAILY_SCROLLS = intPreferencesKey("daily_scrolls")
        val LAST_DATE = stringPreferencesKey("last_date")
        val SHOW_SCROLLS_LEFT = booleanPreferencesKey("show_scrolls_left")

        val HISTORY = stringPreferencesKey("history") //30 day history

        val SHOW_DEBUG_UI = booleanPreferencesKey("show_debug_ui")

    }

    val globalEnabledFlow: Flow<Boolean> = context.dataStore.data.map { it[GLOBAL_ENABLED] ?: true }
    val igEnabledFlow: Flow<Boolean> = context.dataStore.data.map { it[IG_ENABLED] ?: true }
    val ttEnabledFlow: Flow<Boolean> = context.dataStore.data.map { it[TT_ENABLED] ?: true }
    val ytEnabledFlow: Flow<Boolean> = context.dataStore.data.map { it[YT_ENABLED] ?: true }

    val ignoreIgHomeFlow: Flow<Boolean> = context.dataStore.data.map { it[IGNORE_IG_HOME] ?: false }
    val ignoreDmGlobalFlow: Flow<Boolean> = context.dataStore.data.map { it[IGNORE_DM_GLOBAL] ?: true }
    val ignoreDmIgFlow: Flow<Boolean> = context.dataStore.data.map { it[IGNORE_DM_IG] ?: false }
    val ignoreDmTtFlow: Flow<Boolean> = context.dataStore.data.map { it[IGNORE_DM_TT] ?: false }

    val maxScrollsFlow: Flow<Int> = context.dataStore.data.map { it[MAX_SCROLLS] ?: 50 }
    val showScrollsLeftFlow: Flow<Boolean> = context.dataStore.data.map { it[SHOW_SCROLLS_LEFT] ?: true }
    val showDebugUiFlow: Flow<Boolean> = context.dataStore.data.map { it[SHOW_DEBUG_UI] ?: false }

    val dailyScrollsFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        val today = LocalDate.now().toString()
        val lastDate = prefs[LAST_DATE]

        if (lastDate != null && lastDate != today) {
            0 //show 0 on new day
        } else {
            prefs[DAILY_SCROLLS] ?: 0
        }
    }

    val historyFlow: Flow<Map<String, Int>> = context.dataStore.data.map { prefs ->
        val historyStr = prefs[HISTORY] ?: ""
        historyStr.split(",")
            .filter { it.isNotEmpty() && it.contains(":") }
            .associate {
                val parts = it.split(":")
                parts[0] to parts[1].toInt()
            }
    }

    suspend fun setSwitch(key: androidx.datastore.preferences.core.Preferences.Key<Boolean>, value: Boolean) {
        context.dataStore.edit { prefs -> prefs[key] = value }
    }

    suspend fun setMaxScrolls(max: Int) {
        context.dataStore.edit { prefs -> prefs[MAX_SCROLLS] = max }
    }

    suspend fun incrementScroll(): Int {
        var newCount = 0
        val today = LocalDate.now().toString()

        context.dataStore.edit { prefs ->
            val lastDate = prefs[LAST_DATE]
            var currentCount = prefs[DAILY_SCROLLS] ?: 0
            val historyStr = prefs[HISTORY] ?: ""

            // parse history into omap
            val historyMap = historyStr.split(",")
                .filter { it.isNotEmpty() && it.contains(":") }
                .associate {
                    val parts = it.split(":")
                    parts[0] to parts[1].toInt()
                }.toMutableMap()

            // trigger date reset if new day
            if (lastDate != null && lastDate != today) {
                historyMap[lastDate] = currentCount
                currentCount = 0
            }

            newCount = currentCount + 1
            prefs[DAILY_SCROLLS] = newCount
            prefs[LAST_DATE] = today
            historyMap[today] = newCount

            val recentHistoryEntries = historyMap.entries
                .sortedByDescending { it.key }
                .take(30)

            prefs[HISTORY] = recentHistoryEntries.joinToString(",") { "${it.key}:${it.value}" }
        }
        return newCount
    }

    //debug functions

    suspend fun injectMockData() {
        context.dataStore.edit { prefs ->
            val mockData = (0..30).map { i ->
                val date = LocalDate.now().minusDays(i.toLong()).toString()
                // Keep today's actual count, randomize the past days between 15 and 80
                val count = if (i == 0) prefs[DAILY_SCROLLS] ?: 0 else (15..80).random()
                date to count
            }
            prefs[HISTORY] = mockData.joinToString(",") { "${it.first}:${it.second}" }
        }
    }

    suspend fun clearTodaysData() {
        context.dataStore.edit { prefs ->
            val today = LocalDate.now().toString()

            prefs[DAILY_SCROLLS] = 0

            val historyStr = prefs[HISTORY] ?: ""
            val historyMap = historyStr.split(",")
                .filter { it.isNotEmpty() && it.contains(":") }
                .associate {
                    val parts = it.split(":")
                    parts[0] to parts[1].toInt()
                }.toMutableMap()

            historyMap[today] = 0

            val recentHistoryEntries = historyMap.entries
                .sortedByDescending { it.key }
                .take(30)

            prefs[HISTORY] = recentHistoryEntries.joinToString(",") { "${it.key}:${it.value}" }
        }
    }
}