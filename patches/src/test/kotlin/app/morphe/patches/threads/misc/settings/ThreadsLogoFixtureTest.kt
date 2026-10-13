/*
 * Copyright 2026 HushThreads contributors
 * https://github.com/SysAdminDoc/HushThreads
 */
package app.morphe.patches.threads.misc.settings

import app.morphe.ExtensionDex
import app.morphe.FixtureDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.threads.misc.extension.PatchLogCapture
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The long press on the Threads logo, on each declared build: the main feed header's one call to
 * Compose's clickable that's handed the logo's tap goes to the extension's stand-in, whose body calls
 * Compose's combined clickable with Threads' arguments, the extension's long press and Threads' tap.
 */
class ThreadsLogoFixtureTest {
    @Test
    fun `the extension's stand-in and long press have the shapes the patch writes against`() {
        val logo = ExtensionDex.classDef(THREADS_LOGO)
        val stub = logo.methods.single { it.name == "clickable" }
        assertTrue(AccessFlags.STATIC.isSet(stub.accessFlags) && AccessFlags.PUBLIC.isSet(stub.accessFlags))
        assertEquals(LOGO_CLICKABLE, stub.signature())
        val field = logo.fields.single { it.name == "LONG_PRESS" }
        assertTrue(AccessFlags.STATIC.isSet(field.accessFlags) && AccessFlags.FINAL.isSet(field.accessFlags))
        assertEquals(LOGO_LONG_PRESS_FIELD, "${field.definingClass}->${field.name}:${field.type}")
        val press = ExtensionDex.classDef(THREADS_LOGO_LONG_PRESS)
        assertTrue(press.interfaces.isEmpty())
        val invoke = press.methods.single { it.name == "invoke" }
        assertTrue(AccessFlags.PUBLIC.isSet(invoke.accessFlags) && !AccessFlags.STATIC.isSet(invoke.accessFlags))
        assertEquals("Ljava/lang/Object;", invoke.returnType)
        assertTrue(invoke.parameterTypes.isEmpty())
    }

    /**
     * Read apart from the patch: the lambda that sends the LogoTap refresh reason is built in one
     * method of the whole build, which holds the main feed's Compose note, and the first static call
     * after it is built is the clickable the patch picks. Compose's combined clickable sits beside it
     * and hands its node the tap from its last Function0 and the long press from its first.
     */
    @Test
    fun `each declared build builds the logo's tap once, in the main feed header, and the patch finds its clickable`() {
        for (build in Fixtures.declaredBuilds()) {
            val where = build.name
            val fixture = fixture(build)
            val site = context(fixture).logoSite()
            assertEquals("$where: the feed header", fixture.header.signature(), site.method.signature())
            assertEquals("$where: the clickable call", fixture.call, site.call)
            assertEquals("$where: the logo's tap", fixture.tap, site.tap)
            assertEquals("$where: the clickable", fixture.clickable, site.clickable.signature())
            assertEquals("$where: the combined clickable sits beside it", site.clickable.definingClass, site.combined.definingClass)
            val clickableTypes = site.clickable.parameterTypes.map { it.toString() }
            assertEquals(
                "$where: the combined clickable's parameters",
                clickableTypes.take(4) + listOf(STRING, STRING, FUNCTION0, FUNCTION0, FUNCTION0, "Z"),
                site.combined.parameterTypes.map { it.toString() },
            )
            assertEquals("$where: tap, long press and double tap", listOf(8, 6, 7), handlerOrder(site.combined))
        }
    }

