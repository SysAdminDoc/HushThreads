/*
 * Copyright 2026 HushThreads contributors
 * https://github.com/SysAdminDoc/HushThreads
 */
package app.morphe.patches.threads.misc.externalbrowser

import app.morphe.ExtensionDex
import app.morphe.FixtureDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// 451 has two more methods with the first and one more with the second. Only the factory has both and makes an Intent.
private const val FACTORY_NOTE = "BrowserLiteIntent.OPEN_WITH_URL"
private const val FACTORY_MODULE_NOTE = "TrackingInfo.ARG_MODULE_NAME"
private const val AD_NOTE = "AdClickHandler.openWebLink missing webUri for AD_DESTINATION_WEB"
private const val IMMERSIVE_NOTE = "com.instagram.barcelona.inappbrowser.compose.ImmersiveBrowserScreen (ImmersiveBrowserScreen.kt:97)"
private const val STATE_SETTER = "(Landroidx/compose/runtime/MutableState;Z)V"
private const val WEB = "LX/1Dc;->A0G:LX/1Dc;"
private const val METRICS = "LX/M0t;->A03(I)V"
private const val VIEW_MODES = "Lcom/facebook/browser/common/viewmode/IABViewModeLaunchConfig$"

/**
 * The browser Intent factory, every method that calls it, and the ad click handler, read from the
 * build by their strings and calls alone. Nothing of the patch's own finder or flow helpers is used.
 */
internal class BrowserFixture(val classes: List<ClassDef>, val factory: Method, val callers: List<Method>, val request: Method) {
    val launcher get() = callers.single { it.note(LAUNCHER_MESSAGE) }
    val immersive get() = callers.single { it.note(IMMERSIVE_NOTE) }
    val lead get() = direct.single { AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.size == 4 }
    val ad get() = direct.single { !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" }
    val media get() = direct.single { it.name == "invokeSuspend" }
    private val direct get() = callers.filterNot { it.note(LAUNCHER_MESSAGE) || it.note(IMMERSIVE_NOTE) }
}

internal fun browserFixture(build: File): BrowserFixture {
    fun Method.isFactory() = returnType == "Landroid/content/Intent;" && note(FACTORY_NOTE) && note(FACTORY_MODULE_NOTE)
    val factoryClasses = FixtureDex.classesWhere(build, { FACTORY_NOTE in it.stringSection }) { it.isFactory() }
    val factory = factoryClasses.flatMap { it.methods }.single { it.isFactory() }
    val reference = factory.toString()
    val callerClasses = FixtureDex.classesWhere(build, { dex -> dex.methodSection.any { it.toString() == reference } }) { m ->
        m.code().any { it.ref() == reference }
    }
    val requests = FixtureDex.classesWhere(build, { AD_NOTE in it.stringSection }) { it.note(AD_NOTE) }
    val request = requests.flatMap { it.methods }.single { it.note(AD_NOTE) }
    // The enum constants the callers compare with, the AD_DESTINATION_WEB one among them.
    val enums = callerClasses.flatMap { it.methods }.flatMap { it.code() }.mapNotNull {
        ((it as? ReferenceInstruction)?.reference as? FieldReference)?.takeIf { f -> f.type == f.definingClass }?.type
    }.toSet()
    val classes = (factoryClasses + callerClasses + requests + FixtureDex.classes(build, enums).values).distinctBy { it.type }
    val callers = callerClasses.flatMap { it.methods }.filter { m -> m.code().any { it.ref() == reference } }
    return BrowserFixture(classes, factory, callers, request)
}

/**
 * Open links in browser on the three places that make Threads' browser Intent without the
 * launcher: the lead form and privacy policy helper, the ad browser and the media viewer's swipe
 * up. Each asks the extension once and, when the link went out, skips only its own browser work.
 */
class DirectBrowserCallersFixtureTest {
    @Test
    fun `each declared build makes the browser Intent in five places and the lead form helper has two callers`() {
        for (build in Fixtures.declaredBuilds()) {
            val fixture = browserFixture(build)
            val reference = fixture.factory.toString()
            assertEquals(build.name, 5, fixture.callers.size)
            assertEquals(6, fixture.callers.sumOf { m -> m.code().count { it.ref() == reference } })
            // The launcher's two calls are its two ways of showing the browser.
            assertEquals(2, fixture.launcher.code().count { it.ref() == reference })
            assertEquals(5, setOf(fixture.launcher, fixture.immersive, fixture.lead, fixture.ad, fixture.media).size)

            // The lead form helper serves the lead form's privacy policy and disclaimer links from one
            // lambda. 452 also calls it once from a lambda merged with unrelated ones.
            val lead = fixture.lead.toString()
            val leadCallers = FixtureDex.methodsWhere(build, { dex -> dex.methodSection.any { it.toString() == lead } }) { m ->
                m.code().any { it.ref() == lead }
            }
            assertEquals("${build.name}: lead form helper calls per caller", listOf(1, 2),
                leadCallers.map { m -> m.code().count { it.ref() == lead } }.sorted())
        }
    }

