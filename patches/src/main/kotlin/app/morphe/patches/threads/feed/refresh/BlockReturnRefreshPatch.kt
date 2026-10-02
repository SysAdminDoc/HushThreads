/*
 * Copyright 2026 HushThreads contributors
 * https://github.com/SysAdminDoc/HushThreads
 *
 * Built on SysAdminDoc/Hushfacebook (GPL-3.0), whose patch of the same name answers Facebook's
 * return checks. Threads' checks were found by reading 449 and 448 (2026-10-02).
 */
package app.morphe.patches.threads.feed.refresh

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.threads.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.threads.misc.extension.enableStatus
import app.morphe.patches.threads.misc.extension.localRegisterCount
import app.morphe.patches.threads.misc.extension.requireStatusMethod
import app.morphe.patches.threads.misc.extension.threadsExtensionPatch
import app.morphe.patches.threads.misc.settings.settingsPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findMutableMethodOf
import app.morphe.util.getReference
import app.morphe.util.singleOrPatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Block background-return feed refresh"
internal const val RETURN_REFRESH = "$EXTENSION_PACKAGE/feed/ReturnRefresh;"

/** The activity every Threads screen lives in. A kept class: its name survives Redex. */
internal const val BARCELONA_ACTIVITY = "Lcom/instagram/barcelona/mainactivity/BarcelonaActivity;"

/** What the warm-start check logs when the time away is under Threads' threshold. */
internal const val TOO_SHORT = "background_time_too_short_for_hot_start_feed_refresh"

/** What the warm-start check logs first, the time away by the wall clock. */
internal const val WALL_CLOCK = "hot_start_wall_clock_bg_elapsed_ms"

/** What the reset to main feed logs when it resets, and the key it logs its answer under. */
internal const val RESET_TO_MAIN_FEED = "RESET_TO_MAIN_FEED"
internal const val RESET_TO_HOME_FEED = "reset_to_home_feed"

/** What BarcelonaActivity logs as it handles the hot-start decision. */
internal const val BADGE_DECISION = "badge_decision"

/**
 * Keeps the feed where it was after a short trip out of Threads.
 *
 * Coming back, Threads makes four calls. BarcelonaActivity's onStart asks a static method for a
 * hot-start decision, which can refresh For you in the background or badge the home tab, and asks
 * whether to reset to the main feed. The feed screen's warm-start check then compares the time away
 * with a server threshold and, past it, clears the cache, reloads every feed and scrolls to the top.
 * When it skips that, For you compares the time away with the same threshold again and, past it,
 * swaps in the posts it fetched while Threads was in the background. Each asks the extension, which
 * gives every check of one return the same answer: the hot-start decision answers null, its own
 * answer when no stop time was recorded; the reset answers false; the warm-start check stores false,
 * its own skip; the swap sees false, as after a short trip. Pull to refresh, a cold start and
 * returns from one Threads screen to another never reach these. Hushfacebook's ten-minute limit and
 * No time limit switch carry over.
 */
@Suppress("unused")
val blockReturnRefreshPatch = bytecodePatch(
    name = PATCH,
    description = "Keeps your place in the feed when you come back to Threads within ten minutes, or after any " +
        "time away with No time limit on. Pull to refresh and a fresh launch still load new posts.",
    default = false,
) {
    category("Feed")
    dependsOn(settingsPatch)
    dependsOn(threadsExtensionPatch)
    compatibleWith(*AppCompatibilities.threads())

    execute {
        requireStatusMethod("returnRefresh")
        holdCachedPosts(holdWarmStart())
        holdResetToFeed()
        holdHotStart()
        enableStatus("returnRefresh")
    }
}

internal fun Method.holdsString(value: String): Boolean = implementation?.instructions?.any {
    it.getReference<StringReference>()?.string == value
} == true

private fun BytecodePatchContext.methodsHolding(vararg values: String, where: (Method) -> Boolean): List<Method> =
    classDefByStrings(values.first(), StringComparisonType.EQUALS).flatMap { it.methods }
        .filter { method -> values.all { method.holdsString(it) } && where(method) }
        .distinctBy { "${it.definingClass}->${it.name}${it.parameterTypes.joinToString("")}${it.returnType}" }

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).findMutableMethodOf(method)

/** The warm-start check: the register its refresh answer sits in, and where that answer is stored. */
internal data class WarmStartSite(val register: Int, val store: Int)

/**
 * The warm-start check's answer is a boolean set to 1 when the time away reaches the threshold and
 * to 0 right before it logs [TOO_SHORT], then stored into the decision it hands back. Every other
 * path stores a constant false.
 */
