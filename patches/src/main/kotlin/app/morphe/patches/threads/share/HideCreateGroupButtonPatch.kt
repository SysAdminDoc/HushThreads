/*
 * Copyright 2026 HushThreads contributors
 * https://github.com/SysAdminDoc/HushThreads
 *
 * Found by reading 457 (2026-11-27). The idea comes from HushGram's "Hide group buttons on the
 * share sheet", which does the same on Instagram's own share sheet.
 */
package app.morphe.patches.threads.share

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.threads.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.threads.misc.extension.enableStatus
import app.morphe.patches.threads.misc.extension.parameterRegisterNumber
import app.morphe.patches.threads.misc.extension.requireParameterIntact
import app.morphe.patches.threads.misc.extension.requireStatusMethod
import app.morphe.patches.threads.misc.extension.threadsExtensionPatch
import app.morphe.patches.threads.misc.settings.EXTENSION_ROOT
import app.morphe.patches.threads.misc.settings.settingsPatch
import app.morphe.util.ControlFlow
import app.morphe.util.findMutableMethodOf
import app.morphe.util.getReference
import app.morphe.util.singleOrPatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Hide the Create group button"
internal const val CREATE_GROUP_BUTTON = "$EXTENSION_PACKAGE/share/CreateGroupButton;"
internal const val SHOW_CREATE_GROUP = "$CREATE_GROUP_BUTTON->show(Z)Z"

/** The test tag Threads puts on the share sheet's send button. Kept as is in 457. */
internal const val SEND_BUTTON_TAG = "post_share_sheet_send_button"

private const val STRING = "Ljava/lang/String;"
private const val FUNCTION0 = "Lkotlin/jvm/functions/Function0;"

/**
 * The share sheet's bottom bar: [method], the declared parameter [parameter] that says whether the
 * Create group button goes in, and the `if-eqz` at [guard], right after the send button is drawn,
 * that skips the Create group button when it's false. [button] is the call drawing that button.
 */
internal class ShareSheetBar(val method: Method, val parameter: Int, val guard: Int, val button: Int)

/**
 * Hides the Create group button on the share sheet.
 *
 * Pick two people or more on a post's share sheet and Threads shows two buttons at the bottom:
 * Send separately, and Create group. One Compose function draws that bar. It first draws the send
 * button, the function that sets the test tag [SEND_BUTTON_TAG], then an `if-eqz` on a boolean
 * parameter skips the Create group button when the parameter is false, which is the path Threads
 * already takes with one person picked. The same parameter also tells the send button to read
 * Send separately instead of Send, and the function hands it on before the check, so the extension
 * is asked right in front of the check rather than first thing: the send button keeps its label
 * and still sends to each person on their own, and only the Create group button goes.
 *
 * The bar is found as the one method of the send button's class that calls the send button and
 * checks a boolean parameter right after the call, with a call to another function of that class
 * taking a label and a click action in the branch the check skips. The patch refuses rather than
 * guessing when there isn't exactly one, when anything but the send button's call leads into the
 * check, or when the parameter is written over before the check reads it.
 */
@Suppress("unused")
val hideCreateGroupButtonPatch = bytecodePatch(
    name = PATCH,
    description = "Takes the Create group button off the share sheet, so picking two people or more only " +
        "offers Send separately. You can still start a group from your messages. Starts off. Turn it on in " +
        "HushThreads settings > Appearance.",
) {
    category("Interface")
    dependsOn(settingsPatch)
    dependsOn(threadsExtensionPatch)
    compatibleWith(*AppCompatibilities.threads())

    execute {
        requireStatusMethod("hideCreateGroupButton")
        val bar = shareSheetBar()
        val flag = bar.method.parameterRegisterNumber(bar.parameter)
        // Range form and move-result both reach any register, and the flag sits past v15.
        mutableClassDefBy(bar.method.definingClass).findMutableMethodOf(bar.method).addInstructions(
            bar.guard,
            """
                invoke-static/range { v$flag .. v$flag }, $SHOW_CREATE_GROUP
                move-result v$flag
            """,
        )
        enableStatus("hideCreateGroupButton")
    }
}

