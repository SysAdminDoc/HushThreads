/*
 * Copyright 2026 HushThreads contributors
 * https://github.com/SysAdminDoc/HushThreads
 *
 * Built on SysAdminDoc/Hushfacebook (GPL-3.0).
 */
package app.morphe.patches.threads.misc.sharelinks

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.literal
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.threads.ads.MEDIA
import app.morphe.patches.threads.misc.extension.enableStatus
import app.morphe.patches.threads.misc.extension.freeLocalsAt
import app.morphe.patches.threads.misc.extension.threadsExtensionPatch
import app.morphe.patches.threads.misc.settings.settingsPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.getReference
import app.morphe.util.singleOrPatchException
import app.morphe.util.superclassChain
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Sanitize sharing links"

private const val SANITIZE =
    "Lapp/morphe/extension/hushthreads/misc/LinkCleaner;->sanitizeShared(Ljava/lang/String;)Ljava/lang/String;"

internal const val POST_LINK =
    "Lapp/morphe/extension/hushthreads/misc/LinkCleaner;->postLink(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;"

/** The share sheet's repository of post links. A kept class. */
internal const val PERMALINK_REPOSITORY = "Lcom/instagram/barcelona/share/permalink/data/PermalinkRepository;"

/** A user. A kept class, whose methods Redex renames. */
internal const val USER = "Lcom/instagram/user/model/User;"

/**
 * The share sheet's fetch of a post's link: a suspend function taking the post, which reads the
 * link out of the server's answer and hands it on with the post. The only one in its class that
 * names "itas-android", the label it asks the post for to put the post's text before the link.
 */
internal object PostLinkFetchFingerprint : Fingerprint(
    definingClass = PERMALINK_REPOSITORY,
    returnType = "Ljava/lang/Object;",
    parameters = listOf("L", MEDIA, "L", "L"),
    filters = listOf(string("itas-android")),
)

/** A post's code, the last part of its own link. Pando reads a field by its name's hash. */
internal object PostCodeFingerprint : Fingerprint(
    definingClass = MEDIA,
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    filters = listOf(literal("code".hashCode())),
)

/** A post's author, or null when the post doesn't carry one. */
internal object PostAuthorFingerprint : Fingerprint(
    definingClass = MEDIA,
    returnType = USER,
    parameters = listOf(),
    filters = listOf(literal("user".hashCode())),
)

/** A user's username, or null. The name is an encoded string here, the hash is not. */
internal object UsernameFingerprint : Fingerprint(
    definingClass = USER,
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    filters = listOf(literal("username".hashCode())),
)

/**
 * The parser of the server's answer to `media/<id>/permalink/`, the one request behind every link
 * Threads hands out for a post: Copy link, Share to another app, Send and the share sheet's own
 * rows. It reads the `permalink` field and stores it in a fresh response object. The method's name
 * comes from the JSON parser interface it implements, so Redex keeps it, and the two strings say
 * which of the app's many parsers this is.
 */
internal object PermalinkResponseParserFingerprint : Fingerprint(
    name = "unsafeParseFromJson",
    returnType = "Ljava/lang/Object;",
    filters = listOf(
        string("permalink"),
        string("XDTPermalinkResponse"),
    ),
)

/**
 * Takes Threads' tracking tags off the links you share.
 *
 * Threads asks its server for a post's link each time you share it, and the server answers with
 * `xmt`, a code that ties the link to you, and `slof` added to it. The link goes through the
 * extension as the app reads it from that answer, before anything stores it, so every place that
 * shares the link gets the clean one. With the switch off, paused, or before the settings are
 * ready, the extension hands the link back as it came.
 *
 * Found by reading 449 (2026-09-29): the parser stores the string into the response object's one
 * String field right after it creates the object.
 */
