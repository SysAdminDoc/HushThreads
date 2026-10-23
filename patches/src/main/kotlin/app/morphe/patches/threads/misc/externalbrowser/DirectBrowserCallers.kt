/*
 * Copyright 2026 HushThreads contributors
 * https://github.com/SysAdminDoc/HushThreads
 */
package app.morphe.patches.threads.misc.externalbrowser

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.threads.feed.reaching
import app.morphe.patches.threads.misc.extension.freeLocalsAt
import app.morphe.patches.threads.misc.settings.EXTENSION_ROOT
import app.morphe.util.ControlFlow
import app.morphe.util.getReference
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Open links in browser"
private const val CONTEXT = "Landroid/content/Context;"
private const val TEXT = "Ljava/lang/String;"
private const val INTENT = "Landroid/content/Intent;"
private const val URI = "Landroid/net/Uri;"
private const val SESSION = "Lcom/instagram/common/session/UserSession;"
private const val STATE = "Landroidx/compose/runtime/MutableState;"
private const val VIEW_MODE = "Lcom/facebook/browser/common/viewmode/IABViewModeLaunchConfig;"
private const val FULL_SCREEN = "Lcom/facebook/browser/common/viewmode/IABViewModeLaunchConfig\$FullScreen;"
private const val HALF_SHEET = "Lcom/facebook/browser/common/viewmode/IABViewModeLaunchConfig\$HalfSheet;"
private const val START_ACTIVITY = "$CONTEXT->startActivity($INTENT)V"

/** Two of the strings Threads' browser Intent factory puts in the Intent it makes. Only the factory has both. */
internal const val FACTORY_MODULE = "TrackingInfo.ARG_MODULE_NAME"
internal const val FACTORY_OPEN = "BrowserLiteIntent.OPEN_WITH_URL"

/** What the ad click handler logs when a web ad has no link. Its web branch hands the ad's link to the launcher. */
internal const val AD_WEB_MISSING = "AdClickHandler.openWebLink missing webUri for AD_DESTINATION_WEB"

/** The name of the ad destination a web call to action has. */
internal const val WEB_DESTINATION = "AD_DESTINATION_WEB"

/** The start of the Compose trace the immersive browser screen carries, before its line number. */
internal const val IMMERSIVE_SCREEN = "com.instagram.barcelona.inappbrowser.compose.ImmersiveBrowserScreen ("

/**
 * One place that makes Threads' browser Intent without the launcher asking first. [at] is where the
 * hook goes in front of: the method's first instruction, or the factory call itself. A link that
 * went out leaves by `return-void` at the method's start, or by a jump to [done], the method's own
 * ending for an opened browser. A link that stays runs the instruction at [at] as before.
 */
internal class DirectBrowserSite(
    val method: MutableMethod,
    val at: Int,
    val done: Int?,
    val context: Int,
    val url: Int,
) {
    // Picked before anything changes: free where the hook goes in and, past a jump, where it lands.
    private val scratch = method.freeLocalsAt(PATCH, at, 2, listOfNotNull(done))

    fun inject() {
        val (answer, link) = scratch
        val success = if (done == null) "return-void" else "goto/16 :done"
        val labels = listOfNotNull(
            ExternalLabel("stock", method.getInstruction(at)),
            done?.let { ExternalLabel("done", method.getInstruction(it)) },
        )
        method.addInstructionsWithLabels(
            at,
            """
                move-object/from16 v$answer, v$context
                move-object/from16 v$link, v$url
                invoke-static { v$answer, v$link }, $OPEN_LINK
                move-result v$answer
                if-eqz v$answer, :stock
                $success
            """,
            *labels.toTypedArray(),
        )
    }
}

/**
 * The three places on 451 and 452 that make Threads' browser Intent themselves rather than through the
 * launcher: the lead form and privacy policy helper, the ad browser opened from a call to action,
 * and the media viewer's swipe up on an ad. Everything is read and proven before anything changes.
 *
 * Read from 451 (2026-10-13) and 452 (2026-10-22): the factory that builds the browser Intent has
 * five callers. The launcher calls it twice, once for each way it shows the browser. The immersive
 * browser screen calls it after the launcher already asked, so it's left alone, and so is the
 * factory. A sixth caller, or one of these gone, stops the patch.
 */