/** A method's full signature. A Method is a MethodReference too. */
private fun Method.signature() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

internal fun Instruction.isSendButtonTag(): Boolean =
    opcode == Opcode.CONST_STRING && getReference<StringReference>()?.string == SEND_BUTTON_TAG

private fun Instruction.callsStatic(): MethodReference? =
    if (opcode == Opcode.INVOKE_STATIC || opcode == Opcode.INVOKE_STATIC_RANGE) getReference<MethodReference>() else null

/** The share sheet's send button: the one method outside the extension that sets [SEND_BUTTON_TAG]. */
internal fun BytecodePatchContext.sendButton(): Method =
    classDefByStrings(SEND_BUTTON_TAG, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_ROOT) }
        .flatMap { it.methods }
        .distinctBy { it.signature() }
        .filter { method -> method.implementation?.instructions?.any { it.isSendButtonTag() } == true }
        .singleOrPatchException("$PATCH: the share sheet's \"$SEND_BUTTON_TAG\" tag")

/** The share sheet's bottom bar, read through the mutable copy so what's checked is what the hook goes into. */
internal fun BytecodePatchContext.shareSheetBar(): ShareSheetBar {
    val send = sendButton()
    val owner = mutableClassDefBy(send.definingClass)
    val sites = owner.methods.filter { it.implementation != null }.flatMap { method ->
        val body = method.implementation!!.instructions.toList()
        body.indices.filter { at ->
            body[at].callsStatic()?.let { it.definingClass == send.definingClass && it.name == send.name &&
                it.parameterTypes.map(CharSequence::toString) == send.parameterTypes.map(CharSequence::toString) } == true &&
                body.getOrNull(at + 1)?.opcode == Opcode.IF_EQZ
        }.map { method to it + 1 }
    }
    val (method, guard) = sites.singleOrPatchException("$PATCH: a check right after the share sheet's send button")
    return method.shareSheetBarAt(guard, send.definingClass)
}

/** The bar whose check sits at [guard]. Throws unless it's a check of a boolean parameter skipping a group button. */
internal fun Method.shareSheetBarAt(guard: Int, owner: String): ShareSheetBar {
    val flow = ControlFlow.of(this)
    val body = flow.instructions
    // The hook goes in front of the check, so only the send button's call may lead to it.
    val into = flow.instructions.indices.filter { from -> guard in flow.normal[from] || guard in flow.exceptional[from] }
    if (into != listOf(guard - 1)) {
        throw PatchException("$PATCH: ${signature()} reaches its check at $guard from $into, not only from the send button")
    }
    val skip = flow.normal[guard].singleOrNull { it != guard + 1 }
        ?: throw PatchException("$PATCH: the check at $guard in ${signature()} doesn't jump anywhere")
    val buttons = (guard + 1 until skip).filter { at ->
        val call = body[at].callsStatic() ?: return@filter false
        val types = call.parameterTypes.map(CharSequence::toString)
        call.definingClass == owner && call.returnType == "V" && STRING in types && FUNCTION0 in types
    }
    val button = buttons.singleOrNull()
        ?: throw PatchException("$PATCH: the check at $guard in ${signature()} skips ${buttons.size} labelled buttons, expected one")
    val register = (body[guard] as OneRegisterInstruction).registerA
    val parameter = parameterTypes.indices.firstOrNull { parameterRegisterNumber(it) == register }
        ?: throw PatchException("$PATCH: the check at $guard in ${signature()} reads v$register, which isn't a parameter")
    if (parameterTypes[parameter].toString() != "Z") {
        throw PatchException("$PATCH: the check at $guard in ${signature()} reads parameter $parameter, " +
            "a ${parameterTypes[parameter]}, not a boolean")
    }
    if (!AccessFlags.STATIC.isSet(accessFlags)) throw PatchException("$PATCH: ${signature()} isn't a static Compose function")
    requireParameterIntact(PATCH, parameter, listOf(guard))
    return ShareSheetBar(this, parameter, guard, button)
}
