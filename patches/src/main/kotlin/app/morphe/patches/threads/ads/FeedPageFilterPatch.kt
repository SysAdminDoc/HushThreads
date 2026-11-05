/*
 * Copyright 2026 HushThreads contributors
 * https://github.com/SysAdminDoc/HushThreads
 *
 * Built on SysAdminDoc/Hushfacebook (GPL-3.0). The feed cache's merge as the place to take ads out
 * is the one zeldrisho/morphe-patches found: https://github.com/zeldrisho/morphe-patches
 */
package app.morphe.patches.threads.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.threads.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.threads.misc.extension.parameterRegister
import app.morphe.patches.threads.misc.extension.threadsExtensionPatch
import app.morphe.patches.threads.misc.extension.writeStub
import app.morphe.patches.threads.misc.settings.settingsPatch
import app.morphe.util.getReference
import app.morphe.util.singleOrPatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val FEED_ADS = "$EXTENSION_PACKAGE/ads/FeedAds;"
// The shared hook fails for whichever feed patch was selected, so its messages name both.
private const val PATCH = "Feed filter (Hide ads, Hide suggested users)"

/** The pre-cache page boundaries (the feed cache's merge, the spool cache's, and For You's other writes), shared by independently selected feed patches. */
internal val feedPageFilterPatch = bytecodePatch {
    dependsOn(settingsPatch)
    dependsOn(threadsExtensionPatch)
    execute {
        val merge = FeedPageMergeFingerprint.method

        val itemMedia = merge.itemMediaGetter()
        mutableClassDefBy(itemMedia.definingClass).methods.filter {
            it.name == itemMedia.name && it.returnType == MEDIA && it.parameterTypes.isEmpty() &&
                !AccessFlags.STATIC.isSet(it.accessFlags)
        }.singleOrPatchException("$PATCH: declaration of $itemMedia")

        writeStub(
            FEED_ADS, "itemMedia", 2,
            """
                instance-of v0, p0, ${itemMedia.definingClass}
                if-eqz v0, :none
                check-cast p0, ${itemMedia.definingClass}
                invoke-virtual { p0 }, ${itemMedia.definingClass}->${itemMedia.name}()$MEDIA
                move-result-object v0
                return-object v0
                :none
                const/4 v0, 0x0
                return-object v0
            """,
        )
        // The page, first thing. A resumed merge is called again with no page and reads its own saved
        // copy, which is the one filtered here on the first call.
        val page = merge.parameterRegister(4)
        merge.addInstructions(
            0,
            """
                invoke-static/range { $page .. $page }, $FEED_ADS->filter(Ljava/util/List;)Ljava/util/List;
                move-result-object $page
            """,
        )

        // For You's spool cache merges its pages on its own, past the merge above, so its page gets
        // the same filter first thing. It resumes the same way, from its own saved copy.
        val spool = SpoolFeedPageMergeFingerprint.method
        val spoolPage = spool.parameterRegister(5)
        spool.addInstructions(
            0,
            """
                invoke-static/range { $spoolPage .. $spoolPage }, $FEED_ADS->spoolPage(Ljava/util/List;)Ljava/util/List;
                move-result-object $spoolPage
            """,
        )

        // For You's data source writes feed items into either cache more ways than the merges (the
        // refresh's write, the paging flow's, an append to the page it holds, a page it rebuilds),
        // and none passes either filter above. Each handler's write takes its items first thing.
        // The feed cache handler's hand straight on to the feed cache, and a resumed spool write is
        // called again with no list.
        forYouWrites(spool).forEach { (handler, write) ->
            val items = write.parameterRegister(write.parameterTypes.indexOfFirst { it.toString() == LIST })
            val entry = if (handler == SPOOL_FEED_CACHE) "spoolWrite" else "forYouWrite"
            write.addInstructions(
                0,
                """
                    invoke-static/range { $items .. $items }, $FEED_ADS->$entry(Ljava/util/List;)Ljava/util/List;
                    move-result-object $items
                """,
            )
        }
    }
}

private const val LIST = "Ljava/util/List;"

private fun Method.signature(): String = name + parameterTypes.joinToString("", "(", ")") + returnType

/**
 * For You's writes of feed items besides its page merge: each abstract method of
 * [FOR_YOU_LOCAL_SOURCE] taking one list, other than the merge the spool's [merge] implements, as
 * each For You handler implements it.
 */
internal fun BytecodePatchContext.forYouWrites(merge: Method): List<Pair<String, MutableMethod>> {
    val declared = classDefBy(FOR_YOU_LOCAL_SOURCE).methods.filter { method ->
        AccessFlags.ABSTRACT.isSet(method.accessFlags) && method.parameterTypes.count { it.toString() == LIST } == 1
    }
    val (merges, writes) = declared.partition { it.signature() == merge.signature() }
    if (merges.size != 1) {
        throw PatchException("$PATCH: $FOR_YOU_LOCAL_SOURCE doesn't declare the spool's merge ${merge.signature()}")
    }
    if (writes.isEmpty()) throw PatchException("$PATCH: $FOR_YOU_LOCAL_SOURCE declares no list writes besides its merge")
    return listOf(FEED_CACHE_HANDLER, SPOOL_FEED_CACHE).flatMap { handler ->
        val classDef = mutableClassDefBy(handler)
        if (classDef.superclass != FOR_YOU_LOCAL_SOURCE) {
            throw PatchException("$PATCH: $handler no longer extends $FOR_YOU_LOCAL_SOURCE")
        }
        writes.map { declaration ->
            handler to classDef.methods.filter { it.signature() == declaration.signature() && it.implementation != null }
                .singleOrPatchException("$PATCH: $handler's ${declaration.signature()}")
        }
    }
}

/**
 * The feed item's own getter for its post, read from the feed cache's merge: a no-argument call
 * answering Media on a receiver the merge last cast to the getter's own class. It answers null for
 * an item that carries none. Hide ads reads the item class from it too.
 */
internal fun Method.itemMediaGetter(): MethodReference {
    val instructions = implementation!!.instructions.toList()
    val getters = instructions.mapIndexedNotNull { index, instruction ->
        if (instruction.opcode != Opcode.INVOKE_VIRTUAL && instruction.opcode != Opcode.INVOKE_VIRTUAL_RANGE) {
            return@mapIndexedNotNull null
        }
        val reference = instruction.getReference<MethodReference>() ?: return@mapIndexedNotNull null
        if (reference.returnType != MEDIA || reference.parameterTypes.isNotEmpty() || reference.definingClass == MEDIA) {
            return@mapIndexedNotNull null
        }
        val receiver = when (instruction) {
            is FiveRegisterInstruction -> instruction.registerC
            is RegisterRangeInstruction -> instruction.startRegister
            else -> return@mapIndexedNotNull null
        }
        val lastWrite = instructions.take(index).lastOrNull {
            val register = (it as? OneRegisterInstruction)?.registerA
            it.opcode.setsRegister() && (register == receiver || it.opcode.setsWideRegister() && register == receiver - 1)
        }
        reference.takeIf {
            lastWrite?.opcode == Opcode.CHECK_CAST &&
                lastWrite.getReference<TypeReference>()?.type == reference.definingClass
        }
    }.distinctBy { it.toString() }
    return getters.singleOrPatchException(
        "$PATCH: item-owned no-argument Media getter in $definingClass->$name",
    )
}