@Suppress("unused")
val sanitizeSharingLinksPatch = bytecodePatch(
    name = "Sanitize sharing links",
    description = "Takes Threads' tracking tags, such as xmt, off the links you share or copy, and turns a short " +
        "share link into the post's own link. The post a link opens stays the same.",
    default = true,
) {
    category("Privacy")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.threads())
    dependsOn(threadsExtensionPatch)

    execute {
        val method = PermalinkResponseParserFingerprint.method
        val instructions = method.implementation!!.instructions.toList()

        // Tie the response to its type-name constructor argument and follow its registers until
        // an owned String store. A different allocation, owner or receiver isn't the response.
        val typeName = PermalinkResponseParserFingerprint.instructionMatches[1].index
        val typeRegister = (instructions[typeName] as OneRegisterInstruction).registerA
        val stores = mutableSetOf<Int>()
        for (created in typeName + 1 until instructions.size) {
            val allocation = instructions[created]
            if (allocation.opcode != Opcode.NEW_INSTANCE) continue
            val owner = allocation.getReference<TypeReference>()!!.type
            val constructorOwners = superclassChain(owner).toSet()
            val aliases = mutableSetOf((allocation as OneRegisterInstruction).registerA)
            val typeUnchanged = instructions.subList(typeName + 1, created + 1).none {
                val register = (it as? OneRegisterInstruction)?.registerA
                it.opcode.setsRegister() && (register == typeRegister || it.opcode.setsWideRegister() && register == typeRegister - 1)
            }
            if (!typeUnchanged) continue
            var namedResponse = false
            for (index in created + 1 until instructions.size) {
                val instruction = instructions[index]
                if (instruction is OffsetInstruction || !instruction.opcode.canContinue()) break
                if (instruction.opcode == Opcode.INVOKE_DIRECT || instruction.opcode == Opcode.INVOKE_DIRECT_RANGE) {
                    val call = instruction.getReference<MethodReference>()!!
                    val registers = when (instruction) {
                        is FiveRegisterInstruction -> listOf(instruction.registerC, instruction.registerD, instruction.registerE, instruction.registerF, instruction.registerG).take(instruction.registerCount)
                        is RegisterRangeInstruction -> (instruction.startRegister until instruction.startRegister + instruction.registerCount).toList()
                        else -> emptyList()
                    }
                    var word = 1
                    val takesTypeName = call.parameterTypes.any { parameter ->
                        val register = registers.getOrNull(word)
                        word += if (parameter == "J" || parameter == "D") 2 else 1
                        parameter == "Ljava/lang/String;" && register == typeRegister &&
                            instructions.subList(typeName + 1, index).none {
                                val register = (it as? OneRegisterInstruction)?.registerA
                                it.opcode.setsRegister() && (register == typeRegister || it.opcode.setsWideRegister() && register == typeRegister - 1)
                            }
                    }
                    if (call.name == "<init>" && call.returnType == "V" && call.definingClass in constructorOwners &&
                        registers.firstOrNull() in aliases && takesTypeName) {
                        namedResponse = true
                    }
                }
                if (namedResponse && instruction.opcode == Opcode.IPUT_OBJECT) {
                    val field = instruction.getReference<FieldReference>()!!
                    if (field.definingClass == owner && field.type == "Ljava/lang/String;" &&
                        (instruction as TwoRegisterInstruction).registerB in aliases) stores += index
                }
                if (instruction.opcode.setsRegister()) {
                    val target = (instruction as OneRegisterInstruction).registerA
                    val carriesResponse = when (instruction.opcode) {
                        Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16 ->
                            (instruction as TwoRegisterInstruction).registerB in aliases
                        Opcode.CHECK_CAST -> target in aliases
                        else -> false
                    }
                    aliases.remove(target)
                    if (instruction.opcode.setsWideRegister()) aliases.remove(target + 1)
                    if (carriesResponse) aliases += target
                    if (aliases.isEmpty()) break
                }
            }
        }
        val store = stores.singleOrPatchException(
            "Sanitize sharing links: owned response String store in ${method.definingClass}->${method.name}; candidates: " +
                stores.joinToString { "$it:${instructions[it].getReference<FieldReference>()}" },
        )
        val link = (instructions[store] as TwoRegisterInstruction).registerA

        // At the store's own label, so a branch that jumped to the store runs the call too.
        method.addInstructionsAtControlFlowLabel(
            store,
            """
                invoke-static/range { v$link .. v$link }, $SANITIZE
                move-result-object v$link
            """,
        )

        replaceShortLinks(instructions[store].getReference<FieldReference>()!!)

        enableStatus("sanitizeSharingLinks")
    }
}

