package me.ri3d.adb.apps

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.ri3d.adb.App
import me.ri3d.adb.Op
import me.ri3d.adb.Ops
import me.ri3d.adb.adb.InstalledVersion
import me.ri3d.adb.car.CarInfo
import me.ri3d.adb.car.CarState
import me.ri3d.adb.car.InstallPhase
import me.ri3d.adb.car.NoInternet
import me.ri3d.adb.car.cleanTempApks
import me.ri3d.adb.car.installApk
import me.ri3d.adb.car.installedVersions
import me.ri3d.adb.car.launch
import me.ri3d.adb.car.userPackages
import java.io.File

/** One app row on the Car screen, combining configuration, the car's state and the release feed. */
data class AppRow(
    val app: ManagedApp,
    /** Null when not installed, or (with [installedKnown] false) when the car hasn't been asked yet. */
    val installed: InstalledVersion?,
    val installedKnown: Boolean,
    val release: Release?,
    val checkError: String?,
    /** Why [release] doesn't fit the car. */
    val incompatible: String?,
    val op: Op?,
    /** [installed] comes from the connected car, not from the last visit. */
    val live: Boolean = true,
    /** [release]'s APK is downloaded and can be installed without internet. */
    val ready: Boolean = false,
) {
    val updateAvailable get() = installed != null && release != null && incompatible == null && release.versionCode > installed.versionCode
    val installable get() = installedKnown && installed == null && release != null && incompatible == null
}

sealed interface CheckState {
    data class Idle(val lastOk: Long) : CheckState
    data object Checking : CheckState
    data class Failed(val reason: String, val lastOk: Long) : CheckState
}

sealed interface Picked {
    data object None : Picked
    data object Reading : Picked
    data class Ready(val apk: LocalApk) : Picked
    data class Invalid(val reason: String) : Picked
}

sealed interface AddResult {
    data class Done(val name: String, val detail: String) : AddResult
    data class Failed(val reason: String) : AddResult
}

sealed interface CarPackages {
    data object Loading : CarPackages
    data class Loaded(val packages: List<String>) : CarPackages
    data class Failed(val reason: String) : CarPackages
}

class AppsModel(private val app: App) {
    private val car get() = app.car
    private val settings get() = app.settings

    private val managed = MutableStateFlow(load())
    private val installed = MutableStateFlow<Map<String, InstalledVersion>?>(null)
    private val snapshot = MutableStateFlow(settings.carSnapshot)

    /** The car as last seen; null until the first connection. */
    val lastCar: StateFlow<CarSnapshot?> = snapshot

    /** Release APKs downloaded ahead of time. */
    val cache = ApkCache(File(app.filesDir, "apks"))
    private val releases = MutableStateFlow(settings.releases)
    private val errors = MutableStateFlow<Map<String, String>>(emptyMap())
    private val busy = MutableStateFlow<Map<String, Op>>(emptyMap())
    private val _check = MutableStateFlow<CheckState>(CheckState.Idle(settings.lastCheckOk))
    val check: StateFlow<CheckState> = _check

    /** "pkg@versionCode" the user answered "Not now" for, until the next manual check. */
    private val dismissed = MutableStateFlow<Set<String>>(emptySet())

    val apps: StateFlow<List<ManagedApp>> = managed

    // Connected: what the car reports. Otherwise: what it had at the last visit.
    private val onCar = combine(installed, snapshot) { live, snap -> live?.let { it to true } ?: snap?.let { it.installed to false } }
    private val extras = combine(busy, cache.versions, snapshot) { ops, cached, snap -> Triple(ops, cached, snap) }

    val rows: StateFlow<List<AppRow>> = combine(managed, onCar, releases, errors, extras) { list, oc, rel, err, (ops, cached, snap) ->
        val t = car.info?.traits ?: snap?.traits
        list.map { a ->
            val r = rel[a.packageName]?.takeIf { a.source != null }
            AppRow(
                app = a,
                installed = oc?.first?.get(a.packageName),
                installedKnown = oc != null,
                release = r,
                checkError = err[a.packageName],
                incompatible = if (r != null && t != null) incompatibility(r.minSdk, r.abis, t.sdk, t.release, t.abis) else null,
                op = ops[a.packageName],
                live = oc?.second == true,
                ready = r != null && cached[a.packageName] == r.versionCode,
            )
        }
    }.stateIn(app.scope, SharingStarted.Eagerly, emptyList())

