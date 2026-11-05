package dev.namnv.rexc.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.namnv.rexc.BuildConfig
import dev.namnv.rexc.alerts.AlertService
import dev.namnv.rexc.alerts.BatteryOptimization
import dev.namnv.rexc.alerts.Notifications
import dev.namnv.rexc.core.dispatcher.AppCoroutineDispatchers
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import uniffi.rexc_ffi.Event
import uniffi.rexc_ffi.Host
import uniffi.rexc_ffi.HostRecord
import uniffi.rexc_ffi.InboxItem
import uniffi.rexc_ffi.Rexc
import uniffi.rexc_ffi.RexcException
import uniffi.rexc_ffi.RexcListener

/**
 * The one door to the Rust engine. The engine owns protocol, reconnects,
 * terminal emulation and ownership; this class turns its callbacks into flows
 * and runs its blocking calls off the main thread.
 */
@Singleton
class EngineRepository @Inject constructor(
  @ApplicationContext private val context: Context,
  private val dispatchers: AppCoroutineDispatchers,
) {
  private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)

  private val _hosts = MutableStateFlow<ImmutableList<Host>>(persistentListOf())
  val hosts: StateFlow<ImmutableList<Host>> = _hosts.asStateFlow()

  private val _inbox = MutableStateFlow<ImmutableList<InboxItem>>(persistentListOf())
  val inbox: StateFlow<ImmutableList<InboxItem>> = _inbox.asStateFlow()

  /** Every engine event. Frame events are pull-based, so dropping an old one loses nothing. */
  private val _events =
    MutableSharedFlow<Event>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  val events: SharedFlow<Event> = _events.asSharedFlow()

  private val _needsBatteryExemption = MutableStateFlow(false)

  /** Some host wants background alerts but Android may still freeze the app: worth asking for the exemption. */
  val needsBatteryExemption: StateFlow<Boolean> = _needsBatteryExemption.asStateFlow()

  @Volatile
  var inForeground: Boolean = false

  /** Host ids [AlertService] was last told about, so status churn does not restart it. */
  private var watchedIds: Set<String> = emptySet()

  private val engine: Rexc = Rexc(
    context.filesDir.absolutePath,
    BuildConfig.VERSION_NAME,
    "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
    object : RexcListener {
      override fun onEvent(event: Event) = dispatch(event)
    },
  )

  init {
    refresh()
    NetworkWatcher(context, ::onNetworkChanged).start()
  }

  /** A new default network: skip the reconnect backoff, and check that connections that look open still are. */
  private fun onNetworkChanged() {
    if (inForeground) connectAll() else connectWatched()
    engine.networkChanged()
  }

  private fun dispatch(event: Event) {
    when (event) {
      is Event.Hosts, is Event.Host -> refresh()
      is Event.Alert -> if (!inForeground) Notifications.show(context, event.alert)
      is Event.Clipboard -> scope.launch(dispatchers.main) {
        context.getSystemService(
          ClipboardManager::class.java,
        ).setPrimaryClip(ClipData.newPlainText("Terminal", event.text))
      }
      is Event.Terminal, is Event.TerminalState, is Event.OpenUrl -> Unit
    }
    _events.tryEmit(event)
  }

  private fun refresh() {
    scope.launch {
      _hosts.value = engine.allHosts().toImmutableList()
      _inbox.value = engine.inbox().toImmutableList()
      syncAlertService()
    }
  }

  /** Hosts that keep their connection, and so their alerts, while the app is away. */
  fun watchedHosts(): List<HostRecord> = engine.hosts().filter { it.backgroundAlerts }

  /** Keeps [AlertService] running exactly while some host wants alerts in the background. */
  fun syncAlertService(force: Boolean = false) {
    val watched = watchedHosts()
    _needsBatteryExemption.value = watched.isNotEmpty() && !BatteryOptimization.isIgnoring(context)
    val ids = watched.mapTo(HashSet()) { it.hostId }
    if (force || ids != watchedIds) {
      watchedIds = ids
      AlertService.sync(context, watched)
    }
  }

  /** The process was restarted in the background: the new engine has to bring the watched hosts up itself. */
  fun connectWatched() {
    watchedHosts().forEach { h -> engineCall("connect ${h.name}") { connect(h.hostId) } }
  }

  /** The watch notice's Stop: switch background alerts off for every watched host and drop them. */
  fun stopWatching() {
    watchedHosts().forEach { h ->
      engineCall("stop watching ${h.name}") {
        updateHost(h.hostId, h.name, h.address, false)
        disconnect(h.hostId)
      }
    }
  }

  private inline fun engineCall(what: String, block: Rexc.() -> Unit) {
    try {
      engine.block()
    } catch (e: RexcException) {
      Timber.w(e, what)
    }
  }

  fun host(hostId: String): Flow<Host?> = hosts.map { list ->
    list.firstOrNull { it.hostId == hostId }
  }.distinctUntilChanged()

  /** Frame and state events for one terminal. */
  fun terminalEvents(terminalId: ULong): Flow<Event> = events.filter {
    (it is Event.Terminal && it.terminalId == terminalId) || (it is Event.TerminalState && it.terminalId == terminalId)
  }

  /** A blocking engine call (most are a server round trip), off the main thread. */
  suspend fun <T> call(block: Rexc.() -> T): T = withContext(dispatchers.io) { engine.block() }

  fun connectAll() = engine.connectAll()

  fun setBackground(background: Boolean) {
    inForeground = !background
    engine.setBackground(background)
  }

  fun terminal(terminalId: ULong): TerminalController = TerminalController(engine, terminalId)

  fun log(message: String, error: Throwable? = null) {
    if (error != null) Timber.w(error, message) else Timber.d(message)
  }
}
