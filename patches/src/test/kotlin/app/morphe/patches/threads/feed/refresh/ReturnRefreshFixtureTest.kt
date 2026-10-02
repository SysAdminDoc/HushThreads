/*
 * Copyright 2026 HushThreads contributors
 * https://github.com/SysAdminDoc/HushThreads
 */
package app.morphe.patches.threads.feed.refresh

import app.morphe.ExtensionDex
import app.morphe.FixtureDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.threads.misc.extension.SETTINGS_STATUS
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Block background-return feed refresh on each declared build: each of Threads' three return
 * checks is found exactly once, and each asks the extension at the one place its answer is made.
 */
class ReturnRefreshFixtureTest {
    private val warmStart = "$RETURN_REFRESH->warmStart(Z)Z"
    private val resetToFeed = "$RETURN_REFRESH->resetToFeed(Z)Z"
    private val holdHotStart = "$RETURN_REFRESH->holdHotStart()Z"
    private val cachedPosts = "$RETURN_REFRESH->cachedPosts(Z)Z"

    @Test
    fun `the extension answers each check with a boolean`() {
        val extension = ExtensionDex.classDef(RETURN_REFRESH)
        for ((name, parameters) in listOf("warmStart" to listOf("Z"), "resetToFeed" to listOf("Z"), "holdHotStart" to emptyList(),
                "cachedPosts" to listOf("Z"))) {
            val method = extension.methods.single { it.name == name }
            assertTrue(name, AccessFlags.STATIC.isSet(method.accessFlags) && AccessFlags.PUBLIC.isSet(method.accessFlags))
            assertEquals(name, parameters, method.parameterTypes.map { it.toString() })
            assertEquals(name, "Z", method.returnType)
        }
    }

    @Test
    fun `each anchor is held by exactly one method of every declared build`() {
        for (build in Fixtures.declaredBuilds()) {
            val warm = FixtureDex.methodsWhere(build, { true }) { it.holdsString(TOO_SHORT) && it.holdsString(WALL_CLOCK) }
            assertEquals(build.name, 1, warm.size)
            assertEquals(build.name, "Z", warm.single().parameterTypes.last().toString())
            val reset = FixtureDex.methodsWhere(build, { true }) { it.holdsString(RESET_TO_MAIN_FEED) }
            assertEquals(build.name, listOf(BARCELONA_ACTIVITY), reset.map { it.definingClass })
            val handler = FixtureDex.methodsWhere(build, { true }) { it.holdsString(BADGE_DECISION) }
            assertEquals(build.name, listOf(BARCELONA_ACTIVITY), handler.map { it.definingClass })
            // The swap to background posts compares the warm-start threshold once more, in the same class.
            val thresholds = warm.single().wideLiterals()
            val swaps = FixtureDex.classes(build, setOf(warm.single().definingClass)).values.single().methods
                .flatMap { it.cachedPostsSites(thresholds) }
            assertEquals(build.name, 1, swaps.size)
        }
    }

    @Test
    fun `every check asks the extension where its answer is made`() {
        for (build in Fixtures.declaredBuilds()) {
            val fixture = fixture(build)
            val context = context(fixture)
            blockReturnRefreshPatch.execute(context)

            val warm = context.method(fixture.warm)
            val site = fixture.warm.warmStartSite()
            val call = warm.indexOfCall(warmStart)
            assertEquals(build.name, site.store, call)
            assertEquals(build.name, site.register, (warm[call] as RegisterRangeInstruction).startRegister)
            assertEquals(build.name, Opcode.MOVE_RESULT, warm[call + 1].opcode)
            assertEquals(build.name, site.register, (warm[call + 1] as OneRegisterInstruction).registerA)
            assertEquals(build.name, Opcode.IPUT_BOOLEAN, warm[call + 2].opcode)
            assertEquals(build.name, site.register, (warm[call + 2] as TwoRegisterInstruction).registerA)

            val reset = context.method(fixture.reset)
            val resetCall = reset.indexOfCall(resetToFeed)
            val returned = (reset.last() as OneRegisterInstruction).registerA
            assertEquals(build.name, reset.size - 3, resetCall)
            assertEquals(build.name, returned, (reset[resetCall] as RegisterRangeInstruction).startRegister)
            assertEquals(build.name, returned, (reset[resetCall + 1] as OneRegisterInstruction).registerA)

            // The if-gez that skips the false lands on the hook, so both answers are asked.
            val swap = context.method(fixture.cached.method)
            val swapCall = swap.indexOfCall(cachedPosts)
            val answer = fixture.cached.register
            assertEquals(build.name, answer, (swap[swapCall] as RegisterRangeInstruction).startRegister)
            assertEquals(build.name, Opcode.MOVE_RESULT, swap[swapCall + 1].opcode)
            assertEquals(build.name, answer, (swap[swapCall + 1] as OneRegisterInstruction).registerA)
            assertEquals(build.name, Opcode.CONST_4, swap[swapCall - 1].opcode)
            assertEquals(build.name, answer, (swap[swapCall - 1] as OneRegisterInstruction).registerA)
            assertEquals(build.name, Opcode.IF_GEZ, swap[swapCall - 2].opcode)
            assertEquals(build.name, swap.addressOf(swapCall),
                swap.addressOf(swapCall - 2) + (swap[swapCall - 2] as OffsetInstruction).codeOffset)
            assertEquals(build.name, fixture.cached.method.implementation!!.instructions.toList()[fixture.cached.join].opcode,
                swap[swapCall + 2].opcode)

            val hot = context.method(fixture.hotStart)
            val stock = fixture.hotStart.implementation!!.instructions.toList()
            assertEquals(build.name, 0, hot.indexOfCall(holdHotStart))
            assertEquals(build.name, listOf(Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4, Opcode.RETURN_OBJECT),
                hot.subList(1, 5).map { it.opcode })
            assertEquals(build.name, 0, (hot[3] as NarrowLiteralInstruction).narrowLiteral)
            // The no-hold branch skips its own two code units and the two of the null return, landing
            // on Threads' own first instruction, which now follows the hook.
            assertEquals(build.name, stock.first().opcode, hot[5].opcode)
            assertEquals(build.name, 4, (hot[2] as OffsetInstruction).codeOffset)

            assertEquals(build.name, 1, status(context))
        }
    }