    @Test
    fun `each declared build's direct web callers hand off once and finish the successful route`() {
        for (build in Fixtures.declaredBuilds()) {
            val fixture = browserFixture(build)
            val reference = fixture.factory.toString()
            val context = PatchContexts.of(ExtensionDex.classes() + fixture.classes)
            BrowserLauncherFingerprint.clearMatch()
            openLinksExternallyPatch.execute(context)

            for (stock in listOf(fixture.lead, fixture.ad, fixture.media)) {
                val where = "${build.name} ${stock.definingClass}"
                val before = stock.code()
                val actual = context.mutable(stock)
                val after = actual.code()
                val factoryAt = before.indices.single { before[it].ref() == reference }
                val args = before[factoryAt].args()
                val entry = stock == fixture.lead
                val at = if (entry) 0 else factoryAt
                val registers = stock.implementation!!.registerCount

                assertEquals("$where: one handoff", 1, after.count { it.ref() == OPEN_LINK })
                assertEquals("$where: six instructions added", before.size + 6, after.size)
                assertEquals("$where: no new registers", registers, actual.implementation!!.registerCount)
                assertEquals(
                    where,
                    listOf(Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_FROM16, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT,
                        Opcode.IF_EQZ, if (entry) Opcode.RETURN_VOID else Opcode.GOTO_16),
                    after.subList(at, at + 6).map { it.opcode },
                )
                val copied = after.subList(at, at + 2).map { it as TwoRegisterInstruction }
                // The lead helper's context is its first parameter and its link its fourth, the last.
                assertEquals("$where: context", if (entry) registers - 4 else args[0], copied[0].registerB)
                assertEquals("$where: link", if (entry) registers - 1 else args[5], copied[1].registerB)
                assertEquals(copied.map { it.registerA }, after[at + 2].args())
                assertEquals(copied[0].registerA, (after[at + 3] as OneRegisterInstruction).registerA)
                assertEquals(copied[0].registerA, (after[at + 4] as OneRegisterInstruction).registerA)
                assertEquals("$where: a link that stays runs the stock code from where the hook went in", at + 6, after.destination(at + 4))
                assertEquals("$where: the stock code is all there", before.map { it.key() }, (after.take(at) + after.drop(at + 6)).map { it.key() })

                // Nothing the stock method reads later is written over, on either way out of the hook.
                val done = if (entry) null else after.destination(at + 5) - 6
                val locals = registers - stock.parameterTypes.size - (if (AccessFlags.STATIC.isSet(stock.accessFlags)) 0 else 1)
                for (scratch in copied.map { it.registerA }) {
                    assertTrue("$where: v$scratch is a local", scratch < locals)
                    assertFalse("$where: v$scratch is read after the hook", stock.readsBeforeWrite(at, scratch))
                    if (done != null) assertFalse("$where: v$scratch is read where a link that went out lands", stock.readsBeforeWrite(done, scratch))
                }
                // A link that went out never reaches the factory, the stock browser or the extension again.
                val success = if (entry) at + 5 else after.destination(at + 5)
                val reached = actual.reachable(success)
                assertTrue("$where: opens twice", reached.none { after[it].ref() == reference || after[it].ref() == OPEN_LINK })
                assertTrue("$where: starts the stock browser", reached.none { after[it].ref().endsWith("->startActivity(Landroid/content/Intent;)V") })
                assertTrue("$where: runs the metrics the skipped browser set up", reached.none { after[it].ref() == METRICS })
                if (!entry) assertEquals("$where: a link that stays reaches the factory", reference, after[at + 6].ref())

                when (stock) {
                    fixture.lead -> {
                        assertEquals(12, registers)
                        // The plain view the helper tries first runs only for a link that stays.
                        assertTrue(after.drop(6).any { it.string() == "android.intent.action.VIEW" })
                    }
                    else -> {
                        // 452's call to action keeps its web link in 6qK.A07, and the web check sits in front of it.
                        val (url, _) = before.source(factoryAt, args[5])
                        assertEquals("LX/6qK;->A07:Ljava/lang/String;", before[url].ref())
                        val guard = before.indices.single { it > 0 && before[it].opcode == Opcode.IF_NE && before[it - 1].ref() == WEB }
                        assertTrue("$where: web check before the link is read", guard < url)
                        val (ctx, ctxRegister) = before.source(factoryAt, args[0])
                        if (stock == fixture.ad) {
                            assertEquals("the ad browser's own context", -1, ctx)
                            assertEquals(registers - 8, ctxRegister)
                            val noWeb = before.destination(guard)
                            assertEquals(Opcode.RETURN_VOID, before[noWeb].opcode)
                            assertEquals("the ad ends where an ad with no web link ends", noWeb + 6, after.destination(at + 5))
                            assertEquals("so finding it by its return alone would be wrong", 3, before.count { it.opcode == Opcode.RETURN_VOID })
                            // The pre-open callback and both view modes still lead to the browser when the link stays.
                            val pieces = listOf(
                                before.indices.single { before[it].ref() == "LX/HnM;->invoke()Ljava/lang/Object;" },
                                before.indices.single { before[it].opcode == Opcode.NEW_INSTANCE && before[it].ref() == "${VIEW_MODES}FullScreen;" },
                                before.indices.single { before[it].opcode == Opcode.SGET_OBJECT && before[it].ref().endsWith(":${VIEW_MODES}HalfSheet;") },
                            )
                            for (piece in pieces) {
                                val moved = if (piece > at) piece + 6 else piece
                                assertTrue("$where: ${before[piece].ref()} reaches the hook", at in actual.reachable(moved))
                            }
                        } else {
                            assertEquals("the media viewer's captured context", "${stock.definingClass}->A03:Landroid/content/Context;", before[ctx].ref())
                            val lands = after.destination(at + 5)
                            assertEquals(Opcode.CONST_4, after[lands].opcode)
                            assertEquals("a link that went out sets the state to true", 1, (after[lands] as NarrowLiteralInstruction).narrowLiteral)
                            assertTrue(after[lands + 2].ref().endsWith(STATE_SETTER))
                            assertEquals(Opcode.RETURN_OBJECT, after[lands + 4].opcode)
                            val (state, _) = after.source(lands + 1, (after[lands + 1] as TwoRegisterInstruction).registerB)
                            assertEquals("${stock.definingClass}->A08:Landroidx/compose/runtime/MutableState;", after[state].ref())
                            assertTrue("$where: state read before the hook", state < at)
                            // It lands just past the metrics call on the 0PAX the skipped browser would have made.
                            assertEquals(METRICS, after[lands - 1].ref())
                            // The ending for an ad with no web link still sets the state to false.
                            val failed = before.destination(guard)
                            assertEquals(0, (before[failed] as NarrowLiteralInstruction).narrowLiteral)
                            assertEquals(lands + 1, after.destination(failed + 6 + 1))
                        }
                    }
                }
            }

            // The launcher hook is the stock one, and the factory, the immersive screen and the ad click handler are as Threads wrote them.
            assertEquals(OPEN_LINK, context.mutable(fixture.launcher).code()[2].ref())
            for (unchanged in listOf(fixture.factory, fixture.immersive, fixture.request)) {
                val actual = context.mutable(unchanged)
                assertEquals(unchanged.toString(), unchanged.code().map { it.key() to (it as? OffsetInstruction)?.codeOffset },
                    actual.code().map { it.key() to (it as? OffsetInstruction)?.codeOffset })
            }
            val hosts = fixture.classes.flatMap { context.mutableClassDefBy(it.type).methods.toList() }
            assertEquals("${build.name}: the launcher and the three", 4, hosts.sumOf { m -> m.code().count { it.ref() == OPEN_LINK } })
        }
    }

