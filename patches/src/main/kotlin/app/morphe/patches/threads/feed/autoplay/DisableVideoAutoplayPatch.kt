/*
 * Copyright 2026 HushThreads contributors
 * https://github.com/SysAdminDoc/HushThreads
 *
 * Found by reading 449 and 448 and by a method trace of 449 on an emulator (2026-10-02), and
 * carried to 450 by reading its reshaped PostVideo and playback effect (2026-10-06). The Instagram
 * post, trend preview and ad card calls and PostVideo's default mask were read from 450 (2026-10-10).
 */
package app.morphe.patches.threads.feed.autoplay

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.threads.feed.reaching
import app.morphe.patches.threads.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.threads.misc.extension.enableStatus
import app.morphe.patches.threads.misc.extension.requireStatusMethod
import app.morphe.patches.threads.misc.extension.threadsExtensionPatch
import app.morphe.patches.threads.misc.settings.settingsPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.argumentRegister
import app.morphe.util.findMutableMethodOf
import app.morphe.util.getReference
import app.morphe.util.readsAfter
import app.morphe.util.singleOrPatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Disable video autoplay"
internal const val VIDEO_AUTOPLAY = "$EXTENSION_PACKAGE/feed/VideoAutoplay;"

/** Compose's note naming Threads' video-in-a-post composable. It names the source file, so it survives Redex. */
internal const val POST_VIDEO = "com.instagram.barcelona.feed.post.video.PostVideo (PostVideo.kt:"

/** The same note for the effect that starts and releases a post video's player. */
internal const val PLAYBACK_EFFECT = "com.instagram.video.player.compose.VideoPlaybackEffect (VideoPlaybackEffect.kt:"

/** Notes in the feed post composables that put a video in a post: one on its own, and one in a carousel. */
internal const val POST_SINGLE_MEDIA = "com.instagram.barcelona.feed.post.ui.PostSingleMedia"
internal const val POST_CAROUSEL = "com.instagram.barcelona.feed.post.ui.PostCarousel"

/**
 * Notes in the composables that show a video inside a post rather than as its own media: an
 * Instagram post shown in a thread, a trend's preview and an ad card.
 */
internal const val INLINE_IG_VIDEO = "com.instagram.barcelona.igmedia.InlineIgVideo (InlineIgVideo.kt:"
internal const val TREND_PREVIEW_VIDEO =
    "com.instagram.barcelona.common.ui.mediahighlights.AutoplayingMediaHighlightVideo (TrendMediaHighlightsPreview.kt:"
internal const val AD_CARD = "com.instagram.barcelona.sponsored.ui.AdCard (AdCard.kt:"

/** Every composable whose calls to PostVideo the hook holds. */
internal val VIDEO_CALLERS = listOf(POST_SINGLE_MEDIA, POST_CAROUSEL, INLINE_IG_VIDEO, TREND_PREVIEW_VIDEO, AD_CARD)

/**
 * Videos in feed posts wait for a tap.
 *
 * A video in a post is Threads' PostVideo composable. One of its booleans says whether this is the
 * video Threads picked to play; PostVideo plays it only when that's true and the player is ready,
 * hands the answer to VideoPlaybackEffect, which starts or releases the player, and only builds the
 * player's surface for the picked video. The posts in a feed, a profile or a thread pass that flag
 * from PostSingleMedia and PostCarousel, and the hook sits on it at those calls. So does an
 * Instagram post shown in a thread. Trend previews and ad cards call PostVideo themselves too, but
 * leave the flag to PostVideo's default, which plays: Compose's default mask has the flag's bit set,
 * so PostVideo throws away what they pass. At those calls the hook clears that bit and hands
 * PostVideo the extension's answer about the default instead. The full-screen viewer a tap opens
 * calls PostVideo from its own composable, also leaving the flag to the default, and isn't touched,
 * so it plays as it always has. PostVideo itself isn't touched either, since the viewer goes
 * through it.
 *
 * Instagram's own autoplay check, which brosssh/morphe-patches turns off for Instagram, is still in
 * Threads but isn't on this path: a trace of 449 showed a feed video starting with it answering
 * that autoplay was off. Threads' video prefetcher still reads it.
 */