    @Test
    fun `a warm-start check that logs twice or loses its answer is refused`() {
        for (build in Fixtures.declaredBuilds()) {
            val fixture = fixture(build)
            val site = fixture.warm.warmStartSite()
            val cases = listOf(
                "second log" to (site.store to "const-string v${if (site.register == 0) 1 else 0}, \"$TOO_SHORT\""),
                "overwritten answer" to (site.store to "const/4 v${site.register}, 0x1"),
            )
            for ((label, change) in cases) {
                val context = context(fixture)
                context.mutableMethod(fixture.warm).addInstructions(change.first, change.second)
                assertThrows("$build $label", PatchException::class.java) { blockReturnRefreshPatch.execute(context) }
            }
        }
    }

    @Test
    fun `a swap whose answer is overwritten, or a second swap, is refused`() {
        for (build in Fixtures.declaredBuilds()) {
            val fixture = fixture(build)
            val site = fixture.cached
            val threshold = fixture.cached.method.implementation!!.instructions.toList()
                .subList(maxOf(0, site.join - 12), site.join)
                .filter { it.opcode == Opcode.CONST_WIDE }
                .map { (it as WideLiteralInstruction).wideLiteral }
                .single { it in fixture.warm.wideLiterals() }

            val overwritten = context(fixture)
            overwritten.mutableMethod(site.method).addInstructions(site.join, "const/4 v${site.register}, 0x1")
            val lost = assertThrows("$build overwritten", PatchException::class.java) { blockReturnRefreshPatch.execute(overwritten) }
            assertTrue(lost.message.orEmpty(), lost.message.orEmpty().contains("found 0"))

            val twice = context(fixture)
            twice.mutableMethod(site.method).addInstructions(
                0,
                """
                    const-wide v0, ${threshold}L
                    cmp-long v2, v0, v0
                    const/4 v3, 0x1
                    if-gez v2, :again
                    const/4 v3, 0x0
                    :again
                    if-eqz v3, :done
                    :done
                    nop
                """,
            )
            val second = assertThrows("$build second", PatchException::class.java) { blockReturnRefreshPatch.execute(twice) }
            assertTrue(second.message.orEmpty(), second.message.orEmpty().contains("found 2"))
        }
    }

    @Test
    fun `a reset to main feed with a second answer is refused`() {
        for (build in Fixtures.declaredBuilds()) {
            val fixture = fixture(build)
            val context = context(fixture)
            context.mutableMethod(fixture.reset).addInstructions(0, "const/4 v0, 0x0\nreturn v0")
            val error = assertThrows(build.name, PatchException::class.java) { blockReturnRefreshPatch.execute(context) }
            assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("Candidates"))
        }
    }

    private data class Fixture(
        val classes: Collection<ClassDef>,
        val warm: Method,
        val reset: Method,
        val hotStart: Method,
        val cached: CachedPostsSite,
    )

    private fun fixture(build: File): Fixture {
        val warmClasses = FixtureDex.classesWhere(build, { true }) { it.holdsString(TOO_SHORT) }
        val activity = FixtureDex.classes(build, setOf(BARCELONA_ACTIVITY)).getValue(BARCELONA_ACTIVITY)
        val handler = activity.methods.single { it.holdsString(BADGE_DECISION) }
        val decision = activity.methods.single { it.name == "onStart" }.implementation!!.instructions
            .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
            .filter { it.returnType == handler.parameterTypes.single().toString() && it.parameterTypes.size == 4 }
            .distinctBy { it.toString() }.single()
        val decisionClass = FixtureDex.classes(build, setOf(decision.definingClass)).getValue(decision.definingClass)
        val classes = (warmClasses + activity + decisionClass).distinctBy { it.type }
        val warm = warmClasses.flatMap { it.methods }.single { it.holdsString(TOO_SHORT) }
        val cached = warmClasses.single { it.type == warm.definingClass }.methods
            .flatMap { it.cachedPostsSites(warm.wideLiterals()) }.single()
        return Fixture(
            classes,
            warm,
            activity.methods.single { it.holdsString(RESET_TO_MAIN_FEED) },
            decisionClass.methods.single { it.name == decision.name && it.parameterTypes.map(CharSequence::toString) ==
                decision.parameterTypes.map(CharSequence::toString) && it.returnType == decision.returnType },
            cached,
        )
    }

    private fun context(fixture: Fixture) = PatchContexts.of(ExtensionDex.classes() + fixture.classes)

    private fun BytecodePatchContext.mutableMethod(method: Method) = mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
    }

    private fun BytecodePatchContext.method(method: Method): List<Instruction> =
        mutableMethod(method).implementation!!.instructions.toList()

    private fun List<Instruction>.addressOf(index: Int) = subList(0, index).sumOf { it.codeUnits }

    private fun List<Instruction>.indexOfCall(reference: String) =
        indices.single { (this[it] as? ReferenceInstruction)?.reference?.toString() == reference }

    private fun status(context: BytecodePatchContext) = (context.mutableClassDefBy(SETTINGS_STATUS).methods
        .single { it.name == "returnRefresh" }.implementation!!.instructions.first() as NarrowLiteralInstruction).narrowLiteral
}