    /** A changed build the patch can't prove is refused before anything changes, saying what it found. */
    @Test
    fun `wrong sources guards completions and callers are refused`() {
        for (build in Fixtures.declaredBuilds()) {
            val fixture = browserFixture(build)
            val reference = fixture.factory.toString()
            val cases = linkedMapOf(
                "factory URL" to "parses something other than its link",
                "CTA attribution" to "the ad browser opens something other than a call to action's web link",
                "web enum" to "the ad browser's check that the call to action goes to the web: expected one, found 0",
                "ad return" to "the ad browser's ending for an ad with no web link isn't a plain return",
                "ad context" to "the ad browser's browser gets a context other than its own",
                "ad mode" to "the ad browser no longer picks between full screen and a sheet",
                "media false" to "the media viewer's ending for an opened browser: expected one, found 0",
                "media high half" to "a browser value with half a long written over it",
                "media context" to "the media viewer's browser gets a context other than the one it captured",
                "media state" to "the media viewer's state has more than one source",
                "lead URL" to "the lead form helper's browser gets a link other than its own",
                "duplicate" to "the ad browser makes Threads' browser Intent 2 time(s), not 1",
                "direct branch" to "the ad browser's browser has a branch straight to it",
                "unknown caller" to "makes Threads' browser Intent and is none of the callers Threads has",
            )
            for ((fault, expected) in cases) {
                val context = PatchContexts.of(ExtensionDex.classes() + fixture.classes)
                BrowserLauncherFingerprint.clearMatch()
                val target = when (fault) {
                    "factory URL" -> fixture.factory
                    "lead URL" -> fixture.lead
                    "unknown caller" -> fixture.request
                    else -> if (fault.startsWith("media")) fixture.media else fixture.ad
                }
                val method = context.mutable(target)
                val body = target.code()
                val registers = target.implementation!!.registerCount
                val call = body.indexOfFirst { it.ref() == reference }
                fun index(what: (Int) -> Boolean) = body.indices.single(what)
                // const/4 vX, 1 two instructions in front of the state setter: the media viewer's true ending.
                fun trueEnding() = index { body[it].opcode == Opcode.CONST_4 && (body[it] as NarrowLiteralInstruction).narrowLiteral == 1 &&
                    body.getOrNull(it + 2)?.ref()?.endsWith(STATE_SETTER) == true }
                when (fault) {
                    "factory URL" -> {
                        val parser = index { body[it].ref() == "LX/160;->A04(Ljava/lang/String;)Landroid/net/Uri;" }
                        // Parameter 6, the string after the link.
                        val sixth = registers - 3
                        method.replaceInstruction(parser, "invoke-static/range { v$sixth .. v$sixth }, ${body[parser].ref()}")
                    }
                    "CTA attribution" -> {
                        val (load, _) = body.source(call, body[call].args()[5])
                        val read = body[load] as TwoRegisterInstruction
                        // A05 is another String of the call to action, the one 452 passes as the factory's last argument.
                        method.replaceInstruction(load, "iget-object v${read.registerA}, v${read.registerB}, LX/6qK;->A05:Ljava/lang/String;")
                    }
                    "web enum" -> {
                        val n = index { body[it].ref() == WEB }
                        method.replaceInstruction(n, "sget-object v${(body[n] as OneRegisterInstruction).registerA}, LX/1Dc;->A0H:LX/1Dc;")
                    }
                    "ad return" -> {
                        // The web check's no-web branch sent to the metrics check in front of the shared return instead.
                        val guard = index { it > 0 && body[it].opcode == Opcode.IF_NE && body[it - 1].ref() == WEB }
                        val elsewhere = body.destination(guard) - 6
                        assertEquals(Opcode.IF_NEZ, body[elsewhere].opcode)
                        val compare = body[guard] as TwoRegisterInstruction
                        // The new check goes in first, while the stock indexes still hold, then the old one comes out.
                        method.addInstructionsWithLabels(guard, "if-ne v${compare.registerA}, v${compare.registerB}, :elsewhere",
                            ExternalLabel("elsewhere", method.getInstruction(elsewhere)))
                        method.removeInstruction(guard + 1)
                    }
                    "ad context" -> {
                        val copy = (call - 1 downTo 0).first { (body[it] as? TwoRegisterInstruction)?.registerA == body[call].args()[0] }
                        val (sessionAt, session) = body.source(call, body[call].args()[3])
                        assertEquals(-1, sessionAt)
                        method.replaceInstruction(copy, "move-object/from16 v${body[call].args()[0]}, v$session")
                    }
                    "ad mode" -> {
                        val full = index { body[it].opcode == Opcode.NEW_INSTANCE && body[it].ref() == "${VIEW_MODES}FullScreen;" }
                        val half = body.first { it.opcode == Opcode.SGET_OBJECT && it.ref().endsWith(":${VIEW_MODES}HalfSheet;") }.ref()
                        method.replaceInstruction(full, "sget-object v${(body[full] as OneRegisterInstruction).registerA}, $half")
                    }
                    "media false" -> {
                        val n = trueEnding()
                        method.replaceInstruction(n, "const/4 v${(body[n] as OneRegisterInstruction).registerA}, 0x0")
                    }
                    "media high half" -> method.addInstructions(call, "const-wide/16 v${body[call].args()[0] - 1}, 0x0")
                    "media context" -> {
                        val copy = (call - 1 downTo 0).first { (body[it] as? TwoRegisterInstruction)?.registerA == body[call].args()[0] }
                        // The coroutine itself in place of the context it captured.
                        method.replaceInstruction(copy, "move-object/from16 v${body[call].args()[0]}, v${registers - 2}")
                    }
                    "media state" -> {
                        val done = trueEnding()
                        val saved = (body[done + 1] as TwoRegisterInstruction).registerB
                        method.addInstructions(done, "move-object/from16 v$saved, v${body[call].args()[0]}")
                    }
                    "lead URL" -> {
                        val copy = index { body[it].opcode == Opcode.MOVE_OBJECT && (body[it] as TwoRegisterInstruction).registerB == registers - 1 }
                        method.replaceInstruction(copy, "move-object v${(body[copy] as TwoRegisterInstruction).registerA}, v${registers - 2}")
                    }
                    "duplicate" -> method.addInstructions(call, "invoke-static/range { v${body[call].args().first()} .. v${body[call].args().last()} }, $reference")
                    "direct branch" -> method.addInstructionsWithLabels(call, "goto/16 :bypass", ExternalLabel("bypass", method.getInstruction(call)))
                    "unknown caller" -> method.addInstructions(0, "invoke-static/range { v0 .. v8 }, $reference")
                }
                val error = assertThrows("${build.name}: $fault", PatchException::class.java) { openLinksExternallyPatch.execute(context) }
                    .message.orEmpty()
                assertTrue("$fault: $error", error.contains(expected))
                for (hooked in listOf(fixture.launcher, fixture.lead, fixture.ad, fixture.media)) {
                    assertTrue("$fault: nothing changed before the refusal", context.mutable(hooked).code().none { it.ref() == OPEN_LINK })
                }
            }
        }
    }
}

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.returnType == method.returnType &&
            it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
    }

