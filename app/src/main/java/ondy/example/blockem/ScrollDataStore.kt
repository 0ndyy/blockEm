package ondy.example.blockem

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

// initialize singleton datastore
val Context.dataStore by preferencesDataStore(name = "scroll_prefs")

class ScrollDataStore(private val context: Context) {

    private val scrollCountKey = intPreferencesKey("scroll_count")
    private val maxScrollsKey = intPreferencesKey("max_scrolls")
    private val lastDateKey = stringPreferencesKey("last_scroll_date")

    val scrollCountFlow: Flow<Int> = context.dataStore.data.map { it[scrollCountKey] ?: 0 }
    val maxScrollsFlow: Flow<Int> = context.dataStore.data.map { it[maxScrollsKey] ?: 50 } // def limit

    suspend fun incrementScroll() {
        val today = LocalDate.now().toString()

        context.dataStore.edit { prefs ->
            val lastDate = prefs[lastDateKey] ?: ""
            var currentCount = prefs[scrollCountKey] ?: 0

            // midnight reset logic
            if (lastDate != today) {
                currentCount = 0
                prefs[lastDateKey] = today
            }

            prefs[scrollCountKey] = currentCount + 1
        }
    }

    suspend fun setMaxScrolls(max: Int) {
        context.dataStore.edit { prefs ->
            prefs[maxScrollsKey] = max
        }
    }
}