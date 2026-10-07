package me.ri3d.adb.files

import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ri3d.adb.App
import me.ri3d.adb.Op
import me.ri3d.adb.Ops
import me.ri3d.adb.adb.RemoteFile
import me.ri3d.adb.adb.SyncFailed
import me.ri3d.adb.adb.mvCommand
import me.ri3d.adb.adb.rmCommand
import me.ri3d.adb.car.CarState
import java.io.IOException

sealed interface Listing {
    data object Loading : Listing
    data class Loaded(val path: String, val entries: List<RemoteFile>) : Listing
    data class Failed(val path: String, val reason: String) : Listing
}

/** A phone file chosen for upload. */
data class Outgoing(val uri: Uri, val name: String, val size: Long)

fun parentOf(path: String): String = path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }

fun childOf(dir: String, name: String): String = if (dir == "/") "/$name" else "${dir.trimEnd('/')}/$name"

/** The car's storage over ADB SYNC. Paths are remembered; transfers stream and can be cancelled. */
class FilesModel(private val app: App) {
    private val car get() = app.car
    val path = MutableStateFlow(app.settings.filesPath)
    val listing = MutableStateFlow<Listing>(Listing.Loading)
    val transfer = MutableStateFlow<Op?>(null)
    private var loadJob: Job? = null

    init {
        var lastSession = -1L
        app.scope.launch {
            car.state.collect { s ->
                if (s is CarState.Connected && s.info.session != lastSession) {
                    lastSession = s.info.session
                    load()
                }
            }
        }
    }

    fun open(p: String) {
        path.value = p
        app.settings.filesPath = p
        load()
    }

    fun refresh() = load()