/**
 * Where the share sheet reads the link out of the server's answer, through the answer's getter of
 * [link], the field the parser stores it in: right after each read, the post's code and its
 * author's username go to the extension with the link, which swaps a short /share/ link for the
 * post's own. The post is the one the fetch hands on with the link, and it sits in the same
 * register from the read to that store.
 *
 * Found by reading 449 (2026-10-02): the fetch reads the link twice, once for the link it shares
 * and once for the link it keeps, and stores the post with both.
 */
private fun BytecodePatchContext.replaceShortLinks(link: FieldReference) {
    val method = PostLinkFetchFingerprint.method
    val where = "${method.definingClass}->${method.name}"
    val body = method.implementation!!.instructions.toList()

    val owners = superclassChain(link.definingClass).toList()
    val types = owners.toSet() + owners.flatMap { classDefByOrNull(it)?.interfaces.orEmpty() }
    val getters = owners.flatMap { classDefByOrNull(it)?.methods ?: emptyList() }.filter { getter ->
        getter.parameterTypes.isEmpty() && getter.returnType == "Ljava/lang/String;" &&
            getter.implementation?.instructions?.any { it.opcode == Opcode.IGET_OBJECT && it.getReference<FieldReference>() == link } == true
    }.map { it.name }.toSet()
    val reads = body.indices.filter { index ->
        val instruction = body[index]
        if (instruction.opcode != Opcode.INVOKE_INTERFACE && instruction.opcode != Opcode.INVOKE_VIRTUAL) return@filter false
        val call = instruction.getReference<MethodReference>()!!
        call.definingClass in types && call.name in getters && call.parameterTypes.isEmpty() &&
            call.returnType == "Ljava/lang/String;" && body.getOrNull(index + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT
    }
    if (reads.isEmpty()) throw PatchException("$PATCH: $where never reads ${link.definingClass}'s link")

    val post = body.withIndex().filter { (index, instruction) ->
        index > reads.last() && instruction.opcode == Opcode.IPUT_OBJECT && instruction.getReference<FieldReference>()!!.type == MEDIA
    }.singleOrPatchException("$PATCH: the post $where stores with the link").index
    val media = (body[post] as TwoRegisterInstruction).registerA
    val code = PostCodeFingerprint.method
    val author = PostAuthorFingerprint.method
    val username = UsernameFingerprint.method

    val sites = reads.map { read ->
        val after = read + 2
        val value = (body[read + 1] as OneRegisterInstruction).registerA
        for (index in after until post) {
            val instruction = body[index]
            if (instruction is OffsetInstruction || !instruction.opcode.canContinue()) {
                throw PatchException("$PATCH: $where branches between its link read at $read and the post's store at $post")
            }
            val written = (instruction as? OneRegisterInstruction)?.registerA
            if (instruction.opcode.setsRegister() &&
                (written == media || instruction.opcode.setsWideRegister() && written == media - 1)
            ) throw PatchException("$PATCH: $where writes v$media, the post, between its link read at $read and the store")
        }
        if (media > 15 || value > 15) throw PatchException("$PATCH: $where keeps the post or the link above v15")
        Triple(after, value, method.freeLocalsAt(PATCH, after, 2))
    }

    // Later sites first, so the earlier indices still point where they did.
    for ((after, value, scratch) in sites.sortedByDescending { it.first }) {
        val (postCode, name) = scratch
        method.addInstructionsWithLabels(
            after,
            """
                const/4 v$postCode, 0x0
                const/4 v$name, 0x0
                if-eqz v$media, :link
                invoke-virtual { v$media }, ${code.definingClass}->${code.name}()Ljava/lang/String;
                move-result-object v$postCode
                invoke-virtual { v$media }, ${author.definingClass}->${author.name}()$USER
                move-result-object v$name
                if-eqz v$name, :link
                invoke-virtual { v$name }, ${username.definingClass}->${username.name}()Ljava/lang/String;
                move-result-object v$name
                :link
                invoke-static { v$value, v$name, v$postCode }, $POST_LINK
                move-result-object v$value
            """,
        )
    }
}
