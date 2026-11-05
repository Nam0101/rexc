package dev.namnv.rexc.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dockStore by preferencesDataStore(name = "dock")

private const val MAX_RECENTS = 10
private const val SEPARATOR = '\n'

/** The dock's keys and the palette's recent actions, in the `dock` DataStore file. */
@Singleton
class DockPrefs @Inject constructor(@ApplicationContext private val context: Context) {
  val layout: Flow<DockLayout> = context.dockStore.data.map { DockLayout.decode(it[LAYOUT]) ?: DockLayout.Default }

  /** Action ids, most recent first. */
  val recents: Flow<List<String>> = context.dockStore.data.map {
    it[RECENTS].orEmpty().split(SEPARATOR).filter(String::isNotEmpty)
  }

  suspend fun saveLayout(layout: DockLayout) {
    context.dockStore.edit { it[LAYOUT] = layout.encode() }
  }

  suspend fun resetLayout() {
    context.dockStore.edit { it.remove(LAYOUT) }
  }

  suspend fun pushRecent(id: String) {
    context.dockStore.edit { prefs ->
      val current = prefs[RECENTS].orEmpty().split(SEPARATOR).filter(String::isNotEmpty)
      prefs[RECENTS] = current.pushed(id).joinToString(SEPARATOR.toString())
    }
  }

  private companion object {
    val LAYOUT = stringPreferencesKey("layout")
    val RECENTS = stringPreferencesKey("recents")
  }
}

/** [id] first, without a duplicate, keeping the [MAX_RECENTS] newest. */
fun List<String>.pushed(id: String): List<String> = (listOf(id) + (this - id)).take(MAX_RECENTS)