    private fun load() {
        val p = path.value
        loadJob?.cancel()
        listing.value = Listing.Loading
        loadJob = app.scope.launch {
            listing.value = try {
                val all = car.sync { it.list(p) }
                if (all.none { it.name == "." }) {
                    // adbd answers folders it can't open with an empty list: find out why.
                    val st = car.sync { it.stat(p) }
                    Listing.Failed(p, when {
                        !st.exists -> "$p doesn't exist on the car, or the ADB shell can't see it."
                        st.isFile -> "$p is a file, not a folder."
                        else -> "The ADB shell user isn't allowed to open $p."
                    })
                } else {
                    Listing.Loaded(p, all.filter { it.name != "." && it.name != ".." }
                        .sortedWith(compareBy<RemoteFile> { !(it.isDir || it.isLink) }.thenBy { it.name.lowercase() }))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Listing.Failed(p, e.message ?: "Couldn't read $p")
            }
        }
    }

    /** Names and sizes of picked phone files, and which of them already exist in the current folder. */
    suspend fun prepareUpload(uris: List<Uri>): Pair<List<Outgoing>, List<String>> {
        val cr = app.contentResolver
        val items = withContext(Dispatchers.IO) {
            uris.map { uri ->
                cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) Outgoing(uri, c.getString(0) ?: "file", if (c.isNull(1)) -1 else c.getLong(1)) else null
                } ?: Outgoing(uri, uri.lastPathSegment?.substringAfterLast('/') ?: "file", -1)
            }
        }
        val dir = path.value
        val existing = car.sync { s -> items.filter { s.stat(childOf(dir, it.name)).exists }.map { it.name } }
        return items to existing
    }

    /**
     * Uploads into the current folder. Each file is written as a hidden ".part" file and renamed
     * when complete, so an interrupted upload never leaves a half file under the real name.
     */
    fun upload(items: List<Outgoing>, skip: Set<String>) {
        val todo = items.filter { it.name !in skip }
        if (todo.isEmpty() || transfer.value != null) return
        val dir = path.value
        transfer.value = Ops.launch(app, app.scope, "Uploading to the car") { op ->
            val total = todo.sumOf { it.size.coerceAtLeast(0) }
            var before = 0L
            var done = 0
            try {
                for (f in todo) {
                    val target = childOf(dir, f.name)
                    val part = childOf(dir, ".${f.name}.mhu-part")
                    try {
                        op.phase("Uploading ${f.name}", before, total)
                        withContext(Dispatchers.IO) {
                            (app.contentResolver.openInputStream(f.uri) ?: throw IOException("Can't read ${f.name} on this phone")).use { input ->
                                car.sync { it.send(part, 0b110_100_100, System.currentTimeMillis() / 1000, input) { n -> op.phase("Uploading ${f.name}", before + n, total) } }
                            }
                        }
                        val mv = car.exec(mvCommand(part, target))
                        if (mv.exitCode != null && mv.exitCode != 0 || mv.output.isNotBlank()) throw IOException("Couldn't rename the upload: ${mv.output.ifBlank { "exit ${mv.exitCode}" }}")
                        done++
                        before += f.size.coerceAtLeast(0)
                    } catch (e: Throwable) {
                        withContext(NonCancellable) { runCatching { car.exec(rmCommand(listOf(part)), 15_000) } }
                        throw e
                    }
                }
                app.say(if (todo.size == 1) "${todo[0].name} uploaded to $dir" else "${todo.size} files uploaded to $dir")
            } catch (e: CancellationException) {
                app.say("Upload cancelled. $done of ${todo.size} files arrived; the interrupted one was removed.")
                throw e
            } catch (e: Exception) {
                app.say("Upload failed after $done of ${todo.size} files: ${explain(e)}")
            } finally {
                transfer.value = null
                if (path.value == dir) load()
            }
        }
    }

    /** Downloads [files] to documents the user chose (one each). Incomplete downloads are deleted. */
    fun download(files: List<Pair<RemoteFile, Uri>>) {
        if (files.isEmpty() || transfer.value != null) return
        val dir = path.value
        transfer.value = Ops.launch(app, app.scope, "Downloading from the car") { op ->
            val total = files.sumOf { it.first.size }
            var before = 0L
            var done = 0
            try {
                for ((i, pair) in files.withIndex()) {
                    val (f, uri) = pair
                    val remote = childOf(dir, f.name)
                    try {
                        op.phase("Downloading ${f.name}", before, total)
                        val expected = car.sync { it.stat(remote) }.size
                        val got = withContext(Dispatchers.IO) {
                            (app.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Can't write to the chosen location")).use { out ->
                                car.sync { it.recv(remote, out) { n -> op.phase("Downloading ${f.name}", before + n, total) } }
                            }
                        }
                        if (got != expected) throw IOException("${f.name} arrived incomplete ($got of $expected bytes)")
                        done++
                        before += f.size
                    } catch (e: Throwable) {
                        // This file is incomplete and the ones after it were never written: remove them all.
                        withContext(NonCancellable + Dispatchers.IO) {
                            files.drop(i).forEach { runCatching { DocumentsContract.deleteDocument(app.contentResolver, it.second) } }
                        }
                        throw e
                    }
                }
                app.say(if (files.size == 1) "${files[0].first.name} saved to this phone" else "${files.size} files saved to this phone")
            } catch (e: CancellationException) {
                app.say("Download cancelled. $done of ${files.size} files saved; the incomplete one was deleted.")
                throw e
            } catch (e: Exception) {
                app.say("Download failed after $done of ${files.size} files: ${explain(e)}. The incomplete file was deleted.")
            } finally {
                transfer.value = null
            }
        }
    }

    /** Creates one document per file in the folder [tree] the user picked, then downloads into them. */
    fun downloadToFolder(files: List<RemoteFile>, tree: Uri) {
        app.scope.launch {
            try {
                val cr = app.contentResolver
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val targets = withContext(Dispatchers.IO) {
                    // The provider renames on name clashes ("name (1).ext"), so nothing is overwritten.
                    files.map { f -> f to (DocumentsContract.createDocument(cr, parent, mimeOf(f.name), f.name) ?: throw IOException("Can't create ${f.name} there")) }
                }
                download(targets)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                app.say("Can't save there: ${e.message}")
            }
        }
    }

    fun delete(files: List<RemoteFile>, onDone: () -> Unit) {
        val dir = path.value
        app.scope.launch {
            try {
                val paths = files.map { childOf(dir, it.name) }
                car.exec(rmCommand(paths), 60_000)
                val left = car.sync { s -> paths.filter { s.stat(it).exists } }
                app.say(
                    if (left.isEmpty()) "${plural(files.size)} deleted from the car"
                    else "${plural(files.size - left.size)} deleted; ${left.size} couldn't be deleted (no permission?): ${left.joinToString { it.substringAfterLast('/') }}",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                app.say("Delete failed: ${explain(e)}")
            } finally {
                onDone()
                load()
            }
        }
    }

    private fun explain(e: Exception) = when (e) {
        is SyncFailed -> e.message.orEmpty().let { if ("ermission" in it) "the car refused access ($it)" else it }
        else -> e.message ?: e.javaClass.simpleName
    }

    companion object {
        fun plural(n: Int) = if (n == 1) "1 file" else "$n files"
        fun mimeOf(name: String) =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
    }
}