private fun Method.code() = implementation?.instructions?.toList().orEmpty()
private fun Method.note(text: String) = code().any { it.string() == text }
private fun Instruction.string() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
private fun Instruction.ref() = (this as? ReferenceInstruction)?.reference?.toString().orEmpty()

/**
 * What an instruction does, without where its branch lands, which moves when code goes in above.
 * A goto counts as one whatever its width, since code put in between may need a wider one.
 */
private fun Instruction.key(): List<Any?> = listOf(
    if (opcode == Opcode.GOTO_16 || opcode == Opcode.GOTO_32) Opcode.GOTO else opcode, ref(), args(),
    (this as? OneRegisterInstruction)?.registerA, (this as? TwoRegisterInstruction)?.registerB,
    (this as? ThreeRegisterInstruction)?.registerC, (this as? NarrowLiteralInstruction)?.narrowLiteral,
)

private fun Instruction.args(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

private fun List<Instruction>.addresses(): IntArray {
    val at = IntArray(size + 1)
    for (n in indices) at[n + 1] = at[n] + this[n].codeUnits
    return at
}

/** The index the branch at [n] lands on. */
private fun List<Instruction>.destination(n: Int): Int {
    val at = addresses()
    return at.indexOfFirst { it == at[n] + (this[n] as OffsetInstruction).codeOffset }
}

/** Straight back from [at] through object moves: where [register]'s value was made, or -1 and the register it arrived in. */
private fun List<Instruction>.source(at: Int, register: Int): Pair<Int, Int> {
    var wanted = register
    for (n in at - 1 downTo 0) {
        val instruction = this[n]
        if (!instruction.opcode.setsRegister() || (instruction as OneRegisterInstruction).registerA != wanted) continue
        if (instruction.opcode == Opcode.CHECK_CAST) continue
        if (instruction.opcode in listOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)) {
            wanted = (instruction as TwoRegisterInstruction).registerB
        } else {
            return n to wanted
        }
    }
    return -1 to wanted
}