@Suppress("unused")
val disableVideoAutoplayPatch = bytecodePatch(
    name = PATCH,
    description = "Videos in your feed wait for a tap instead of playing as you scroll. Good for a calmer feed and " +
        "less data use. Starts off. Turn it on in HushThreads settings > Feed.",
) {
    category("Feed")
    dependsOn(settingsPatch)
    dependsOn(threadsExtensionPatch)
    compatibleWith(*AppCompatibilities.threads())

    execute {
        requireStatusMethod("disableVideoAutoplay")
        val effect = composable(PLAYBACK_EFFECT, "the video playback effect").also { it.requirePlaybackEffect() }
        val postVideo = composable(POST_VIDEO, "PostVideo")
        val shape = postVideo.playTest(effect)
        val default = postVideo.playDefault(shape)
        val sites = VIDEO_CALLERS.flatMap { note ->
            playSites(postVideo, note, shape.parameter, default).ifEmpty { throw PatchException("$PATCH: no call to PostVideo holds \"$note\"") }
        }
        sites.forEach { site ->
            // A caller that leaves the flag to PostVideo's default gets the bit cleared and the default's value to ask about.
            val defaulted = site.defaulted?.let {
                """
                    const v${it.register}, ${it.mask}
                    const/16 v${site.register}, ${it.value}
                """
            }.orEmpty()
            mutableClassDefBy(site.method.definingClass).findMutableMethodOf(site.method).addInstructionsAtControlFlowLabel(
                site.call,
                defaulted + """
                    invoke-static/range { v${site.register} .. v${site.register} }, $VIDEO_AUTOPLAY->play(Z)Z
                    move-result v${site.register}
                """,
            )
        }
        enableStatus("disableVideoAutoplay")
    }
}

/**
 * A call to PostVideo, the register carrying whether its video is the one to play, and what to
 * change when the caller leaves that flag to PostVideo's default.
 */
internal data class PlaySite(val method: Method, val call: Int, val register: Int, val defaulted: DefaultedFlag? = null)

/** The caller's default mask [register], the [mask] to hand PostVideo there without the flag's bit, and the [value] PostVideo would've played by. */
internal data class DefaultedFlag(val register: Int, val mask: Int, val value: Int)

/** PostVideo's default for its play flag: when a caller sets [bit] in its [mask] parameter, PostVideo plays by [value] instead of the flag it's handed. */
internal data class PlayDefault(val mask: Int, val bit: Int, val value: Int)

internal fun Method.holdsNote(note: String): Boolean = implementation?.instructions?.any {
    it.getReference<StringReference>()?.string?.startsWith(note) == true
} == true

/**
 * The effect takes the composer, the player's state and the video, then Compose's ints (450 adds a
 * volume float among them), and ends on its booleans: two in 450, where 449 and 448 had three.
 * Whether to play is the first boolean, and its index comes back.
 */
internal fun Method.requirePlaybackEffect(): Int {
    val types = parameterTypes.map { it.toString() }
    val play = types.indexOf("Z")
    val middle = if (play > 3) types.subList(3, play) else emptyList()
    if (!AccessFlags.STATIC.isSet(accessFlags) || returnType != "V" || types.take(3).any { !it.startsWith("L") } ||
        "I" !in middle || middle.any { it != "I" && it != "F" } || types.drop(play).any { it != "Z" } || types.size - play !in 2..3
    ) throw PatchException("$PATCH: the video playback effect $definingClass->$name${types.joinToString("", "(", ")")}$returnType has another shape")
    return play
}

/**
 * Which of PostVideo's parameters says whether its video plays: the fourth boolean in 450, which
 * dropped one ahead of it. It's the boolean PostVideo tests last before its
 * one call to the effect, on a branch whose false side writes a 0 as the effect's play argument
 * before the call, and every write to that argument between the test and the call must be a 0 or a
 * 1, with a 1 among them. The tested register has to hold that one boolean on every path to the
 * test, as it arrived or through one move, or the default Compose gives it when a caller leaves it
 * out.
 */
internal fun Method.playParameter(effect: Method): Int = playTest(effect).parameter

/** PostVideo's test of its play flag at [test], the flag's [parameter] index, and the [call] to the effect it sets [argument] for. */
internal data class PlayTest(val test: Int, val call: Int, val argument: Int, val parameter: Int)

