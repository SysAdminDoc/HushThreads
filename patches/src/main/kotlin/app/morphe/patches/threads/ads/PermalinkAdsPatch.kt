/*
 * Copyright 2026 HushThreads contributors
 * https://github.com/SysAdminDoc/HushThreads
 *
 * Built on SysAdminDoc/Hushfacebook (GPL-3.0).
 */
package app.morphe.patches.threads.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patches.threads.misc.extension.threadsExtensionPatch
import app.morphe.util.ControlFlow
import app.morphe.util.getReference
import app.morphe.util.singleOrPatchException
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Hide ads"

/** Fetches the ads Threads shows among an opened post's replies. Its name survives Redex. */
internal const val PERMALINK_AD_FETCHER = "Lcom/instagram/barcelona/sponsored/permalink/ThreadsPermalinkAdFetcher;"

/** The endpoint the fetcher asks for an opened post's ads. */
internal const val PERMALINK_ADS_PATH = "text_feed/threads_permalink_ads/"

/** The fetcher's one suspend method: it asks the endpoint and answers the ads it read, or a failure. */
internal object PermalinkAdFetchFingerprint : Fingerprint(
    definingClass = PERMALINK_AD_FETCHER,
    returnType = "Ljava/lang/Object;",
    filters = listOf(string(PERMALINK_ADS_PATH)),
)

/** Where the fetcher hands its ads on: the result it builds at [index] from the list in [list]. */
internal data class PermalinkAdResult(val index: Int, val list: Int, val result: String)

/**
 * Takes the ads out from under an opened post. Hide ads depends on it.
 *
 * An opened post fetches its own ads, apart from the feed, and shows them among its replies. The
 * fetcher reads the server's answer into a list of ads and returns them wrapped in a small result,
 * with how many the server sent. The list goes to the extension first and the result is built from
 * what comes back, which is empty while Hide ads is on. Threads' own code already handles an empty
 * answer, the one it gets when the server has no ad for a post.
 *
 * Read from 452 (2026-10-22): the result's constructor takes the list and a count, and its only
 * reader, the post's ad use case, copies the list and emits it.
 */
internal val permalinkAdsPatch = bytecodePatch {
    dependsOn(threadsExtensionPatch)
    execute {
        val fetch = PermalinkAdFetchFingerprint.method
        val at = fetch.permalinkAdResult()
        fetch.addInstructions(
            at.index,
            """
                invoke-static/range { v${at.list} .. v${at.list} }, $FEED_ADS->permalinkAds(Ljava/util/List;)Ljava/util/List;
                move-result-object v${at.list}
            """,
        )
    }
}

/**
 * The fetcher's success result: a new-instance whose constructor takes a List and an int, returned
 * straight after. Throws unless there's exactly one, and unless nothing but the instruction before it
 * leads there, since a jump into the new-instance would skip the hook put in front of it.
 */
internal fun Method.permalinkAdResult(): PermalinkAdResult {
    val flow = ControlFlow.of(this)
    val body = flow.instructions
    val sites = body.indices.filter { index ->
        val init = body[index].getReference<MethodReference>() ?: return@filter false
        val instance = body.getOrNull(index - 1) ?: return@filter false
        val back = body.getOrNull(index + 1) ?: return@filter false
        body[index].opcode == Opcode.INVOKE_DIRECT && init.name == "<init>" && init.returnType == "V" &&
            init.parameterTypes.map(CharSequence::toString) == listOf("Ljava/util/List;", "I") &&
            instance.opcode == Opcode.NEW_INSTANCE &&
            instance.getReference<TypeReference>()?.type == init.definingClass &&
            (instance as OneRegisterInstruction).registerA == (body[index] as FiveRegisterInstruction).registerC &&
            back.opcode == Opcode.RETURN_OBJECT && (back as OneRegisterInstruction).registerA == instance.registerA
    }
    val init = sites.singleOrPatchException("$PATCH: the opened post ad fetcher's result built from a List and returned")
    val instance = init - 1
    val into = Array(body.size) { mutableListOf<Int>() }
    for (from in body.indices) (flow.normal[from] + flow.exceptional[from]).forEach { into[it] += from }
    if (into[instance] != listOf(instance - 1)) {
        throw PatchException("$PATCH: the opened post ad fetcher reaches its result at $instance from ${into[instance]}, " +
            "not only from the instruction before it")
    }
    val call = body[init] as FiveRegisterInstruction
    if (call.registerD == call.registerC) {
        throw PatchException("$PATCH: the opened post ad fetcher builds its result from itself")
    }
    return PermalinkAdResult(instance, call.registerD, body[instance].getReference<TypeReference>()!!.type)
}