    /** Updates to offer in the update card. */
    val pending: StateFlow<List<AppRow>> = combine(rows, dismissed) { r, d ->
        r.filter { it.updateAvailable && it.op == null && "${it.app.packageName}@${it.release!!.versionCode}" !in d }
    }.stateIn(app.scope, SharingStarted.Eagerly, emptyList())

    private var checkJob: Job? = null

    init {
        var lastSession = -1L
        app.scope.launch {
            car.state.collect { s ->
                if (s is CarState.Connected && s.info.session != lastSession) {
                    lastSession = s.info.session
                    launch { onConnected() }
                } else if (s !is CarState.Connected) {
                    installed.value = null
                }
            }
        }
        // Also away from the car: release metadata is a few KB, so check at app start when stale.
        app.scope.launch { if (stale()) checkUpdates(manual = false) }
    }

    private fun stale() = System.currentTimeMillis() - settings.lastCheckOk > 30 * 60_000

    private fun load(): List<ManagedApp> {
        val overrides = settings.sourceOverrides
        val catalog = Catalog.apps.map { a -> overrides[a.packageName]?.let { a.copy(source = Source.parse(it)) } ?: a }
        return catalog + settings.customApps.filter { c -> catalog.none { it.packageName == c.packageName } }
    }

    private suspend fun onConnected() {
        car.cleanTempApks()
        refreshInstalled()
        // Once per connection, and not again within 30 min: cached metadata covers the gap.
        if (stale()) checkUpdates(manual = false)
    }

    /** Records the car's apps, also as the snapshot for offline use, and drops downloads it no longer needs. */
    private fun setInstalled(now: Map<String, InstalledVersion>) {
        installed.value = now
        car.info?.let { info ->
            val s = CarSnapshot(info.name, info.serial, info.traits, now, System.currentTimeMillis())
            snapshot.value = s
            settings.carSnapshot = s
        }
        cache.prune(now, releases.value)
    }

    suspend fun refreshInstalled() {
        try {
            setInstalled(car.installedVersions(managed.value.map { it.packageName }))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            app.say("Couldn't read the apps on the car: ${e.message}")
        }
    }

    /** The refresh button: re-reads the car and asks every configured source. */
    fun refresh() {
        dismissed.value = emptySet()
        app.scope.launch {
            if (car.info != null) refreshInstalled()
            checkUpdates(manual = true)
        }
    }