internal fun Method.playTest(effect: Method): PlayTest {
    if (!AccessFlags.STATIC.isSet(accessFlags)) throw PatchException("$PATCH: PostVideo $definingClass->$name isn't static")
    val types = parameterTypes.map { it.toString() }
    val body = implementation!!.instructions.toList()
    val call = body.indices.filter { body[it].calls(effect) }.singleOrPatchException("$PATCH: PostVideo's call to the video playback effect")
    val playArgument = body[call].argumentRegister(effect.registerOffset(effect.requirePlaybackEffect()))!!
    val first = implementation!!.registerCount - parameterRegisters()
    // Each boolean parameter, by the register it arrives in.
    val booleans = types.indices.filter { types[it] == "Z" }.associateBy { first + registerOffset(it) }
    // The boolean [register] holds when the instruction at [at] runs, the same one on every path.
    fun held(at: Int, register: Int): Int? {
        val writes = reaching(at, setOf(register))
        val sources = writes.writes.mapNotNull { write ->
            val instruction = body[write]
            // Compose's default for the parameter, set when a caller leaves it out.
            if (instruction is NarrowLiteralInstruction && instruction.narrowLiteral in 0..1) return@mapNotNull null
            val source = (instruction as? TwoRegisterInstruction)?.registerB?.takeIf { instruction.opcode.isMove() } ?: return null
            val arrived = reaching(write, setOf(source))
            if (arrived.writes.isNotEmpty() || !arrived.fromEntry) return null
            booleans[source] ?: return null
        }
        val own = if (writes.fromEntry) listOf(booleans[register] ?: return null) else emptyList()
        return (sources + own).distinct().singleOrNull()
    }
    val address = body.runningFold(0) { at, instruction -> at + instruction.codeUnits }
    // Where a branch at [at] goes when its boolean is false, if that's between it and the call.
    fun falseSide(at: Int): Int? = when (body[at].opcode) {
        Opcode.IF_EQZ -> address.indexOf(address[at] + (body[at] as OffsetInstruction).codeOffset)
        Opcode.IF_NEZ -> at + 1
        else -> null
    }?.takeIf { it in at + 1 until call }
    var play: Int? = null
    val test = (call - 1 downTo 0).firstOrNull { at ->
        val side = falseSide(at) ?: return@firstOrNull false
        (body[side] as? OneRegisterInstruction)?.registerA == playArgument && body[side].opcode.setsRegister() &&
            held(at, (body[at] as OneRegisterInstruction).registerA).also { play = it } != null
    } ?: throw PatchException("$PATCH: PostVideo never tests one of its booleans before the playback effect")
    val writes = (test + 1 until call).map { body[it] }.filter { (it as? OneRegisterInstruction)?.registerA == playArgument && it.opcode.setsRegister() }
    if (writes.any { it !is NarrowLiteralInstruction || it.narrowLiteral !in 0..1 }) {
        throw PatchException("$PATCH: PostVideo's play argument to the playback effect isn't set from the boolean it tests")
    }
    if ((body[falseSide(test)!!] as NarrowLiteralInstruction).narrowLiteral != 0 || writes.none { (it as NarrowLiteralInstruction).narrowLiteral == 1 }) {
        throw PatchException("$PATCH: PostVideo's play argument to the playback effect doesn't follow the boolean it tests")
    }
    return PlayTest(test, call, playArgument, play!!)
}

/**
 * Where PostVideo sets its play flag to Compose's default: the 0 or 1 it writes into the register
 * it tests, behind an if-eqz on one bit of an int parameter, the caller's default mask. Null when
 * PostVideo has no default for the flag, so every caller passes its own.
 */
internal fun Method.playDefault(shape: PlayTest): PlayDefault? {
    val body = implementation!!.instructions.toList()
    val tested = (body[shape.test] as OneRegisterInstruction).registerA
    val write = reaching(shape.test, setOf(tested)).writes.filter { body[it] is NarrowLiteralInstruction }.ifEmpty { return null }
        .singleOrPatchException("$PATCH: PostVideo's default for its play flag")
    val address = body.runningFold(0) { at, instruction -> at + instruction.codeUnits }
    val guard = body.getOrNull(write - 1)
    if (guard?.opcode != Opcode.IF_EQZ || address[write - 1] + (guard as OffsetInstruction).codeOffset != address[write + 1]) {
        throw PatchException("$PATCH: PostVideo's default for its play flag isn't behind a test of its default mask")
    }
    val (tests, bitRegister) = origin(write - 1, (guard as OneRegisterInstruction).registerA)
        ?: throw PatchException("$PATCH: PostVideo tests its play flag's default on a value that differs by path")
    val and = body.getOrNull(tests)
    val bit = (and as? NarrowLiteralInstruction)?.narrowLiteral
    if ((and?.opcode != Opcode.AND_INT_LIT16 && and?.opcode != Opcode.AND_INT_LIT8) || bit == null || bit.countOneBits() != 1) {
        throw PatchException("$PATCH: PostVideo's default for its play flag isn't behind one bit of v$bitRegister")
    }
    val (from, mask) = origin(tests, (and as TwoRegisterInstruction).registerB)
        ?: throw PatchException("$PATCH: PostVideo's default mask differs by path")
    val first = implementation!!.registerCount - parameterRegisters()
    val parameter = parameterTypes.indices.takeIf { from < 0 }
        ?.singleOrNull { parameterTypes[it].toString() == "I" && first + registerOffset(it) == mask }
        ?: throw PatchException("$PATCH: PostVideo's default mask isn't one of its int parameters")
    return PlayDefault(parameter, bit, (body[write] as NarrowLiteralInstruction).narrowLiteral)
}

