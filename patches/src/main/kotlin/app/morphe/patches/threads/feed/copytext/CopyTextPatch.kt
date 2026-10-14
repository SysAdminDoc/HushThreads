/*
 * Copyright 2026 HushThreads contributors
 * https://github.com/SysAdminDoc/HushThreads
 *
 * Found by reading 451 for #9 (2026-10-13).
 */
package app.morphe.patches.threads.feed.copytext

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.threads.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.threads.misc.extension.freeLocalsAt
import app.morphe.patches.threads.misc.extension.localRegisterCount
import app.morphe.patches.threads.misc.extension.parameterRegister
import app.morphe.patches.threads.misc.extension.parameterRegisterNumber
import app.morphe.patches.threads.misc.extension.requireParameterIntact
import app.morphe.patches.threads.misc.extension.threadsExtensionPatch
import app.morphe.patches.threads.misc.settings.EXTENSION_ROOT
import app.morphe.util.ControlFlow
import app.morphe.util.findMutableMethodOf
import app.morphe.util.getReference
import app.morphe.util.literalReads
import app.morphe.util.singleOrPatchException
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.util.EnumSet

private const val PATCH = "Copy text on long press"
internal const val COPY_TEXT = "$EXTENSION_PACKAGE/feed/CopyText;"
internal const val LONG_PRESS = "$COPY_TEXT->longPress(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
internal const val SELECTION = "$COPY_TEXT->selection(Z)Z"

/**
 * The note on the lambda of Threads' post row that lays out a post's text, for the feed, profiles
 * and an opened post. The line number after it moves from build to build.
 */
internal const val POST_ROW_NOTE =
    "com.instagram.barcelona.feed.post.ui.FeedPostRow.<anonymous>.<anonymous> (FeedPostRow.kt:"

/** The note PostText puts on remembering its text selection, inlined into PostText itself. */
internal const val SELECTION_NOTE =
    "com.instagram.barcelona.feed.post.ui.rememberPostTextSelectionState (PostTextSelectionMode.kt:"

private const val FUNCTION0 = "Lkotlin/jvm/functions/Function0;"

private val MOVES = EnumSet.of(Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16)

/**
 * The post row's long press override: in [method], the read at [read] of the override the row
 * was handed into [register], the null check at [guard] straight after it, the override's own
 * long press [action] the row reads when there is one, and a register [free] the hook may write
 * in front of [guard].
 */
internal class LongPressOverride(
    val method: MutableMethod,
    val read: Int,
    val guard: Int,
    val register: Int,
    val action: FieldReference,
    val free: Int,
)

/** PostText, [method], and its boolean [parameter] that turns text selection on, copied at [copy] and tested at [check]. */
internal class SelectionFlag(val method: MutableMethod, val parameter: Int, val copy: Int, val check: Int)

/**
 * Gives an opened post's long press back its Copy text sheet, and keeps Threads' word selection
 * off, while the Copy text switch is on (#9).
 *
 * Threads lays out a post's text in one lambda of its post row, and its link preview in another,
 * and each one's long press opens Copy text. The row can be handed an override for that press, and
 * an opened post hands it one with no action in it, so each lambda puts a press that does nothing
 * in its place. Each reads the override and checks it for null; the override goes through the
 * extension in between, along with the action in it, and comes back null when the extension wants
 * the lambda's own press. PostText takes a boolean that turns on Threads' text selection, which
 * some accounts get on opened posts, and the extension is asked for it first thing.
 *
 * Part of every build, with no name to deselect it by: the settings patch, which every HushThreads
 * patch depends on, depends on this one, and its switch is the settings entry's own. It refuses
 * rather than guess, which stops the run, when no lambda reads an override, the lambdas read
 * different ones, a check is reached other than from its read, or PostText's selection check
 * doesn't read a copy of one boolean parameter as it came in.
 */
internal val copyTextPatch = bytecodePatch {
    dependsOn(threadsExtensionPatch)

    execute {
        // Everything is found before anything changes, so a refusal leaves Threads as it was.
        val overrides = longPressOverrides()
        val selection = selectionFlag()

        // From the bottom of each method up, so a hook never moves a check still to come.
        overrides.sortedByDescending { it.guard }.forEach { override ->
            val held = "v${override.register}"
            val free = "v${override.free}"
            override.method.addInstructionsWithLabels(
                override.guard,
                """
                    if-eqz $held, :threads
                    iget-object $free, $held, ${override.action}
                    invoke-static { $held, $free }, $LONG_PRESS
                    move-result-object $held
                    check-cast $held, ${override.action.definingClass}
                """,
                ExternalLabel("threads", override.method.getInstruction(override.guard)),
            )
        }

        val flag = selection.method.parameterRegister(selection.parameter)
        selection.method.addInstructions(
            0,
            """
                invoke-static/range { $flag .. $flag }, $SELECTION
                move-result $flag
            """,
        )
    }
}

/** A method's full signature. A Method is a MethodReference too. */
private fun Method.signature() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.isNote(note: String): Boolean =
    (opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO) &&
        getReference<StringReference>()?.string?.startsWith(note) == true

/** Every method outside the extension holding a string that starts with [note], read through its mutable copy. */
private fun BytecodePatchContext.noted(note: String): List<MutableMethod> =
    classDefByStrings(note, StringComparisonType.STARTS_WITH)
        .filterNot { it.type.startsWith(EXTENSION_ROOT) }
        .flatMap { it.methods }
        .distinctBy { it.signature() }
        .filter { method -> method.body().any { it.isNote(note) } }
        // Read through the mutable copy, so what's checked is what the hook goes into.
        .map { mutableClassDefBy(it.definingClass).findMutableMethodOf(it) }