    @Test
    fun `each declared build sends the logo's clickable to the stand-in, which adds the long press`() {
        for (build in Fixtures.declaredBuilds()) {
            val where = build.name
            val fixture = fixture(build)
            val stock = fixture.header.body()
            val stockCall = stock[fixture.call] as RegisterRangeInstruction
            val result = (stock[fixture.call + 1] as OneRegisterInstruction).registerA

            val context = context(fixture)
            val combined = context.logoSite().combined
            context.addLogoLongPress()

            val header = context.header(fixture)
            val patched = header.body()
            assertEquals("$where: one instruction added", stock.code().size + 1, patched.code().size)
            assertEquals("$where: no new registers", fixture.header.implementation!!.registerCount,
                header.implementation!!.registerCount)
            val call = patched[fixture.call] as RegisterRangeInstruction
            assertEquals("$where: the stand-in", Opcode.INVOKE_STATIC_RANGE, patched[fixture.call].opcode)
            assertEquals(LOGO_CLICKABLE, patched[fixture.call].method().toString())
            assertEquals("$where: Threads' own registers", stockCall.startRegister to stockCall.registerCount,
                call.startRegister to call.registerCount)
            assertEquals("$where: the answer is taken as before", Opcode.MOVE_RESULT_OBJECT, patched[fixture.call + 1].opcode)
            assertEquals(result, (patched[fixture.call + 1] as OneRegisterInstruction).registerA)
            val cast = patched[fixture.call + 2]
            assertEquals("$where: then cast back to the modifier", Opcode.CHECK_CAST, cast.opcode)
            assertEquals(result, (cast as OneRegisterInstruction).registerA)
            assertEquals(fixture.clickableReturn, cast.getReference<TypeReference>()!!.type)
            assertEquals("$where: the rest is the stock header",
                stock.filterIndexed { index, _ -> index != fixture.call }.code().map { it.opcode },
                patched.filterIndexed { index, _ -> index != fixture.call && index != fixture.call + 2 }.code().map { it.opcode })
            assertEquals("$where: no clickable left for the logo", 0, patched.count { it.method()?.toString() == fixture.clickable })

            // The stand-in: Threads' arguments cast back, its translated label, the extension's long
            // press, no double tap, Threads' tap and its enabled flag, into the combined clickable.
            val stubMethod = context.mutableClassDefBy(THREADS_LOGO).methods.single { it.name == "clickable" }
            val stub = stubMethod.body()
            val parameter = { index: Int -> stubMethod.implementation!!.registerCount - 7 + index }
            val at = stub.indexOfFirst { it.opcode == Opcode.INVOKE_STATIC_RANGE }
            val into = stub[at] as RegisterRangeInstruction
            assertEquals("$where: the combined clickable", combined.signature(), stub[at].method().toString())
            assertEquals(10, into.registerCount)
            fun writer(argument: Int) = stub.subList(0, at).last { (it as? OneRegisterInstruction)?.registerA == into.startRegister + argument }
            fun copiedFrom(argument: Int): Int {
                val write = stub.subList(0, at).indexOfLast { (it as? OneRegisterInstruction)?.registerA == into.startRegister + argument &&
                    it.opcode != Opcode.CHECK_CAST }
                return (stub[write] as TwoRegisterInstruction).registerB
            }
            for (argument in 0..3) {
                assertEquals("$where: argument $argument", combined.parameterTypes[argument].toString(),
                    writer(argument).takeIf { it.opcode == Opcode.CHECK_CAST }?.getReference<TypeReference>()?.type)
                assertEquals("$where: argument $argument comes from Threads", parameter(argument), copiedFrom(argument))
            }
            assertEquals("$where: Threads' label", parameter(4), copiedFrom(4))
            val label = writer(5)
            assertEquals("$where: the accessibility label", Opcode.MOVE_RESULT_OBJECT, label.opcode)
            assertEquals("$THREADS_LOGO->longClickLabel()Ljava/lang/String;",
                stub[stub.indexOf(label) - 1].method().toString())
            assertEquals("$where: no double tap", 0, (writer(7) as NarrowLiteralInstruction).narrowLiteral)
            assertEquals("$where: the long press", LOGO_LONG_PRESS_FIELD,
                writer(6).takeIf { it.opcode == Opcode.SGET_OBJECT }?.getReference<FieldReference>().toString())
            assertEquals("$where: Threads' tap", FUNCTION0,
                writer(8).takeIf { it.opcode == Opcode.CHECK_CAST }?.getReference<TypeReference>()?.type)
            assertEquals("$where: Threads' tap comes from Threads", parameter(5), copiedFrom(8))
            assertEquals("$where: Threads' enabled flag", parameter(6), copiedFrom(9))
            assertEquals(Opcode.MOVE_RESULT_OBJECT, stub[at + 1].opcode)
            assertEquals(Opcode.RETURN_OBJECT, stub.last().opcode)

            val press = context.mutableClassDefBy(THREADS_LOGO_LONG_PRESS)
            assertEquals("$where: the long press is a Function0", listOf(FUNCTION0), press.interfaces.toList())
        }
    }