/** Where each instruction goes next without throwing: on, a branch or a switch arm. */
private fun Method.flow(): List<List<Int>> {
    val code = code()
    val at = code.addresses()
    val index = at.withIndex().associate { (n, address) -> address to n }
    val payloads = setOf(Opcode.PACKED_SWITCH_PAYLOAD, Opcode.SPARSE_SWITCH_PAYLOAD, Opcode.ARRAY_PAYLOAD)
    return code.indices.map { n ->
        val instruction = code[n]
        val out = mutableListOf<Int>()
        if (instruction.opcode !in payloads) {
            if (instruction.opcode.canContinue()) out += n + 1
            if (instruction is OffsetInstruction && instruction.opcode != Opcode.FILL_ARRAY_DATA) {
                val target = index.getValue(at[n] + instruction.codeOffset)
                if (instruction.opcode == Opcode.PACKED_SWITCH || instruction.opcode == Opcode.SPARSE_SWITCH) {
                    (code[target] as SwitchPayload).switchElements.forEach { out += index.getValue(at[n] + it.offset) }
                } else {
                    out += target
                }
            }
        }
        out.filter { it < code.size }.distinct()
    }
}

/** The handlers of each try block an instruction sits in. */
private fun Method.handlers(): List<List<Int>> {
    val code = code()
    val at = code.addresses()
    val index = at.withIndex().associate { (n, address) -> address to n }
    return code.indices.map { n ->
        implementation!!.tryBlocks.filter { at[n] >= it.startCodeAddress && at[n] < it.startCodeAddress + it.codeUnitCount }
            .flatMap { block -> block.exceptionHandlers.map { index.getValue(it.handlerCodeAddress) } }.distinct()
    }
}

