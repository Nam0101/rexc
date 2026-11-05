package dev.namnv.rexc.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.namnv.rexc.core.dispatcher.AppCoroutineDispatchers
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking

/** The user's preferences, one Preferences DataStore file named `settings`. */
@Singleton
class SettingsRepository @Inject constructor(
  @ApplicationContext context: Context,
  dispatchers: AppCoroutineDispatchers,
) {
  private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
  private val store = PreferenceDataStoreFactory.create(scope = scope) { context.preferencesDataStoreFile("settings") }

  /** Read once up front (a tiny file) so the first frame already has the chosen theme. */
  val settings: StateFlow<Settings> = store.data.map { it.toSettings() }.let { flow ->
    val first = runBlocking(dispatchers.io) { flow.first() }
    flow.stateIn(scope, SharingStarted.Eagerly, first)
  }

  suspend fun update(transform: (Settings) -> Settings) {
    store.edit { prefs -> prefs.write(transform(prefs.toSettings())) }
  }
}
