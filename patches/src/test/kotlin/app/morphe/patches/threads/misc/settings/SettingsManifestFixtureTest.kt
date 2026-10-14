/*
 * Copyright 2026 HushThreads contributors
 * https://github.com/SysAdminDoc/HushThreads
 */
package app.morphe.patches.threads.misc.settings

import app.morphe.FixtureResources
import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The settings manifest additions on every declared build: the App info alias and the file
 * picker's activity are added, nothing of Threads' own changes, the picker's activity can't be
 * reached from other apps, and a manifest that has them already is refused.
 */
class SettingsManifestFixtureTest {
    @Test
    fun everyDeclaredBuildGainsTheAliasAndThePickerAndNothingElse() {
        val builds = Fixtures.declaredBuilds()
        assertTrue("no declared build to check", builds.isNotEmpty())
        for (build in builds) FixtureResources.of(build).use { resources ->
            val manifest = resources.document("AndroidManifest.xml")
            val stock = FixtureResources.elements(manifest)
            addSettingsEntries(manifest)
            val patched = FixtureResources.elements(manifest)

            val removed = stock.toMutableList().apply { patched.forEach { remove(it) } }
            assertEquals("${build.name}: something of Threads' own changed", emptyList<String>(), removed)
            val application = "manifest/application[android:name=" +
                (manifest.getElementsByTagName("application").item(0) as Element).getAttribute("android:name") + "]/"
            val alias = "${application}activity-alias[android:name=$SETTINGS_ALIAS_NAME]/"
            assertEquals("${build.name}: what was added", listOf(
                "${alias}intent-filter/action[android:name=$APPLICATION_PREFERENCES]",
                "${alias}intent-filter/category[android:name=android.intent.category.DEFAULT]",
                "${alias}intent-filter[]",
                "${application}activity-alias[android:exported=true,android:name=$SETTINGS_ALIAS_NAME," +
                    "android:targetActivity=$MAIN_ACTIVITY_NAME]",
                "${application}activity[android:configChanges=$SETTINGS_FILE_PICKER_CONFIG_CHANGES," +
                    "android:exported=false,android:name=$SETTINGS_FILE_PICKER_NAME," +
                    "android:theme=@android:style/Theme.Translucent.NoTitleBar]",
            ).sorted(), (patched - stock.toSet()).sorted())

            val again = runCatching { addSettingsEntries(manifest) }.exceptionOrNull()
            assertTrue("${build.name}: ${again?.message}", again is PatchException && again.message!!.contains("already has"))
            assertEquals("${build.name}: a refused second run changed something", patched, FixtureResources.elements(manifest))
        }
    }
}