/**
 * The instruction whose value [register] holds when the one at [at] runs, the same one on every
 * path, followed back through moves: its index and the register it wrote, or -1 and the register
 * as the method got it. Null when paths disagree.
 */
private fun Method.origin(at: Int, register: Int): Pair<Int, Int>? {
    val body = implementation!!.instructions.toList()
    var index = at
    var held = register
    while (true) {
        val reaching = reaching(index, setOf(held))
        if (reaching.writes.isEmpty()) return if (reaching.fromEntry) -1 to held else null
        val write = reaching.writes.singleOrNull()?.takeUnless { reaching.fromEntry } ?: return null
        if (!body[write].opcode.isMove()) return write to held
        index = write
        held = (body[write] as TwoRegisterInstruction).registerB
    }
}

/**
 * Every call to PostVideo from a method holding [note], with the register of its [play] argument.
 * A call whose default mask, which has to be one constant, leaves the flag to PostVideo's
 * [default] comes with that mask's register too. The call must be the last thing to read those
 * registers, since the hook leaves its own values there.
 */
private fun BytecodePatchContext.playSites(postVideo: Method, note: String, play: Int, default: PlayDefault?): List<PlaySite> =
    classDefByStrings(note, StringComparisonType.STARTS_WITH).flatMap { it.methods }
        .filter { it.holdsNote(note) }
        .distinctBy { "${it.definingClass}->${it.name}${it.parameterTypes.joinToString("")}${it.returnType}" }
        .flatMap { method ->
            val body = method.implementation!!.instructions.toList()
            body.indices.filter { body[it].calls(postVideo) }.map { call ->
                val register = body[call].argumentRegister(postVideo.registerOffset(play))!!
                val defaulted = default?.let { flag ->
                    val maskRegister = body[call].argumentRegister(postVideo.registerOffset(flag.mask))!!
                    val mask = method.origin(call, maskRegister)?.first?.let { body.getOrNull(it) }
                        ?.takeIf { it.opcode in CONSTANTS }?.let { (it as NarrowLiteralInstruction).narrowLiteral }
                        ?: throw PatchException("$PATCH: ${method.definingClass}->${method.name} hands PostVideo a default mask in v$maskRegister that isn't one constant")
                    DefaultedFlag(maskRegister, mask and flag.bit.inv(), flag.value).takeIf { mask and flag.bit != 0 }
                }
                for (written in listOfNotNull(register, defaulted?.register)) {
                    val later = method.readsAfter(call, written)
                    if (later.isNotEmpty()) throw PatchException("$PATCH: ${method.definingClass}->${method.name} reads v$written again after PostVideo, at $later")
                }
                if (register > 255) throw PatchException("$PATCH: ${method.definingClass}->${method.name} passes the play flag in v$register, past move-result's reach")
                if ((defaulted?.register ?: 0) > 255) throw PatchException("$PATCH: ${method.definingClass}->${method.name} passes its default mask in v${defaulted!!.register}, past const's reach")
                PlaySite(method, call, register, defaulted)
            }
        }

/** The instructions that load an int constant. */
private val CONSTANTS = setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST, Opcode.CONST_HIGH16)

private fun Opcode.isMove() = this == Opcode.MOVE || this == Opcode.MOVE_FROM16 || this == Opcode.MOVE_16

/** Registers the parameters take, `this` included. */
private fun Method.parameterRegisters(): Int =
    (if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1) + parameterTypes.sumOf { if (it.isWide()) 2 else 1 }

/** How far into a static call's arguments the [index]th parameter sits, counting wide ones twice. */
private fun Method.registerOffset(index: Int): Int = parameterTypes.take(index).sumOf { if (it.isWide()) 2 else 1 }

private fun CharSequence.isWide() = this == "J" || this == "D"

private fun Instruction.calls(method: Method): Boolean =
    (opcode == Opcode.INVOKE_STATIC || opcode == Opcode.INVOKE_STATIC_RANGE) && getReference<MethodReference>()?.let {
        it.definingClass == method.definingClass && it.name == method.name && it.returnType == method.returnType &&
            it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
    } == true

private fun BytecodePatchContext.composable(note: String, label: String): MutableMethod {
    val method = classDefByStrings(note, StringComparisonType.STARTS_WITH).flatMap { it.methods }
        .filter { it.holdsNote(note) }
        .distinctBy { "${it.definingClass}->${it.name}${it.parameterTypes.joinToString("")}${it.returnType}" }
        .singleOrPatchException("$PATCH: $label, the method holding \"$note\"")
    return mutableClassDefBy(method.definingClass).findMutableMethodOf(method)
}