private fun Method.reachable(start: Int): Set<Int> {
    val flow = flow()
    val handlers = handlers()
    val seen = mutableSetOf(start)
    val pending = ArrayDeque(listOf(start))
    while (pending.isNotEmpty()) {
        val n = pending.removeFirst()
        (flow[n] + handlers[n]).forEach { if (seen.add(it)) pending += it }
    }
    return seen
}

/**
 * Whether a path from [start], that instruction included, can read [register] before writing it.
 * Every named register counts as read unless it's a plain destination, the upper half of a wide
 * one included, and a try handler is reached with the register as it was before the instruction.
 */
private fun Method.readsBeforeWrite(start: Int, register: Int): Boolean {
    val code = code()
    val flow = flow()
    val handlers = handlers()
    val seen = mutableSetOf(start)
    val pending = ArrayDeque(listOf(start))
    while (pending.isNotEmpty()) {
        val n = pending.removeFirst()
        val instruction = code[n]
        // Opcode's own name field, the smali spelling: add-int/2addr, const-wide/16.
        val name = instruction.opcode.name
        val wide = listOf("wide", "long", "double").any { it in name }
        val named = when (instruction) {
            is RegisterRangeInstruction, is FiveRegisterInstruction -> instruction.args()
            is ThreeRegisterInstruction -> listOf(instruction.registerA, instruction.registerB, instruction.registerC)
            is TwoRegisterInstruction -> listOf(instruction.registerA, instruction.registerB)
            is OneRegisterInstruction -> listOf(instruction.registerA)
            else -> emptyList()
        }
        val plainWrite = instruction.opcode.setsRegister() && !name.endsWith("/2addr") && instruction.opcode != Opcode.CHECK_CAST
        val reads = if (plainWrite) named.drop(1) else named
        if (reads.any { it == register || wide && it + 1 == register }) return true
        handlers[n].forEach { if (seen.add(it)) pending += it }
        val writes = instruction.opcode.setsRegister() && (instruction as OneRegisterInstruction).registerA.let {
            it == register || instruction.opcode.setsWideRegister() && it + 1 == register
        }
        if (!writes) flow[n].forEach { if (seen.add(it)) pending += it }
    }
    return false
}
