/*
 * Copyright 2026 HushThreads contributors
 * https://github.com/SysAdminDoc/HushThreads
 *
 * Built on SysAdminDoc/Hushfacebook (GPL-3.0).
 */
package app.morphe.patches.threads.misc.analytics

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.threads.misc.extension.enableStatus
import app.morphe.patches.threads.misc.extension.handleTargets
import app.morphe.patches.threads.misc.extension.requireStatusMethod
import app.morphe.patches.threads.misc.extension.threadsExtensionPatch
import app.morphe.patches.threads.misc.settings.settingsPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.indexOfFirstStringInstruction
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val PATCH = "Disable analytics"

private const val ENDPOINT =
    "Lapp/morphe/extension/hushthreads/misc/Analytics;->endpoint(Ljava/lang/String;)Ljava/lang/String;"

/** Where Meta's apps post their event logs when nothing else says where. */
private const val LOGGING_URL = "https://graph.facebook.com/logging_client_events"

/**
 * The Pigeon logger's address builder: a host in, `https://<host>/logging_client_events` out, or
 * `/pigeon_nest` for a batch. A static (String, boolean) method, its class and name Redex's.
 */
internal object PigeonUrlFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/String;",
    parameters = listOf("Ljava/lang/String;", "Z"),
    filters = listOf(string("/pigeon_nest"), string("/logging_client_events")),
)

/** A provider whose `get()` answers the default event log address and nothing else. */
internal object LoggingUrlProviderFingerprint : Fingerprint(
    name = "get",
    returnType = "Ljava/lang/Object;",
    parameters = listOf(),
    filters = listOf(string(LOGGING_URL)),
)

/**
 * The MQTT client's settings, read from a JSON object. One of them is the address its own
 * analytics go to, with the default event log address as the fallback.
 */
internal object MqttSettingsFingerprint : Fingerprint(
    name = "<init>",
    parameters = listOf("Lorg/json/JSONObject;"),
    filters = listOf(string("analytics_endpoint"), string(LOGGING_URL)),
)

/**
 * Sends Threads' analytics uploads nowhere.
 *
 * Each place Threads builds the address it posts event logs to hands that address to the
 * extension, which answers a port on the phone itself that nothing listens on while the switch is
 * on. The upload fails there and then, and nothing else about the request, or any other request,
 * changes. The three places stand alone: a build that renamed one still has the others covered,
 * and the patch log names the one it went without.
 */
@Suppress("unused")
val disableAnalyticsPatch = bytecodePatch(
    name = PATCH,
    description = "Stops Threads sending its usage analytics and event logs to Meta. Everything " +
        "the app needs to work is left alone.",
    default = true,
) {
    category("Privacy")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.threads())
    dependsOn(threadsExtensionPatch)

    execute {
        requireStatusMethod("disableAnalytics")

        val targets = listOf(PigeonUrlFingerprint, LoggingUrlProviderFingerprint, MqttSettingsFingerprint)
        handleTargets(PATCH, "analytics addresses", targets) { fingerprint ->
            val method = fingerprint.methodOrNull ?: return@handleTargets when (fingerprint) {
                PigeonUrlFingerprint -> "no static (String, boolean) method builds the Pigeon logger's address"
                LoggingUrlProviderFingerprint -> "no provider's get() answers $LOGGING_URL"
                else -> "no constructor reads analytics_endpoint from the MQTT client's settings"
            }
            if (fingerprint == MqttSettingsFingerprint) {
                method.wrapAnalyticsSetting()
            } else {
                method.wrapEveryReturn()
                null
            }
        }

        enableStatus("disableAnalytics")
    }
}

/**
 * Sends each address this method returns through the extension, last return first, since an insert
 * moves every later index. Each goes in at the return's own label, so a branch to it runs it too.
 */
private fun MutableMethod.wrapEveryReturn() {
    val returns = implementation!!.instructions.withIndex()
        .filter { it.value.opcode == Opcode.RETURN_OBJECT }
        .map { it.index to (it.value as OneRegisterInstruction).registerA }
    check(returns.isNotEmpty()) { "$definingClass->$name returns nothing to replace" }
    returns.asReversed().forEach { (index, register) ->
        addInstructionsAtControlFlowLabel(
            index,
            """
                invoke-static/range { v$register .. v$register }, $ENDPOINT
                move-result-object v$register
            """,
        )
    }
}

/**
 * Sends the analytics address the MQTT settings read to the extension, right after it's read:
 * the first result after the `analytics_endpoint` key. Answers null when that went in, or why not.
 */
private fun MutableMethod.wrapAnalyticsSetting(): String? {
    val instructions = implementation!!.instructions.toList()
    val key = indexOfFirstStringInstruction("analytics_endpoint")
    val read = (key until instructions.size).firstOrNull { instructions[it].opcode == Opcode.MOVE_RESULT_OBJECT }
        ?: return "$definingClass->$name never reads analytics_endpoint's value"
    val register = (instructions[read] as OneRegisterInstruction).registerA
    addInstructions(
        read + 1,
        """
            invoke-static/range { v$register .. v$register }, $ENDPOINT
            move-result-object v$register
        """,
    )
    return null
}