    private fun checkUpdates(manual: Boolean) {
        if (checkJob?.isActive == true) return
        checkJob = app.scope.launch {
            val withSource = managed.value.filter { it.source != null }
            _check.value = CheckState.Checking
            val failures = mutableMapOf<String, String>()
            for (a in withSource) {
                try {
                    val r = app.releases.latest(a, a.source!!, car.info?.traits ?: snapshot.value?.traits, releases.value[a.packageName])
                    releases.update { it + (a.packageName to r) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures[a.packageName] = e.message ?: e.javaClass.simpleName
                }
            }
            settings.releases = releases.value
            errors.value = failures
            (installed.value ?: snapshot.value?.installed)?.let { cache.prune(it, releases.value) }
            if (failures.isEmpty()) {
                settings.lastCheckOk = System.currentTimeMillis()
                _check.value = CheckState.Idle(settings.lastCheckOk)
                if (manual && rows.value.none { it.updateAvailable }) app.say("All apps on the car are up to date")
            } else {
                val reason = failures.values.distinct().let { if (it.size == 1) it[0] else "${failures.size} sources couldn't be checked" }
                _check.value = CheckState.Failed(reason, settings.lastCheckOk)
            }
        }
    }

    fun notNow() {
        dismissed.update { d -> d + pending.value.map { "${it.app.packageName}@${it.release!!.versionCode}" } }
    }

    /** Pending installs on the car run one at a time. */
    private val installLock = Mutex()

    /** Downloads and installs the known release of each app, one after another (after the user confirmed). */
    fun installReleases(targets: List<ManagedApp>) {
        for (a in targets.filter { busy.value[it.packageName] == null }) {
            Ops.launch(app, app.scope, "Installing ${a.name} on the car") { op ->
                busy.update { it + (a.packageName to op) }
                op.phase("Waiting…")
                try {
                    installLock.withLock {
                        val was = installed.value?.get(a.packageName)
                        val now = installRelease(a, op)
                        val v = now.versionName ?: "build ${now.versionCode}"
                        app.say(if (was != null) "${a.name} updated to $v" else "${a.name} $v installed on the car")
                    }
                } catch (e: CancellationException) {
                    app.say("${a.name}: cancelled")
                    throw e
                } catch (e: Exception) {
                    app.say("${a.name}: ${e.message}")
                } finally {
                    busy.update { it - a.packageName }
                }
            }
        }
    }

    private suspend fun installRelease(a: ManagedApp, op: Op): InstalledVersion {
        val info = car.info ?: throw me.ri3d.adb.car.NotConnected()
        val r = releases.value[a.packageName] ?: throw IllegalStateException("No known release")
        incompatibility(r.minSdk, r.abis, info.sdk, info.release, info.abis)?.let { throw IllegalStateException(it) }
        val file = fetch(a, r, op)
        val now = car.installApk(file, r.packageName, r.versionCode) { p -> op.report(p) }
        setInstalled((installed.value ?: emptyMap()) + (a.packageName to now))
        cache.drop(a.packageName) // installed: the download isn't needed any more (a failed install keeps it)
        return now
    }

    /** The release APK from the offline store, downloading and verifying it first if needed. */
    private suspend fun fetch(a: ManagedApp, r: Release, op: Op): File {
        if (cache.has(r)) return cache.file(r.packageName, r.versionCode)
        op.phase("Downloading ${r.label}…", 0, r.size)
        val file = try {
            app.releases.download(r, cache.dir) { done, total -> op.phase("Downloading ${r.label}…", done, total) }
        } catch (e: java.io.IOException) {
            // No internet at all, or a network that can't reach the server (typical at the car).
            if (e !is NoInternet && e !is java.net.UnknownHostException && e !is java.net.ConnectException && e !is java.net.SocketTimeoutException) throw e
            throw IllegalStateException("Can't download ${a.name} ${r.label} here (${e.message}). Use “Download for offline” while you have internet.", e)
        }
        try {
            val local = withContext(Dispatchers.IO) { inspectApkFile(app, file, file.name) }
            if (local.manifest.packageName != r.packageName || local.manifest.versionCode != r.versionCode) {
                throw IllegalStateException("The downloaded APK isn't ${r.packageName} ${r.label}")
            }
            withContext(Dispatchers.IO) { saveIcon(app, file, a.packageName) }
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
        cache.keep(r.packageName, r.versionCode)
        return file
    }

    /** Downloads the known releases of [targets] so they can be installed at the car without internet (user-initiated). */
    fun downloadForOffline(targets: List<ManagedApp>) {
        for (a in targets.filter { busy.value[it.packageName] == null }) {
            val r = releases.value[a.packageName] ?: continue
            if (cache.has(r)) continue
            Ops.launch(app, app.scope, "Downloading ${a.name} ${r.label}") { op ->
                busy.update { it + (a.packageName to op) }
                try {
                    fetch(a, r, op)
                    app.say("${a.name} ${r.label} is ready to install without internet")
                } catch (e: CancellationException) {
                    app.say("${a.name}: download cancelled")
                    throw e
                } catch (e: Exception) {
                    app.say("${a.name}: ${e.message}")
                } finally {
                    busy.update { it - a.packageName }
                }
            }
        }
    }

    private fun Op.report(p: InstallPhase) = when (p) {
        is InstallPhase.Sending -> phase("Sending to the car…", p.done, p.total)
        InstallPhase.Installing -> phase("Installing on the car…")
    }

    fun launch(a: ManagedApp) {
        app.scope.launch {
            try {
                car.launch(a.packageName, a.launchComponent)
                app.say("Opened ${a.name} on the car screen")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                app.say("Couldn't open ${a.name}: ${e.message}")
            }
        }
    }

    /** Sets or clears (blank) the update source. Returns an error message for unparseable input. */
    fun setSource(a: ManagedApp, text: String): String? {
        val src = if (text.isBlank()) null else Source.parse(text) ?: return "Use github:Owner/Repo or an https:// JSON manifest URL"
        if (a.fromCatalog) {
            settings.sourceOverrides = settings.sourceOverrides.toMutableMap().apply {
                if (src == null) remove(a.packageName) else put(a.packageName, src.text())
            }
        } else {
            settings.customApps = settings.customApps.map { if (it.packageName == a.packageName) it.copy(source = src) else it }
        }
        managed.value = load()
        releases.update { it - a.packageName }
        errors.update { it - a.packageName }
        if (src != null) {
            settings.lastCheckOk = 0
            checkUpdates(manual = false)
        }
        return null
    }

    fun remove(a: ManagedApp) {
        if (a.fromCatalog) return
        settings.customApps = settings.customApps.filter { it.packageName != a.packageName }
        managed.value = load()
    }

    private fun addCustom(packageName: String, name: String) {
        if (managed.value.any { it.packageName == packageName }) return
        settings.customApps = settings.customApps + ManagedApp(packageName, name.ifBlank { packageName }, null)
        managed.value = load()
    }

    // ---- "Install an app" screen ----

    val picked = MutableStateFlow<Picked>(Picked.None)
    val addOp = MutableStateFlow<Op?>(null)
    val addResult = MutableStateFlow<AddResult?>(null)
    val carPackages = MutableStateFlow<CarPackages>(CarPackages.Loading)

    fun pick(uri: Uri) {
        picked.value = Picked.Reading
        app.scope.launch {
            picked.value = try {
                Picked.Ready(withContext(Dispatchers.IO) { inspectApk(app, uri, File(app.cacheDir, "picked")) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Picked.Invalid(e.message ?: "Couldn't read the file")
            }
        }
    }

    fun unpick() {
        picked.value = Picked.None
        addResult.value = null
    }

    fun installPicked(name: String, openAfter: Boolean) {
        val apk = (picked.value as? Picked.Ready)?.apk ?: return
        if (addOp.value != null) return
        settings.openAfterInstall = openAfter
        addResult.value = null
        val pkg = apk.manifest.packageName
        addOp.value = Ops.launch(app, app.scope, "Installing ${name.ifBlank { pkg }} on the car") { op ->
            busy.update { it + (pkg to op) }
            try {
                withContext(Dispatchers.IO) { saveIcon(app, apk.file, pkg) }
                val now = installLock.withLock { car.installApk(apk.file, pkg, apk.manifest.versionCode) { p -> op.report(p) } }
                addCustom(pkg, name)
                setInstalled((installed.value ?: emptyMap()) + (pkg to now))
                val shown = managed.value.first { it.packageName == pkg }
                var detail = "${now.versionName ?: "build ${now.versionCode}"} is installed. ${shown.name} shows up under Apps on the car."
                if (openAfter) {
                    detail += try {
                        car.launch(pkg, shown.launchComponent)
                        " It was opened on the car screen."
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        " Opening it failed: ${e.message}"
                    }
                }
                addResult.value = AddResult.Done(shown.name, detail)
                apk.file.delete()
                picked.value = Picked.None
            } catch (e: CancellationException) {
                addResult.value = AddResult.Failed("Cancelled. Nothing was installed unless the car had already started installing.")
                throw e
            } catch (e: Exception) {
                addResult.value = AddResult.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                busy.update { it - pkg }
                addOp.value = null
            }
        }
    }

    fun loadCarPackages() {
        carPackages.value = CarPackages.Loading
        app.scope.launch {
            carPackages.value = try {
                CarPackages.Loaded(car.userPackages())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CarPackages.Failed(e.message ?: "Couldn't list the car's apps")
            }
        }
    }

    /** Registers an app that is already installed on the car; nothing is reinstalled. */
    fun addExisting(packageName: String, name: String) {
        addCustom(packageName, name)
        addResult.value = AddResult.Done(managed.value.first { it.packageName == packageName }.name, "${name.ifBlank { packageName }} now shows up under Apps on the car.")
        app.scope.launch { refreshInstalled() }
    }

    fun carInfo(): CarInfo? = car.info
}