/**
 * Every read and null check of a long press override in the post row's lambdas: on 451 the
 * post's text and its link preview each have one. Throws unless there's at least one, they all
 * read the same override's same action, and each check is reached only from its read.
 */
internal fun BytecodePatchContext.longPressOverrides(): List<LongPressOverride> {
    val sites = noted(POST_ROW_NOTE).flatMap { method ->
        val flow = ControlFlow.of(method)
        flow.instructions.indices.filter { read -> method.overrideAction(flow, read) != null }.map { method to it }
    }
    if (sites.isEmpty()) throw PatchException("$PATCH: no lambda of the post row reads and checks a long press override")
    val found = sites.map { (method, read) -> method.overrideAt(read) }
    val actions = found.map { it.action.toString() }.distinct()
    if (actions.size != 1) throw PatchException("$PATCH: the post row's override checks read different actions, $actions")
    return found
}

/** The override read at [read] and its check. Throws unless the read is the only way to the check. */
private fun MutableMethod.overrideAt(read: Int): LongPressOverride {
    val method = this
    val flow = ControlFlow.of(method)
    val guard = read + 1
    val register = (flow.instructions[read] as TwoRegisterInstruction).registerA
    val into = flow.instructions.indices.filter { from -> guard in flow.normal[from] || guard in flow.exceptional[from] }
    if (into != listOf(read)) {
        throw PatchException("$PATCH: ${method.signature()} reaches its override check at $guard from $into, not only " +
            "from the read at $read")
    }
    if (register > 15) {
        throw PatchException("$PATCH: ${method.signature()} holds its override in v$register, which the call can't name")
    }
    val action = method.overrideAction(flow, read)!!
    val free = method.freeLocalsAt(PATCH, guard, 1).single()
    return LongPressOverride(method, read, guard, register, action, free)
}

/**
 * The override's long press, when [read] is the row's read of an override of its own: an
 * `iget-object` of a field of the row's class, an `if-nez` on it straight after, and where that
 * jumps, within a few instructions, a read of the override's `Function0` field and an `if-nez` on
 * that. Null for anything else.
 */
private fun Method.overrideAction(flow: ControlFlow, read: Int): FieldReference? {
    val body = flow.instructions
    if (read + 1 >= body.size || body[read].opcode != Opcode.IGET_OBJECT || body[read + 1].opcode != Opcode.IF_NEZ) return null
    val field = body[read].getReference<FieldReference>() ?: return null
    val register = (body[read] as TwoRegisterInstruction).registerA
    if (field.definingClass != definingClass || (body[read + 1] as OneRegisterInstruction).registerA != register) return null
    val target = flow.normal[read + 1].singleOrNull { it != read + 2 } ?: return null
    for (at in target until minOf(target + 4, body.size - 1)) {
        val instruction = body[at]
        if (instruction.opcode != Opcode.IGET_OBJECT) continue
        val action = instruction.getReference<FieldReference>() ?: continue
        val get = instruction as TwoRegisterInstruction
        if (get.registerB != register || action.definingClass != field.type || action.type != FUNCTION0) continue
        val next = body[at + 1]
        return action.takeIf { next.opcode == Opcode.IF_NEZ && (next as OneRegisterInstruction).registerA == get.registerA }
    }
    return null
}

/**
 * PostText's selection flag: the first `if-eqz` after [SELECTION_NOTE] in the one method holding
 * it, which tests a boolean parameter or one copy of one. Throws unless the copy reads the
 * parameter as it came in.
 */
internal fun BytecodePatchContext.selectionFlag(): SelectionFlag {
    val method = noted(SELECTION_NOTE).singleOrPatchException("$PATCH: PostText, the method holding \"$SELECTION_NOTE\"")
    val body = method.body()
    val note = body.indexOfFirst { it.isNote(SELECTION_NOTE) }
    val check = (note + 1 until body.size).firstOrNull { body[it].opcode == Opcode.IF_EQZ }
        ?: throw PatchException("$PATCH: ${method.signature()} checks nothing after its selection note")
    val register = (body[check] as OneRegisterInstruction).registerA
    fun parameterIn(held: Int) = method.parameterTypes.indices.firstOrNull { method.parameterRegisterNumber(it) == held }
    val copy = if (register >= method.localRegisterCount()) {
        check
    } else {
        val copies = (0 until check).filter { at ->
            body[at].opcode in MOVES && (body[at] as TwoRegisterInstruction).registerA == register &&
                check in method.literalReads(at)
        }
        copies.singleOrNull() ?: throw PatchException("$PATCH: ${method.signature()} fills v$register for its " +
            "selection check at $check from ${copies.size} copies, not one")
    }
    val source = if (copy == check) register else (body[copy] as TwoRegisterInstruction).registerB
    val parameter = parameterIn(source)
        ?: throw PatchException("$PATCH: the selection check at $check in ${method.signature()} reads v$source, " +
            "which isn't a parameter")
    if (method.parameterTypes[parameter].toString() != "Z") {
        throw PatchException("$PATCH: the selection check at $check in ${method.signature()} reads parameter " +
            "$parameter, a ${method.parameterTypes[parameter]}, not a boolean")
    }
    // The hook goes in first thing, so what the check reads has to be the parameter as it came in.
    method.requireParameterIntact(PATCH, parameter, listOf(copy))
    return SelectionFlag(method, parameter, copy, check)
}