    /**
     * Each way the build could stop matching what was read refuses it before anything is written: a
     * second header that builds the logo's tap, no LogoTap reason, no answer taken right after the
     * clickable, and a combined clickable that would hand the long press to the node as the tap.
     */
    @Test
    fun `a feed header or a clickable that differs from the one read is refused`() {
        for (build in Fixtures.declaredBuilds()) {
            val fixture = fixture(build)
            val cases = listOf<Pair<String, (BytecodePatchContext) -> Unit>>(
                "found 2" to { context ->
                    val method = fixture.header
                    context.mutableClassDefBy(method.definingClass).methods.add(MutableMethod(ImmutableMethod(
                        method.definingClass, "copyOf${method.name}", method.parameters, method.returnType,
                        method.accessFlags, null, null, ImmutableMethodImplementation.of(method.implementation),
                    )))
                },
                "\"$LOGO_TAP\", an enum constant" to { context ->
                    val enum = context.mutableClassDefBy(fixture.reasons).methods.single { it.name == "<clinit>" }
                    val at = enum.body().indexOfFirst { it.getReference<StringReference>()?.string == LOGO_TAP }
                    val register = (enum.body()[at] as OneRegisterInstruction).registerA
                    enum.replaceInstruction(at, "const-string v$register, \"LogoPress\"")
                },
                "right after its clickable" to { context ->
                    context.header(fixture).addInstruction(fixture.call + 1, "nop")
                },
                "the long press could land on the tap" to { context ->
                    val owner = context.mutableClassDefBy(fixture.clickable.substringBefore("->"))
                    val combined = owner.methods.single { it.signature() == fixture.combined }
                    val first = combined.implementation!!.registerCount - 10
                    val at = combined.body().indexOfFirst {
                        it.opcode in setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16) &&
                            (it as TwoRegisterInstruction).registerB == first + 6
                    }
                    val register = (combined.body()[at] as OneRegisterInstruction).registerA
                    combined.replaceInstruction(at, "move-object/from16 v$register, v${first + 7}")
                },
            )
            for ((expected, mutate) in cases) {
                val context = context(fixture)
                mutate(context)
                val error = assertThrows("${build.name}: $expected", PatchException::class.java) { context.logoSite() }
                    .message.orEmpty()
                assertTrue("${build.name}: $error", error.contains(expected))
            }
        }
    }

    /**
     * Every patch depends on the settings patch, so a build the long press doesn't fit gets the rest
     * of it: the row in Threads' settings still goes in, and the patch log names why the long press
     * was left out.
     */
    @Test
    fun `a build the long press doesn't fit gets the rest of the settings patch and a warning`() {
        val context = PatchContexts.of(SettingsPatchHosts.all())
        val warnings = PatchLogCapture.warnings { settingsPatch.execute(context) }

        assertEquals(warnings.toString(), 1, warnings.size)
        assertTrue(warnings.single(), warnings.single().contains("\"$LOGO_TAP\"") &&
            warnings.single().contains("long press on the Threads logo is left out"))
        val list = context.mutableClassDefBy("Lfixture/SettingsScreen;").methods.single { it.name == "list" }.body()
        assertTrue("the row still goes in", list.any { it.method()?.toString() == ADD_SETTINGS_ROW })
    }

    private data class Fixture(
        val classes: Collection<ClassDef>,
        val header: Method,
        val call: Int,
        val tap: String,
        val clickable: String,
        val clickableReturn: String,
        val combined: String,
        val reasons: String,
    )

    private fun fixture(build: File): Fixture = fixtures.getOrPut(build) {
        val where = build.name
        val noted = FixtureDex.classesWhere(build, { true }) { method ->
            method.holdsString(MAIN_FEED_SCREEN_NOTE) || method.name == "<clinit>" && method.body().any { it.string() == LOGO_TAP }
        }
        // The reason is the field the enum's static initializer stores after it builds LogoTap.
        val initializer = noted.flatMap { it.methods }.single { it.name == "<clinit>" && it.body().any { i -> i.string() == LOGO_TAP } }
        val enumBody = initializer.body()
        val reason = enumBody.drop(enumBody.indexOfFirst { it.string() == LOGO_TAP })
            .first { it.opcode == Opcode.SPUT_OBJECT }.getReference<FieldReference>()!!
        val reasonName = "${reason.definingClass}->${reason.name}:${reason.type}"

        val taps = FixtureDex.methodsWhere(build, { true }) { method ->
            method.name == "invoke" && method.parameterTypes.isEmpty() && method.returnType == "Ljava/lang/Object;" &&
                method.body().any { it.opcode == Opcode.SGET_OBJECT && it.getReference<FieldReference>()?.let { f ->
                    "${f.definingClass}->${f.name}:${f.type}" } == reasonName }
        }.map { it.definingClass }.toSet()
        assertEquals("$where: one lambda sends $reasonName", 1, taps.size)
        val tap = taps.single()
        val header = FixtureDex.methodsWhere(build, { true }) { method ->
            method.body().any { it.opcode == Opcode.NEW_INSTANCE && it.getReference<TypeReference>()?.type == tap }
        }.single()
        assertTrue("$where: ${header.signature()} holds the main feed's note",
            header.body().any { it.string()?.let { s -> s.startsWith(MAIN_FEED_SCREEN_NOTE) && "(MainFeedScreen.kt:" in s } == true })
        val body = header.body()
        val built = body.indexOfFirst { it.opcode == Opcode.NEW_INSTANCE && it.getReference<TypeReference>()?.type == tap }
        val call = (built + 1 until body.size).first {
            body[it].opcode == Opcode.INVOKE_STATIC || body[it].opcode == Opcode.INVOKE_STATIC_RANGE
        }
        val clickable = body[call].method()!!
        val owner = FixtureDex.classes(build, setOf(clickable.definingClass)).getValue(clickable.definingClass)
        val combined = owner.methods.single { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.size == 10 &&
                method.parameterTypes.map { it.toString() }.count { it == FUNCTION0 } == 3 && method.returnType == clickable.returnType
        }
        val read = FixtureDex.classes(build, setOf(tap, header.definingClass, FUNCTION0))
        Fixture(
            (noted + owner + read.values).distinctBy { it.type }, header, call, tap, clickable.signature(),
            clickable.returnType, combined.signature(), reason.definingClass,
        )
    }

    private fun context(fixture: Fixture) =
        PatchContexts.of((ExtensionDex.classes() + fixture.classes).distinctBy { it.type })

    private fun BytecodePatchContext.header(fixture: Fixture) =
        mutableClassDefBy(fixture.header.definingClass).methods.single { it.signature() == fixture.header.signature() }

    private fun Method.holdsString(prefix: String) = body().any { it.string()?.startsWith(prefix) == true }

    private fun Instruction.string(): String? = getReference<StringReference>()?.string

    private fun Instruction.method(): MethodReference? = getReference<MethodReference>()

    private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun List<Instruction>.code() = filter { it.opcode != Opcode.NOP }

    private fun MethodReference.signature() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

    private companion object {
        const val STRING = "Ljava/lang/String;"
        val fixtures = HashMap<File, Fixture>()
    }
}
