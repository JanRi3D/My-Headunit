package me.ri3d.adb.console

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.ri3d.adb.App
import me.ri3d.adb.adb.Foreground
import me.ri3d.adb.adb.ShellTimeout
import me.ri3d.adb.adb.q
import me.ri3d.adb.apps.Catalog
import me.ri3d.adb.car.NotConnected
import me.ri3d.adb.car.foreground
import me.ri3d.adb.car.installedVersions
import me.ri3d.adb.car.userPackages

enum class Kind { Cmd, Out, Err, Ok, Info, Fail }

data class Line(val id: Long, val kind: Kind, val text: String)

/** The command console: real shell commands on the car, with a bounded log. */
class Console(private val app: App) {
    private val car get() = app.car
    val lines = MutableStateFlow<List<Line>>(emptyList())
    val running = MutableStateFlow(false)
    private var job: Job? = null
    private var nextId = 0L

    @Synchronized
    private fun add(kind: Kind, text: String) {
        lines.update { (it + Line(nextId++, kind, text)).takeLast(MAX_LINES) }
    }

    private fun run(label: String, block: suspend () -> Unit) {
        if (job?.isActive == true) return
        add(Kind.Cmd, label)
        job = app.scope.launch {
            running.value = true
            try {
                block()
            } catch (_: ShellTimeout) {
                add(Kind.Fail, "No result after ${TIMEOUT_MS / 1000} s; the command was stopped.")
            } catch (_: NotConnected) {
                add(Kind.Fail, "Not connected to the car.")
            } catch (e: CancellationException) {
                add(Kind.Info, "Stopped.")
                throw e
            } catch (e: Exception) {
                add(Kind.Fail, e.message ?: e.javaClass.simpleName)
            } finally {
                running.value = false
            }
        }
    }

    /** Runs what the user typed, exactly, after they pressed Send. */
    fun send(command: String) {
        val cmd = command.trim()
        if (cmd.isEmpty()) return
        run(cmd) {
            val r = car.exec(cmd, TIMEOUT_MS) { line, err -> add(if (err) Kind.Err else Kind.Out, line) }
            when (val code = r.exitCode) {
                null -> add(Kind.Info, "Exit status unavailable (this car's ADB shell doesn't report it)")
                0 -> add(Kind.Ok, "exit 0")
                else -> add(Kind.Fail, "exit $code")
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    fun clear() {
        lines.value = emptyList()
    }

    /** Starts FileExplorer's configured component after checking it is installed. */
    fun startFileExplorer() {
        val fe = app.apps.apps.value.firstOrNull { it.packageName == Catalog.fileExplorer.packageName } ?: Catalog.fileExplorer
        val component = fe.launchComponent ?: return
        run("am start -n $component") {
            if (car.installedVersions(listOf(fe.packageName)).isEmpty()) {
                add(Kind.Fail, "${fe.name} (${fe.packageName}) isn't installed on the car.")
                return@run
            }
            val out = car.exec("am start -n ${q(component)}", TIMEOUT_MS) { line, _ -> add(Kind.Out, line) }.output
            if ("Error" in out || "does not exist" in out) {
                add(Kind.Fail, "The car can't start $component. Check the activity name in the FileExplorer configuration.")
            } else {
                add(Kind.Ok, "Started ${fe.name}")
            }
        }
    }

    /** Which app is on the car's screen (null if it can't be told reliably). Logged to the console. */
    suspend fun identifyForeground(): Result<Foreground?> = try {
        val f = car.foreground()
        Result.success(f)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun packages(): List<String> = car.userPackages()

    fun forceStop(pkg: String, how: String?) {
        run("am force-stop $pkg") {
            if (how != null) add(Kind.Info, "In front: $pkg (from $how)")
            val r = car.exec("am force-stop ${q(pkg)}", TIMEOUT_MS) { line, err -> add(if (err) Kind.Err else Kind.Out, line) }
            when (r.exitCode) {
                0 -> add(Kind.Ok, "Stopped $pkg")
                null -> add(Kind.Info, "Force-stop sent to $pkg (exit status unavailable)")
                else -> add(Kind.Fail, "exit ${r.exitCode}")
            }
        }
    }

    companion object {
        const val MAX_LINES = 500
        const val TIMEOUT_MS = 120_000L
    }
}