internal fun Method.warmStartSite(): WarmStartSite {
    val body = implementation!!.instructions.toList()
    val logged = body.indices.filter { body[it].getReference<StringReference>()?.string == TOO_SHORT }
        .singleOrPatchException("$PATCH: the warm-start check's \"$TOO_SHORT\" log")
    fun literal(index: Int, value: Int) = body[index].opcode == Opcode.CONST_4 &&
        (body[index] as NarrowLiteralInstruction).narrowLiteral == value
    val zero = (logged - 1 downTo 0).firstOrNull { literal(it, 0) }
        ?: throw PatchException("$PATCH: no false before the warm-start check's \"$TOO_SHORT\" log")
    val register = (body[zero] as OneRegisterInstruction).registerA
    fun writes(index: Int) = body[index].writes(register)
    if ((zero + 1 until logged).any(::writes)) {
        throw PatchException("$PATCH: the warm-start answer v$register is overwritten before \"$TOO_SHORT\" is logged")
    }
    // The true side: set within a few instructions before the false, on the branch that skips it.
    (zero - 1 downTo maxOf(0, zero - 4)).firstOrNull { literal(it, 1) && (body[it] as OneRegisterInstruction).registerA == register }
        ?: throw PatchException("$PATCH: no true answer in v$register before the warm-start check's false")
    val store = (logged + 1 until body.size).firstOrNull {
        body[it].opcode == Opcode.IPUT_BOOLEAN && (body[it] as TwoRegisterInstruction).registerA == register
    } ?: throw PatchException("$PATCH: the warm-start answer v$register is never stored")
    if ((logged + 1 until store).any(::writes)) {
        throw PatchException("$PATCH: the warm-start answer v$register is overwritten before it is stored")
    }
    return WarmStartSite(register, store)
}

private fun Instruction.writes(register: Int) = opcode.setsRegister() && (this as? OneRegisterInstruction)?.registerA.let {
    it == register || opcode.setsWideRegister() && it == register - 1
}

private fun BytecodePatchContext.holdWarmStart(): MutableMethod {
    val check = methodsHolding(TOO_SHORT, WALL_CLOCK) { it.parameterTypes.lastOrNull()?.toString() == "Z" }
        .singleOrPatchException("$PATCH: warm-start check holding \"$TOO_SHORT\" and \"$WALL_CLOCK\"")
        .let { mutable(it) }
    val site = check.warmStartSite()
    check.addInstructionsAtControlFlowLabel(
        site.store,
        """
            invoke-static/range { v${site.register} .. v${site.register} }, $RETURN_REFRESH->warmStart(Z)Z
            move-result v${site.register}
        """,
    )
    return check
}

/** For you's swap to the posts fetched in the background: the register holding its answer, and where that answer joins. */
internal data class CachedPostsSite(val method: Method, val register: Int, val join: Int) {
    override fun toString() = "${method.definingClass}->${method.name} v$register at $join"
}

/** The 64-bit MobileConfig numbers a method loads, the warm-start check's server threshold among them. */
internal fun Method.wideLiterals(): Set<Long> = implementation?.instructions?.filter { it.opcode == Opcode.CONST_WIDE }
    ?.map { (it as WideLiteralInstruction).wideLiteral }?.toSet().orEmpty()

/**
 * After the warm-start check skips its reload, For you compares the time away with the same server
 * threshold: it loads a MobileConfig number from [thresholds], then cmp-long, a true, an if-gez over
 * a false that falls straight into the join, and an if-eqz on that answer a few instructions on,
 * which guards the swap. The warm-start check's own comparison branches past its log instead.
 */
internal fun Method.cachedPostsSites(thresholds: Set<Long>): List<CachedPostsSite> {
    val body = implementation?.instructions?.toList() ?: return emptyList()
    val address = IntArray(body.size + 1)
    for (index in body.indices) address[index + 1] = address[index] + body[index].codeUnits
    fun literal(index: Int, value: Int) = body[index].opcode == Opcode.CONST_4 &&
        (body[index] as NarrowLiteralInstruction).narrowLiteral == value
    fun register(index: Int) = (body[index] as OneRegisterInstruction).registerA
    return (3 until body.size - 1).mapNotNull { zero ->
        val branch = zero - 1
        if (!literal(zero, 0) || body[branch].opcode != Opcode.IF_GEZ || !literal(zero - 2, 1) ||
            body[zero - 3].opcode != Opcode.CMP_LONG
        ) return@mapNotNull null
        val answer = register(zero)
        val join = zero + 1
        if (register(zero - 2) != answer ||
            address[branch] + (body[branch] as OffsetInstruction).codeOffset != address[join]
        ) return@mapNotNull null
        val loadsThreshold = (maxOf(0, zero - 11) until zero - 3).any {
            body[it].opcode == Opcode.CONST_WIDE && (body[it] as WideLiteralInstruction).wideLiteral in thresholds
        }
        val guard = (join until minOf(body.size, join + 6)).firstOrNull {
            body[it].opcode == Opcode.IF_EQZ && register(it) == answer
        }
        if (!loadsThreshold || guard == null || (join until guard).any { body[it].writes(answer) }) return@mapNotNull null
        CachedPostsSite(this, answer, join)
    }
}