internal fun BytecodePatchContext.directBrowserSites(launcher: Method, launcherLink: Int): List<DirectBrowserSite> {
    val factory = browserFactory()
    val callers = mutableListOf<Pair<Method, Int>>()
    val requests = mutableListOf<Method>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        for (method in classDef.methods) {
            var calls = 0
            var request = false
            for (instruction in method.implementation?.instructions ?: continue) {
                when (instruction.opcode) {
                    Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE ->
                        if (instruction.getReference<MethodReference>()?.sameAs(factory) == true) calls++
                    Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO ->
                        if (instruction.getReference<StringReference>()?.string == AD_WEB_MISSING) request = true
                    else -> {}
                }
            }
            if (calls > 0) callers += method to calls
            if (request) requests += method
        }
    }

    val clickSource = factory.parameterTypes[4].toString()
    val post = factory.parameterTypes[2].toString()
    fun Method.isLead() = AccessFlags.STATIC.isSet(accessFlags) && returnType == "V" &&
        parameterTypes.map(CharSequence::toString) == listOf(CONTEXT, SESSION, clickSource, TEXT)
    fun Method.isAd() = !AccessFlags.STATIC.isSet(accessFlags) && returnType == "V" &&
        parameterTypes.map(CharSequence::toString).let {
            it.size == 8 && it[0] == CONTEXT && it[2] == post && it[4] == SESSION && it[6] == "Z" && it[7] == "Z"
        }
    fun Method.isMedia() = name == "invokeSuspend" && returnType == "Ljava/lang/Object;" &&
        parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;") && strings().any { it == WEB_DESTINATION }
    fun Method.isImmersive() = strings().any { it.startsWith(IMMERSIVE_SCREEN) }
    val kinds = mapOf<String, (Method) -> Boolean>(
        "the launcher" to { it.sameAs(launcher) },
        "the lead form helper" to { it.isLead() },
        "the ad browser" to { it.isAd() },
        "the media viewer's swipe up" to { it.isMedia() },
        "the immersive browser screen" to { it.isImmersive() },
    )
    for ((method, calls) in callers) {
        val matched = kinds.filterValues { it(method) }.keys
        if (matched.size != 1) {
            throw PatchException("$PATCH: ${method.describe()} makes Threads' browser Intent and is ${
                if (matched.isEmpty()) "none of the callers Threads has" else matched.joinToString(" and ")
            }")
        }
        val expected = if (matched.single() == "the launcher") 2 else 1
        ensure(calls == expected, "${matched.single()} makes Threads' browser Intent $calls time(s), not $expected")
    }
    val found = kinds.mapValues { (_, kind) -> callers.map { it.first }.filter(kind) }
    for ((kind, methods) in found) ensure(methods.size == 1, "$kind is one of ${methods.size} callers of Threads' browser Intent factory")

    val webUrl = adWebUrl(requests, launcher, launcherLink)
    return listOf(
        leadSite(mutable(found.getValue("the lead form helper").single()), factory),
        adSite(mutable(found.getValue("the ad browser").single()), factory, webUrl),
        mediaSite(mutable(found.getValue("the media viewer's swipe up").single()), factory, webUrl),
    )
}

/**
 * The static method that builds Threads' browser Intent from a context, the post, the session, where
 * the tap came from and the link, parameter 5. It parses the link with one static String-to-Uri
 * helper, and the Intent gets that Uri, or Uri.EMPTY when it parsed nothing.
 */
