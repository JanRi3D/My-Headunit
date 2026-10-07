package me.ri3d.adb.apps

/**
 * Where updates for an app come from. Written by users as text:
 * "github:Owner/Repo" (or a github.com URL) for GitHub Releases, any other https URL for a JSON manifest.
 */
sealed interface Source {
    /** Latest non-prerelease GitHub release; [asset] picks among several APK assets. */
    data class GitHub(val repo: String, val asset: Regex? = null) : Source

    /** HTTPS JSON manifest, format in README "Update sources". */
    data class Manifest(val url: String) : Source

    fun text(): String = when (this) {
        is GitHub -> "github:$repo"
        is Manifest -> url
    }

    fun describe(): String = when (this) {
        is GitHub -> "github.com/$repo"
        is Manifest -> url.removePrefix("https://").substringBefore('/')
    }

    companion object {
        private val github = Regex("""^(?:github:|https://github\.com/)([\w.-]+/[\w.-]+?)(?:\.git)?/?$""")

        fun parse(text: String): Source? {
            val t = text.trim()
            github.matchEntire(t)?.let { return GitHub(it.groupValues[1]) }
            if (t.startsWith("https://") && t.length > "https://x.y".length) return Manifest(t)
            return null
        }
    }
}

/** An app My Headunit manages on the car: one of the catalog apps or one the user added. */
data class ManagedApp(
    val packageName: String,
    val name: String,
    val source: Source?,
    /** "package/class" to start; null starts the launcher activity. */
    val launchComponent: String? = null,
    val fromCatalog: Boolean = false,
) {
    val monogram: String
        get() = name.split(Regex("""[\s_-]+|(?<=[a-z])(?=[A-Z])""")).filter { it.isNotEmpty() }
            .take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
}

/**
 * The predefined catalog. Package IDs, repositories and asset names come from the apps' own
 * repositories (app/build.gradle.kts applicationId, .github/workflows/release.yml asset name).
 * FileExplorer's package and activity are the ones given in the requirements; it has no
 * public release feed, so its source stays unset until the user supplies one.
 */
object Catalog {
    val apps = listOf(
        ManagedApp(
            packageName = "me.ri3d.openauto",
            name = "OpenAuto",
            source = Source.GitHub("JanRi3D/OpenAuto", Regex("""openauto-.*-release\.apk""")),
            fromCatalog = true,
        ),
        ManagedApp(
            packageName = "me.ri3d.welle",
            name = "Welle",
            source = Source.GitHub("JanRi3D/Welle", Regex("""welle-.*-release\.apk""")),
            fromCatalog = true,
        ),
        ManagedApp(
            packageName = "com.estrongs.android.pop",
            name = "FileExplorer",
            source = null,
            launchComponent = "com.estrongs.android.pop/com.estrongs.android.pop.view.FileExplorerActivity",
            fromCatalog = true,
        ),
    )

    val fileExplorer get() = apps.last()
}