/**
 * 449 makes this comparison in the warm-start check's sibling that handles a skipped reload, and 448
 * later in the warm-start check itself. Either way it sits in the warm-start check's class.
 */
private fun BytecodePatchContext.holdCachedPosts(warm: MutableMethod) {
    val thresholds = warm.wideLiterals()
    val site = mutableClassDefBy(warm.definingClass).methods.flatMap { it.cachedPostsSites(thresholds) }
        .singleOrPatchException("$PATCH: For you's swap to posts fetched in the background, past the warm-start threshold")
    (site.method as MutableMethod).addInstructionsAtControlFlowLabel(
        site.join,
        """
            invoke-static/range { v${site.register} .. v${site.register} }, $RETURN_REFRESH->cachedPosts(Z)Z
            move-result v${site.register}
        """,
    )
}

private fun BytecodePatchContext.holdResetToFeed() {
    val reset = methodsHolding(RESET_TO_MAIN_FEED, RESET_TO_HOME_FEED) {
        it.definingClass == BARCELONA_ACTIVITY && it.returnType == "Z"
    }.singleOrPatchException("$PATCH: BarcelonaActivity's reset to main feed holding \"$RESET_TO_MAIN_FEED\"")
        .let { mutable(it) }
    val body = reset.implementation!!.instructions.toList()
    val answer = body.indices.filter { body[it].opcode == Opcode.RETURN }
        .singleOrPatchException("$PATCH: the reset to main feed's return")
    val register = (body[answer] as OneRegisterInstruction).registerA
    reset.addInstructionsAtControlFlowLabel(
        answer,
        """
            invoke-static/range { v$register .. v$register }, $RETURN_REFRESH->resetToFeed(Z)Z
            move-result v$register
        """,
    )
}

/**
 * The hot-start decision has no string of its own. BarcelonaActivity's handler of it is the one
 * method holding [BADGE_DECISION], and onStart makes the decision with one static call answering
 * the handler's parameter type, from the session's helper, the last surface and two times.
 */
private fun BytecodePatchContext.holdHotStart() {
    val handler = methodsHolding(BADGE_DECISION) {
        it.definingClass == BARCELONA_ACTIVITY && it.returnType == "V" && it.parameterTypes.size == 1
    }.singleOrPatchException("$PATCH: BarcelonaActivity's hot-start handler holding \"$BADGE_DECISION\"")
    val decisionType = handler.parameterTypes.single().toString()
    val onStart = mutableClassDefBy(BARCELONA_ACTIVITY).methods.filter { it.name == "onStart" && it.parameterTypes.isEmpty() }
        .singleOrPatchException("$PATCH: BarcelonaActivity.onStart")
    val decision = onStart.implementation!!.instructions.mapNotNull { instruction ->
        if (instruction.opcode != Opcode.INVOKE_STATIC && instruction.opcode != Opcode.INVOKE_STATIC_RANGE) return@mapNotNull null
        instruction.getReference<MethodReference>()?.takeIf {
            it.returnType == decisionType &&
                it.parameterTypes.map(CharSequence::toString).let { types ->
                    types.size == 4 && types[0].startsWith("L") && types[1] == "Ljava/lang/String;" && types[2] == "J" && types[3] == "J"
                }
        }
    }.distinctBy { it.toString() }.singleOrPatchException("$PATCH: onStart's static hot-start decision answering $decisionType")
    val method = mutableClassDefBy(decision.definingClass).findMutableMethodOf(decision)
    if (!AccessFlags.STATIC.isSet(method.accessFlags) || method.localRegisterCount() < 1) {
        throw PatchException("$PATCH: the hot-start decision $decision has no free local register at entry")
    }
    method.addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $RETURN_REFRESH->holdHotStart()Z
            move-result v0
            if-eqz v0, :decide
            const/4 v0, 0x0
            return-object v0
        """,
        ExternalLabel("decide", method.getInstruction(0)),
    )
}