private fun BytecodePatchContext.browserFactory(): Method {
    val matched = Fingerprint(
        returnType = INTENT,
        parameters = listOf(CONTEXT, "L", "L", SESSION, "L", TEXT, TEXT, TEXT, TEXT),
        filters = listOf(string(FACTORY_MODULE), string(FACTORY_OPEN)),
        custom = { method, _ -> AccessFlags.STATIC.isSet(method.accessFlags) },
    ).matchAllOrNull().orEmpty().map { it.originalMethod }.one("Threads' browser Intent factory")
    // The factory as the build has it now, which is what its callers will run, read like the census reads them.
    val factory = classDefByOrNull(matched.definingClass)?.methods?.singleOrNull { it.sameAs(matched) }
        ?: refuse("Threads' browser Intent factory is gone from its class")
    val body = factory.body()
    val parse = body.indices.filter { n ->
        body[n].opcode.isStatic() && body[n].method()?.let {
            it.returnType == URI && it.parameterTypes.map(CharSequence::toString) == listOf(TEXT)
        } == true
    }.one("the browser Intent factory's link parser")
    ensure(
        factory.origins(parse, body[parse].namedRegisters().single()) == setOf(ENTRY to factory.entry(5)),
        "the browser Intent factory parses something other than its link, parameter 5",
    )
    ensure(body.getOrNull(parse + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT, "the browser Intent factory drops the parsed link")
    val data = body.indices.filter { body[it].method()?.toString() == "$INTENT->setData($URI)$INTENT" }
        .one("the browser Intent factory's setData")
    // On 451 the parse sits in a try whose handler leaves the null the Uri started as, and a null
    // Uri becomes Uri.EMPTY before setData. Read without the branch's sense, the null reaches it too.
    val uris = factory.origins(data, body[data].namedRegisters()[1])
    ensure(
        uris.any { it.first == parse + 1 } && uris.all { (n, _) ->
            n == parse + 1 || n >= 0 && (
                body[n].opcode == Opcode.SGET_OBJECT && body[n].field()?.toString() == "$URI->EMPTY:$URI" ||
                    body[n].opcode == Opcode.CONST_4 && (body[n] as NarrowLiteralInstruction).narrowLiteral == 0
                )
        },
        "the browser Intent factory's Intent gets a Uri other than the parsed link",
    )
    return factory
}

/** The String field of a call to action that holds its web link: what the ad click handler hands the launcher. */
private fun adWebUrl(requests: List<Method>, launcher: Method, launcherLink: Int): FieldReference {
    val request = requests.one("the ad click handler's web branch")
    val code = request.body()
    val call = code.indices.filter { code[it].method()?.sameAs(launcher) == true }.one("the ad click handler's launcher call")
    val link = code[call].namedRegisters()[launcherLink]
    val (read, _) = request.origins(call, link).singleOrNull() ?: refuse("the ad click handler's link has more than one source")
    val field = code.getOrNull(read)?.takeIf { it.opcode == Opcode.IGET_OBJECT }?.field()?.takeIf { it.type == TEXT }
        ?: refuse("the ad click handler's link isn't a call to action's String field")
    return field
}

/**
 * The lead form and privacy policy helper, a static method taking the context, the session, where
 * the tap came from and the link. It tries a plain ACTION_VIEW first, then makes the browser Intent
 * from the same context and link and starts it. The hook goes in at its start, before the plain
 * view, so a link that goes out is cleaned the same way the launcher's are.
 */
private fun leadSite(lead: MutableMethod, factory: Method): DirectBrowserSite {
    val code = lead.body()
    ensure(lead.strings().any { it == "android.intent.action.VIEW" }, "the lead form helper no longer tries a plain view first")
    val at = code.indices.filter { code[it].method()?.sameAs(factory) == true }.one("the lead form helper's browser Intent")
    val args = code[at].namedRegisters()
    val context = lead.entry(0)
    val url = lead.entry(3)
    ensure(lead.origins(at, args[0]) == setOf(ENTRY to context), "the lead form helper's browser gets a context other than its own")
    ensure(lead.origins(at, args[5]) == setOf(ENTRY to url), "the lead form helper's browser gets a link other than its own")
    ensure(code.getOrNull(at + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT, "the lead form helper drops its browser Intent")
    val made = (code[at + 1] as OneRegisterInstruction).registerA
    val start = code.indices.filter { code[it].method()?.toString() == START_ACTIVITY }.one("the lead form helper's startActivity")
    val started = code[start].namedRegisters()
    ensure(
        lead.origins(start, started[0]) == setOf(ENTRY to context) && lead.origins(start, started[1]) == setOf(at + 1 to made),
        "the lead form helper starts something other than its browser Intent",
    )
    // Code put in front of a try block's first instruction would land in the block.
    ensure(lead.implementation!!.tryBlocks.none { it.startCodeAddress == 0 }, "the lead form helper starts inside a try block")
    return DirectBrowserSite(lead, 0, null, context, url)
}

/**
 * The ad browser: an instance method taking the context, the browser's callbacks, the post, the
 * session and two flags. It opens the web link of the post's first call to action, full screen or
 * in a sheet. The hook goes in front of its factory call, after its pre-open callback and after
 * both view modes are picked, and a link that went out ends the method the way an ad with no web
 * link does.
 */
private fun BytecodePatchContext.adSite(ad: MutableMethod, factory: Method, webUrl: FieldReference): DirectBrowserSite {
    val code = ad.body()
    val at = ad.soleEntry(factory, "the ad browser")
    val args = code[at].namedRegisters()
    ensure(ad.origins(at, args[0]) == setOf(ENTRY to ad.entry(0)), "the ad browser's browser gets a context other than its own")
    val guard = callToAction(ad, at, args, factory, webUrl, "the ad browser")
    val done = code.target(guard)
    ensure(code[done].opcode == Opcode.RETURN_VOID, "the ad browser's ending for an ad with no web link isn't a plain return")
    val modes = ad.viewModes(at, "the ad browser")
    ensure(
        modes.any { code[it].opcode == Opcode.NEW_INSTANCE && code[it].getReference<TypeReference>()?.type == FULL_SCREEN } &&
            modes.any { code[it].opcode == Opcode.SGET_OBJECT && code[it].field()?.type == HALF_SHEET },
        "the ad browser no longer picks between full screen and a sheet",
    )
    return DirectBrowserSite(ad, at, done, args[0], args[5])
}

/**
 * The media viewer's swipe up on an ad, a coroutine whose captured context, post and state it reads
 * from its own fields. It opens the web link of the post's first call to action in a sheet, then
 * sets its state to true. Without a web link it sets the state to false. The hook goes in front of
 * its factory call, and a link that went out goes on to the true ending.
 */
private fun BytecodePatchContext.mediaSite(media: MutableMethod, factory: Method, webUrl: FieldReference): DirectBrowserSite {
    val code = media.body()
    val at = media.soleEntry(factory, "the media viewer's swipe up")
    val args = code[at].namedRegisters()
    val context = media.origins(at, args[0]).singleOrNull()?.first ?: refuse("the media viewer's context has more than one source")
    ensure(
        context >= 0 && code[context].field()?.type == CONTEXT && media.captured(context),
        "the media viewer's browser gets a context other than the one it captured",
    )
    val guard = callToAction(media, at, args, factory, webUrl, "the media viewer's swipe up")
    val failed = code.target(guard)
    media.viewModes(at, "the media viewer's swipe up")

    // const/4 vB, 1; move-object/from16 vS, vState; invoke (MutableState, Z)V; sget-object Unit; return-object
    val done = code.indices.filter { n ->
        n + 4 < code.size && code[n].opcode == Opcode.CONST_4 && (code[n] as NarrowLiteralInstruction).narrowLiteral == 1 &&
            code[n + 1].opcode == Opcode.MOVE_OBJECT_FROM16 &&
            code[n + 2].method()?.parameterTypes?.map(CharSequence::toString) == listOf(STATE, "Z") &&
            code[n + 3].opcode == Opcode.SGET_OBJECT && code[n + 4].opcode == Opcode.RETURN_OBJECT
    }.one("the media viewer's ending for an opened browser")
    val flag = (code[done] as OneRegisterInstruction).registerA
    val copy = code[done + 1] as TwoRegisterInstruction
    ensure(code[done + 2].namedRegisters() == listOf(copy.registerA, flag), "the media viewer sets its state from other registers")
    val state = media.origins(done + 1, copy.registerB).singleOrNull()?.first ?: refuse("the media viewer's state has more than one source")
    ensure(
        state >= 0 && code[state].field()?.type == STATE && media.captured(state) &&
            media.origins(at, copy.registerB) == media.origins(done + 1, copy.registerB),
        "the media viewer's state isn't its captured state before the browser opens",
    )
    ensure(
        code[failed].opcode == Opcode.CONST_4 && (code[failed] as NarrowLiteralInstruction).narrowLiteral == 0 &&
            (code[failed] as OneRegisterInstruction).registerA == flag &&
            code.getOrNull(failed + 1)?.let { it.opcode == Opcode.GOTO || it.opcode == Opcode.GOTO_16 || it.opcode == Opcode.GOTO_32 } == true &&
            code.target(failed + 1) == done + 1,
        "the media viewer's ending for an ad with no web link no longer sets its state to false",
    )
    return DirectBrowserSite(media, at, done, args[0], args[5])
}

/**
 * Proves the factory call at [at] opens the web link of the post's first call to action, and only
 * after the check that the call to action's destination is the web: the link is [webUrl] read from
 * the first item of a list the post holds, the post is the one the factory gets, and the check
 * compares that call to action's destination with the AD_DESTINATION_WEB constant. Answers the
 * check's index; its branch is taken when the destination isn't the web.
 */
private fun BytecodePatchContext.callToAction(
    caller: Method, at: Int, args: List<Int>, factory: Method, webUrl: FieldReference, what: String,
): Int {
    val code = caller.body()
    val (read, _) = caller.origins(at, args[5]).singleOrNull() ?: refuse("$what's link has more than one source")
    ensure(
        code[read].opcode == Opcode.IGET_OBJECT && code[read].field()?.toString() == webUrl.toString(),
        "$what opens something other than a call to action's web link",
    )
    val cta = caller.origins(read, (code[read] as TwoRegisterInstruction).registerB)
    val first = cta.singleOrNull()?.first ?: refuse("$what's call to action has more than one source")
    ensure(
        first > 0 && code[first].opcode == Opcode.MOVE_RESULT_OBJECT && code[first - 1].method()?.let {
            it.returnType == "Ljava/lang/Object;" && it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/util/List;")
        } == true,
        "$what's call to action isn't the first item of a list",
    )
    val list = caller.origins(first - 1, code[first - 1].namedRegisters().single()).singleOrNull()?.first
        ?: refuse("$what's call to action list has more than one source")
    ensure(
        list >= 0 && code[list].opcode == Opcode.IGET_OBJECT && code[list].field()?.definingClass == factory.parameterTypes[2].toString() &&
            caller.origins(list, (code[list] as TwoRegisterInstruction).registerB) == caller.origins(at, args[2]),
        "$what's call to action belongs to another post than the one its browser gets",
    )
    val guard = code.indices.filter { n ->
        if (n < 3 || n >= at || code[n].opcode != Opcode.IF_NE) return@filter false
        if (code[n - 1].opcode != Opcode.SGET_OBJECT || code[n - 2].opcode != Opcode.MOVE_RESULT_OBJECT) return@filter false
        val destination = code[n - 1].field() ?: return@filter false
        val getter = code[n - 3].method() ?: return@filter false
        getter.definingClass == webUrl.definingClass && getter.parameterTypes.isEmpty() && getter.returnType == destination.type &&
            caller.origins(n - 3, code[n - 3].namedRegisters().single()) == cta &&
            code[n].namedRegisters().toSet() == setOf(
                (code[n - 1] as OneRegisterInstruction).registerA, (code[n - 2] as OneRegisterInstruction).registerA,
            ) &&
            isWebDestination(destination)
    }.one("$what's check that the call to action goes to the web")
    ensure(caller.dominates(guard, at), "$what can reach its browser without the web check")
    return guard
}

/** Whether [field] is the enum constant its class's initializer makes with the name AD_DESTINATION_WEB. */
private fun BytecodePatchContext.isWebDestination(field: FieldReference): Boolean {
    val initializer = classDefByOrNull(field.definingClass)?.methods?.singleOrNull { it.name == "<clinit>" } ?: return false
    val code = initializer.body()
    val store = code.indices.singleOrNull {
        code[it].opcode == Opcode.SPUT_OBJECT && code[it].field()?.toString() == field.toString()
    } ?: return false
    val instance = initializer.origins(store, (code[store] as OneRegisterInstruction).registerA)
    return code.indices.count { n ->
        val call = code[n].method()
        if (call?.definingClass != field.definingClass || call.name != "<init>" || call.parameterTypes.firstOrNull()?.toString() != TEXT) {
            return@count false
        }
        val args = code[n].namedRegisters()
        initializer.origins(n, args[0]) == instance && initializer.origins(n, args[1]).singleOrNull()?.first?.let { name ->
            name >= 0 && code[name].getReference<StringReference>()?.string == WEB_DESTINATION
        } == true
    } == 1
}

/**
 * The view modes that reach the call wrapping the factory's Intent right after it, which takes
 * the Intent, an IABViewModeLaunchConfig and a flag. Answers where each mode is made.
 */
private fun Method.viewModes(at: Int, what: String): Set<Int> {
    val code = body()
    ensure(code.getOrNull(at + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT, "$what drops its browser Intent")
    val wrap = code.getOrNull(at + 2) ?: refuse("$what ends at its browser Intent")
    ensure(
        wrap.method()?.parameterTypes?.map(CharSequence::toString) == listOf(INTENT, VIEW_MODE, "Z") &&
            wrap.namedRegisters().first() == (code[at + 1] as OneRegisterInstruction).registerA,
        "$what no longer hands its browser Intent straight to the view mode",
    )
    val modes = origins(at + 2, wrap.namedRegisters()[1])
    ensure(
        modes.isNotEmpty() && modes.all { (n, _) ->
            n >= 0 && (code[n].getReference<TypeReference>()?.type ?: code[n].field()?.type)
                ?.startsWith(VIEW_MODE.removeSuffix(";") + "$") == true
        },
        "$what's view mode isn't one of the browser's own",
    )
    return modes.map { it.first }.toSet()
}

/** The index of the one factory call in this method, reached only from the instruction before it. */
private fun Method.soleEntry(factory: Method, what: String): Int {
    val code = body()
    val at = code.indices.filter { code[it].method()?.sameAs(factory) == true }.one("$what's browser Intent")
    ensure(implementation!!.tryBlocks.isEmpty(), "$what has a try block the hook would have to be proven against")
    val flow = ControlFlow.of(this)
    ensure(
        at > 0 && code[at - 1] !is OffsetInstruction && code[at - 1].opcode.canContinue() &&
            flow.normal.indices.none { from -> from != at - 1 && at in flow.normal[from] },
        "$what's browser has a branch straight to it that would miss the hook",
    )
    return at
}

/** Whether the instruction at [at] is an iget-object of one of this method's own fields, read from this. */
private fun Method.captured(at: Int): Boolean {
    val read = body()[at]
    return read.opcode == Opcode.IGET_OBJECT && read.field()?.definingClass == definingClass &&
        origins(at, (read as TwoRegisterInstruction).registerB) == setOf(ENTRY to entry(0) - 1)
}

/** Whether every path from the method's start to [target] runs [guard]. */
private fun Method.dominates(guard: Int, target: Int): Boolean {
    val flow = ControlFlow.of(this)
    val pending = ArrayDeque(listOf(0))
    val seen = mutableSetOf<Int>()
    while (pending.isNotEmpty()) {
        val at = pending.removeFirst()
        if (at == guard || !seen.add(at)) continue
        if (at == target) return false
        pending.addAll(flow.normal[at] + flow.exceptional[at])
    }
    return true
}

/** The index [ENTRY] stands for in an origin: the value the method got. */
private const val ENTRY = -1

/**
 * Where the value [register] holds at [at] comes from, on every path there: `(index, register)` of
 * each instruction that made it, or `(ENTRY, register)` for what the method got, through object
 * moves and casts. A wide write over one half of it is refused, not followed.
 */
private fun Method.origins(at: Int, register: Int, seen: Set<Pair<Int, Int>> = emptySet()): Set<Pair<Int, Int>> {
    if (at to register in seen) refuse("a browser value that goes round in a loop")
    val code = body()
    val writes = reaching(at, setOf(register))
    val sources = if (writes.fromEntry) mutableSetOf(ENTRY to register) else mutableSetOf()
    for (n in writes.writes) {
        val instruction = code[n]
        ensure((instruction as OneRegisterInstruction).registerA == register, "a browser value with half a long written over it")
        sources += when (instruction.opcode) {
            Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16 ->
                origins(n, (instruction as TwoRegisterInstruction).registerB, seen + (at to register))
            Opcode.CHECK_CAST -> origins(n, register, seen + (at to register))
            else -> setOf(n to register)
        }
    }
    return sources
}

/** The register declared parameter [index] arrives in, past the locals and `this`. */
private fun Method.entry(index: Int): Int {
    fun width(type: CharSequence) = if (type.toString() == "J" || type.toString() == "D") 2 else 1
    return implementation!!.registerCount - parameterTypes.sumOf(::width) + parameterTypes.take(index).sumOf(::width)
}

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single { it.sameAs(method) }

private fun MethodReference.sameAs(other: MethodReference) =
    name == other.name && definingClass == other.definingClass && returnType == other.returnType &&
        parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)

private fun Method.describe() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
private fun Method.body() = implementation?.instructions?.toList().orEmpty()
private fun Method.strings() = body().mapNotNull { it.getReference<StringReference>()?.string }
private fun Instruction.method() = getReference<MethodReference>()
private fun Instruction.field() = getReference<FieldReference>()
private fun Opcode.isStatic() = this == Opcode.INVOKE_STATIC || this == Opcode.INVOKE_STATIC_RANGE

/** The index the branch at [at] lands on. */
private fun List<Instruction>.target(at: Int): Int {
    var address = 0
    val offsets = map { instruction -> address.also { address += instruction.codeUnits } }
    val target = offsets.indexOf(offsets[at] + (this[at] as OffsetInstruction).codeOffset)
    ensure(target >= 0, "a browser branch lands between instructions")
    return target
}

private fun <T> List<T>.one(what: String): T = singleOrNull() ?: refuse("$what: expected one, found $size")
private fun ensure(condition: Boolean, what: String) { if (!condition) refuse(what) }
private fun refuse(what: String): Nothing = throw PatchException("$PATCH: $what")
