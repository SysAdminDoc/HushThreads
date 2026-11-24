/*
 * Copyright 2026 HushThreads contributors
 * https://github.com/SysAdminDoc/HushThreads
 */
package app.morphe.patches.threads.misc.theme

import app.morphe.ExtensionDex
import app.morphe.FixtureDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.PerBuild
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.threads.misc.extension.SETTINGS_STATUS
import app.morphe.util.getReference
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Custom font (#28) on each declared build: right after each of the font repository's three loads,
 * the typeface goes to the extension with what it was loaded by, and the answer takes its place.
 * The three loads are read by hand on 512708508, apart from how the patch finds them.
 */
class CustomFontFixtureTest {
    @Test
    fun `each declared build hands every repository load to the extension`() {
        val extension = ExtensionDex.classDef(CUSTOM_FONT)
        for ((name, signature) in listOf(
            "assetFont" to "(Landroid/graphics/Typeface;Ljava/lang/String;)Landroid/graphics/Typeface;",
            "systemFont" to "(Landroid/graphics/Typeface;Ljava/lang/String;)Landroid/graphics/Typeface;",
            "resourceFont" to "(Landroid/graphics/Typeface;I)Landroid/graphics/Typeface;",
        )) {
            val method = extension.methods.single { it.name == name }
            assertTrue(name, AccessFlags.STATIC.isSet(method.accessFlags) && AccessFlags.PUBLIC.isSet(method.accessFlags))
            assertEquals(name, signature, "(${method.parameterTypes.joinToString("")})${method.returnType}")
        }

        for (build in Fixtures.declaredBuilds()) {
            val where = build.name
            val context = context(build)
            assertEquals(where, 0, status(context))
            val repository = repository(build)
            val loads = context.fontLoads(repository)
            assertEquals(where, listOf("assetFont", "systemFont", "resourceFont"), loads.map { it.hook.substringBefore('(') })
            val stock = repository.body()
            for (load in loads) {
                // What each load is loaded by, read from the call itself.
                val call = stock[load.call].getReference<MethodReference>()!!
                assertEquals(where, Opcode.MOVE_RESULT_OBJECT, stock[load.call + 1].opcode)
                assertEquals(where, load.result, (stock[load.call + 1] as OneRegisterInstruction).registerA)
                val argument = if (load.hook.startsWith("systemFont")) 0 else 1
                assertEquals(where, stock[load.call].namedRegisters()[argument], load.name)
                assertEquals(where, "Landroid/graphics/Typeface;", call.returnType)
            }

            BY_HAND[where.substringAfterLast('-').substringBefore('.')]?.let { expected ->
                assertEquals(where, expected, listOf(repository.toString()) +
                    loads.map { "${it.call}: v${it.result} from v${it.name}" })
            }

            customFontPatch.execute(context)
            val patched = context.mutableClassDefBy(repository.definingClass).methods.single { it.same(repository) }.body()
            assertEquals(where, stock.size + 2 * loads.size, patched.size)
            // Each hook lands right after its load's move-result, shifted by the hooks above it.
            loads.sortedBy { it.call }.forEachIndexed { above, load ->
                val at = load.call + 2 + 2 * above
                assertEquals(where, Opcode.INVOKE_STATIC, patched[at].opcode)
                assertEquals(where, "$CUSTOM_FONT->${load.hook}", patched[at].getReference<MethodReference>().toString())
                assertEquals(where, listOf(load.result, load.name), patched[at].namedRegisters())
                assertEquals(where, Opcode.MOVE_RESULT_OBJECT, patched[at + 1].opcode)
                assertEquals(where, load.result, (patched[at + 1] as OneRegisterInstruction).registerA)
            }
            // Threads' own code is all still there, in order.
            val hooks = loads.sortedBy { it.call }.mapIndexed { above, load -> load.call + 2 + 2 * above }
                .flatMap { listOf(it, it + 1) }.toSet()
            assertEquals(where, stock.map { it.kind() }, patched.indices.filter { it !in hooks }.map { patched[it].kind() })
            assertEquals(where, 1, status(context))
        }
    }

    @Test
    fun `a second asset load is refused before anything changes`() {
        for (build in Fixtures.declaredBuilds()) {
            val context = context(build)
            val repository = repository(build)
            val mutable = context.mutableClassDefBy(repository.definingClass).methods.single { it.same(repository) }
            val first = mutable.body().indexOfFirst {
                it.getReference<MethodReference>()?.name == "createFromAsset"
            }
            val loaded = (mutable.body()[first + 1] as OneRegisterInstruction).registerA
            val call = mutable.body()[first].namedRegisters()
            mutable.addInstructions(first, """
                invoke-static { v${call[0]}, v${call[1]} }, Landroid/graphics/Typeface;->createFromAsset(Landroid/content/res/AssetManager;Ljava/lang/String;)Landroid/graphics/Typeface;
                move-result-object v$loaded
            """)
            val before = mutable.body().size
            val error = assertThrows(build.name, PatchException::class.java) { customFontPatch.execute(context) }
            assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("loads 2 asset fonts, not 1"))
            assertEquals(build.name, before, mutable.body().size)
            assertEquals(build.name, 0, status(context))
        }
    }

    @Test
    fun `a resource load from a class that isn't ResourcesCompat is refused`() {
        for (build in Fixtures.declaredBuilds()) {
            val context = context(build)
            val repository = repository(build)
            val loader = context.fontLoads(repository).single { it.hook.startsWith("resourceFont") }
            val owner = repository.body()[loader.call].getReference<MethodReference>()!!.definingClass
            // Without its message, nothing says the class loads font resources.
            context.mutableClassDefBy(owner).methods.forEach { method ->
                val strings = method.body().withIndex().filter {
                    it.value.getReference<StringReference>()?.string?.startsWith(FONT_RESOURCE) == true
                }
                strings.forEach { (index, instruction) ->
                    method.implementation!!.replaceInstruction(index,
                        com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c(
                            Opcode.CONST_STRING, (instruction as OneRegisterInstruction).registerA,
                            com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference("other"),
                        ))
                }
            }
            val error = assertThrows(build.name, PatchException::class.java) { customFontPatch.execute(context) }
            assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("loads 0 resource fonts, not 1"))
            assertEquals(build.name, 0, status(context))
        }
    }

    private fun repository(build: File): Method = fixture(build).classes.flatMap { it.methods }.single { method ->
        method.returnType == "Landroid/graphics/Typeface;" && method.parameterTypes.size == 1 &&
            method.body().any { it.string() == LOAD_TYPEFACE }
    }

    private class Fixture(val classes: List<ClassDef>)

    /** The repository's class and ResourcesCompat, each found by its own string, read once per build. */
    private fun fixture(build: File): Fixture = fixtures.getOrPut(build) {
        val holding = { text: String -> { method: Method -> method.body().any { it.string()?.startsWith(text) == true } } }
        Fixture((FixtureDex.classesWhere(build, { true }, holding(LOAD_TYPEFACE)) +
            FixtureDex.classesWhere(build, { true }, holding(FONT_RESOURCE))).distinctBy { it.type })
    }

    private fun context(build: File): BytecodePatchContext {
        TypefaceRepositoryFingerprint.clearMatch()
        return PatchContexts.of((ExtensionDex.classes() + fixture(build).classes).distinctBy { it.type })
    }

    private fun status(context: BytecodePatchContext) = (context.mutableClassDefBy(SETTINGS_STATUS).methods
        .single { it.name == "customFont" }.implementation!!.instructions.first() as NarrowLiteralInstruction).narrowLiteral

    private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Method.same(other: Method) = definingClass == other.definingClass && name == other.name &&
        returnType == other.returnType && parameterTypes.map { it.toString() } == other.parameterTypes.map { it.toString() }

    private fun Instruction.string() =
        if (opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO) getReference<StringReference>()?.string else null

    /** An insert can push a branch past a plain goto's reach, and dexlib2 widens it. */
    private fun Instruction.kind() = when (opcode) {
        Opcode.GOTO_16, Opcode.GOTO_32 -> Opcode.GOTO
        else -> opcode
    }

    private companion object {
        /** 512708508's repository, by hand from its dump: createFromAsset, Typeface.create and ResourcesCompat.getFont. */
        val BY_HAND = mapOf(
            "512708508" to listOf(
                "LX/9qj;->A03(LX/9qg;)Landroid/graphics/Typeface;",
                "164: v5 from v1", "66: v5 from v2", "75: v5 from v1",
            ),
        )

        /** One read of each build serves every test; each test patches its own copy. */
        val fixtures = PerBuild<Fixture>()
    }
}
