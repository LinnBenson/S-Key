package io.secure.keyboard

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import androidx.lifecycle.viewModelScope
import io.secure.keyboard.data.ContentStore
import io.secure.keyboard.data.SettingsStore
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.UUID

/** Isolated ViewModels, installed at super.onCreate before the production Compose screen renders. */
internal class SettingsUiFixture(private val instrumentation: Instrumentation) : AutoCloseable {
    val context = IsolatedContext(instrumentation.targetContext)
    val content = ContentStore(context)
    val config = SettingsStore(context)
    @Volatile var current: Activity? = null
        private set
    var destroyedCount = 0
        private set
    val destroyedActivities = mutableListOf<WeakReference<Activity>>()
    private val live = mutableSetOf<Activity>()
    private val ownedJobs = mutableSetOf<kotlinx.coroutines.Job>()
    private val application = instrumentation.targetContext.applicationContext as Application
    private val actualPreferences = snapshotPreferences()
    private val actualContent = snapshotContent()
    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, state: Bundle?) {
            if (!owns(activity)) return
            live.add(activity)
            if (activity is SettingsActivity) {
                val isolatedApp = object : Application() {
                    init { attachBaseContext(context) }
                    override fun getApplicationContext(): Context = this
                }
                val factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : androidx.lifecycle.ViewModel> create(type: Class<T>): T =
                        when (type) {
                            io.secure.keyboard.settings.SettingsModel::class.java -> io.secure.keyboard.settings.SettingsModel(isolatedApp) as T
                            io.secure.keyboard.settings.BuiltinDictionaryModel::class.java -> io.secure.keyboard.settings.BuiltinDictionaryModel(isolatedApp, androidx.lifecycle.SavedStateHandle()) as T
                            else -> error("Unexpected fixture ViewModel: $type")
                        }
                }
                val settings = androidx.lifecycle.ViewModelProvider(activity, factory)[io.secure.keyboard.settings.SettingsModel::class.java]
                val dictionaries = androidx.lifecycle.ViewModelProvider(activity, factory)[io.secure.keyboard.settings.BuiltinDictionaryModel::class.java]
                for (model in listOf(settings, dictionaries))
                    model.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.let { ownedJobs.add(it) }
            }
        }
        override fun onActivityStarted(activity: Activity) { if (owns(activity)) current = activity }
        override fun onActivityResumed(activity: Activity) { if (owns(activity)) current = activity }
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) {
            if (!live.remove(activity)) return
            destroyedCount++
            destroyedActivities.add(WeakReference(activity))
            if (current === activity) current = null
        }
    }

    init { instrumentation.runOnMainSync { application.registerActivityLifecycleCallbacks(lifecycle) } }

    fun assertActualDataUnchanged() {
        check(actualPreferences == snapshotPreferences()) { "Actual application settings changed during isolated UI tests" }
        check(actualContent == snapshotContent()) { "Actual application content changed during isolated UI tests" }
    }

    override fun close() {
        try {
            instrumentation.runOnMainSync { live.toList().forEach(Activity::finish) }
            instrumentation.waitForIdleSync()
            val deadline = android.os.SystemClock.uptimeMillis() + 30000
            while (ownedJobs.any { !it.isCompleted } && android.os.SystemClock.uptimeMillis() < deadline) {
                android.os.SystemClock.sleep(20)
                instrumentation.waitForIdleSync()
            }
            check(ownedJobs.all { it.isCompleted }) { "ViewModel work must end before isolated files are removed" }
            assertActualDataUnchanged()
        } finally {
            instrumentation.runOnMainSync {
                application.unregisterActivityLifecycleCallbacks(lifecycle)
                live.clear()
                current = null
            }
            // Delete only the UUID-scoped test directory/preferences that this fixture created.
            if (ownedJobs.all { it.isCompleted }) {
                context.root.deleteRecursively()
                context.deleteOwnedPreferences()
            }
            ownedJobs.clear()
            destroyedActivities.clear()
        }
    }

    private fun owns(activity: Activity) = activity is SettingsActivity || activity is MainActivity || activity is VoiceSetupActivity
    private fun snapshotPreferences(): Map<String, Any?> = instrumentation.targetContext
        .getSharedPreferences("settings", Context.MODE_PRIVATE).all.mapValues { (_, value) ->
            if (value is Set<*>) value.toSet() else value
        }
    private fun snapshotContent(): Map<String, String> {
        val root = instrumentation.targetContext.noBackupFilesDir
        return listOf("content", "private-content").flatMap { name ->
            File(root, name).walkTopDown().filter(File::isFile).map { it.relativeTo(root).path to settingsQaSha256(it) }.toList()
        }.toMap()
    }

    class IsolatedContext(base: Context) : ContextWrapper(base) {
        private val prefix = "settings-016-ui-${UUID.randomUUID()}"
        val root = File(base.cacheDir, prefix).apply { mkdirs() }
        private val preferenceNames = mutableSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = File(root, "no_backup").apply { mkdirs() }
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        override fun getExternalCacheDir(): File = File(root, "external_cache").apply { mkdirs() }
        override fun getExternalCacheDirs(): Array<File> = arrayOf(externalCacheDir)
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val owned = "$prefix-$name"
            preferenceNames.add(owned)
            return super.getSharedPreferences(owned, mode)
        }
        fun deleteOwnedPreferences() { preferenceNames.forEach { baseContext.deleteSharedPreferences(it) } }
    }
}

/** Unmodified UiAutomation pixels plus sidecar provenance; never recolors or composes screenshots. */
internal class SettingsUiEvidence(private val instrumentation: Instrumentation, private val scenario: String) {
    private val target = instrumentation.targetContext
    private val apkHash by lazy { settingsQaSha256(File(target.applicationInfo.sourceDir)) }
    private val directory by lazy {
        File(requireNotNull(target.getExternalFilesDir(null)), "validation/settings-redesign-0.1.6/$scenario")
            .apply { check(isDirectory || mkdirs()) }
    }

    fun capture(activity: Activity, route: String, state: String, keyboardMode: String, name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val file = File(directory, "$name.png")
        val config = activity.resources.configuration
        val metrics = activity.resources.displayMetrics
        val packageInfo = target.packageManager.getPackageInfo(target.packageName, 0)
        val metadata = JSONObject().apply {
            put("capture", "native-ui-automation-unmodified")
            put("scenario", scenario)
            put("route", route)
            put("state", state)
            put("activity", activity.javaClass.name)
            put("title", activity.title?.toString())
            put("syntheticContentAndSettings", true)
            put("versionName", packageInfo.versionName)
            put("versionCode", if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else packageInfo.versionCode.toLong())
            put("apkSha256", apkHash)
            put("sdk", Build.VERSION.SDK_INT)
            put("widthPixels", bitmap.width)
            put("heightPixels", bitmap.height)
            put("screenWidthDp", config.screenWidthDp)
            put("screenHeightDp", config.screenHeightDp)
            put("densityDpi", metrics.densityDpi)
            put("fontScale", config.fontScale)
            put("orientation", config.orientation)
            put("systemNight", config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
            put("keyboardModeFixture", keyboardMode)
            put("capturedAtEpochMs", System.currentTimeMillis())
        }
        try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        check(file.length() > 0)
        metadata.put("pngSha256", settingsQaSha256(file))
        File(directory, "$name.json").writeText(metadata.toString(2))
        instrumentation.sendStatus(2, Bundle().apply {
            putString("validation_screenshot", file.absolutePath)
            putString("validation_metadata", File(directory, "$name.json").absolutePath)
        })
    }
}

internal fun settingsQaSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { stream ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}
