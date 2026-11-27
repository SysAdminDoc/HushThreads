/*
 * Copyright 2026 HushThreads contributors
 * https://github.com/SysAdminDoc/HushThreads
 */
package app.morphe.patches.threads.share

import app.morphe.ExtensionDex
import app.morphe.FixtureDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.PerBuild
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.threads.misc.extension.SETTINGS_STATUS
import app.morphe.patches.threads.misc.extension.parameterRegisterNumber
import app.morphe.util.ControlFlow
import app.morphe.util.findMutableMethodOf
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Hide the Create group button on each declared build: the share sheet's bottom bar, the one
 * method of the send button's class that checks a boolean parameter right after drawing the send
 * button, asks the extension for that flag right in front of the check, and the check that skips
 * the Create group button still reads it. Anything that would make the hook miss the check, or the
 * check miss the button, is refused before the method changes.
 */
class HideCreateGroupButtonFixtureTest {
    @Test
    fun `each declared build asks the extension whether its share sheet draws Create group`() {
        val extension = ExtensionDex.classDef(CREATE_GROUP_BUTTON).methods.single { it.name == "show" }
        assertTrue(AccessFlags.STATIC.isSet(extension.accessFlags) && AccessFlags.PUBLIC.isSet(extension.accessFlags))
        assertEquals("Z", extension.parameterTypes.joinToString(""))
        assertEquals("Z", extension.returnType)

        for (build in Fixtures.declaredBuilds()) {
            val where = build.name
            // Read apart from the patch: one method sets the send button's tag, and one method of
            // its class calls it with an if-eqz on a boolean parameter right after.
            val classes = classes(build)
            val senders = classes.flatMap { it.methods }.filter { method -> method.body().any { it.isSendButtonTag() } }
            assertEquals("$where: methods setting the tag", 1, senders.size)
            val send = senders.single()
            val owner = classes.single { it.type == send.definingClass }
            val bars = owner.methods.filter { it.implementation != null }.flatMap { method ->
                val body = method.body()
                body.indices.filter { at ->
                    body[at].getReference<MethodReference>()?.toString() == send.signature() &&
                        body.getOrNull(at + 1)?.opcode == Opcode.IF_EQZ
                }.map { method to it + 1 }
            }
            assertEquals("$where: checks right after the send button", 1, bars.size)
            val (stock, guard) = bars.single()
            val stockBody = stock.body()
            val register = (stockBody[guard] as OneRegisterInstruction).registerA
            val parameter = stock.parameterTypes.indices.single { stock.parameterRegisterNumber(it) == register }
            assertEquals("$where: the flag", "Z", stock.parameterTypes[parameter].toString())
            // On 457 the flag is the bar's parameter 16, of 18, which it also hands the send button
            // so it reads Send separately; the flag sits past v15.
            assertEquals(where, 16, parameter)
            assertTrue(where, register > 15)
            // The branch the check skips draws the button with its label and click action.
            val skip = ControlFlow.of(stock).normal[guard].single { it != guard + 1 }
            assertTrue(where, skip > guard + 1)
            val buttons = (guard + 1 until skip).filter { at ->
                val call = stockBody[at].getReference<MethodReference>() ?: return@filter false
                call.definingClass == owner.type && "Ljava/lang/String;" in call.parameterTypes.map(CharSequence::toString)
            }
            assertEquals("$where: labelled buttons the check skips", 1, buttons.size)

            val context = context(build)
            assertEquals(where, 0, status(context))
            val bar = context.shareSheetBar()
            assertEquals(where, stock.signature(), bar.method.signature())
            assertEquals(where, parameter, bar.parameter)
            assertEquals(where, guard, bar.guard)
            assertEquals(where, buttons.single(), bar.button)
            hideCreateGroupButtonPatch.execute(context)

            val patched = context.mutableClassDefBy(stock.definingClass).methods.single { it.signature() == stock.signature() }
            val body = patched.body()
            assertEquals(where, stock.implementation!!.registerCount, patched.implementation!!.registerCount)
            assertEquals(where, stockBody.size + 2, body.size)
            // Everything before the check is as Threads wrote it, so the send button still gets
            // Threads' own flag and keeps reading Send separately.
            assertEquals(where, stockBody.take(guard).map { it.opcode }, body.take(guard).map { it.opcode })
            assertEquals(where, Opcode.INVOKE_STATIC_RANGE, body[guard].opcode)
            assertEquals(where, SHOW_CREATE_GROUP, body[guard].getReference<MethodReference>().toString())
            assertEquals(where, register, (body[guard] as RegisterRangeInstruction).startRegister)
            assertEquals(where, 1, (body[guard] as RegisterRangeInstruction).registerCount)
            // The answer goes back in the flag's own register and nowhere else.
            assertEquals(where, Opcode.MOVE_RESULT, body[guard + 1].opcode)
            assertEquals(where, register, (body[guard + 1] as OneRegisterInstruction).registerA)
            assertEquals(where, stockBody.drop(guard).map { it.opcode }, body.drop(guard + 2).map { it.opcode })
            // The check that skips the button reads the answer.
            assertEquals(where, Opcode.IF_EQZ, body[guard + 2].opcode)
            assertEquals(where, register, (body[guard + 2] as OneRegisterInstruction).registerA)
            assertEquals(where, 1, status(context))
        }
    }

    @Test
    fun `a flag written over before its check is refused`() {
        for (build in Fixtures.declaredBuilds()) {
            val context = context(build)
            val bar = context.shareSheetBar()
            val register = bar.method.parameterRegisterNumber(bar.parameter)
            context.mutable(bar.method).addInstructions(0, "const/16 v$register, 0x1")
            val error = assertThrows(build.name, PatchException::class.java) { hideCreateGroupButtonPatch.execute(context) }
            assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("writes over parameter ${bar.parameter}"))
            assertEquals(build.name, 0, status(context))
            assertFalse(build.name, context.mutable(bar.method).body().any { it.calls(SHOW_CREATE_GROUP) })
        }
    }

    @Test
    fun `a second send button tag is refused`() {
        for (build in Fixtures.declaredBuilds()) {
            val context = context(build)
            // A second method setting the tag leaves the patch unsure which is the send button.
            val bar = context.mutable(context.shareSheetBar().method)
            bar.addInstructions(0, "const-string v0, \"$SEND_BUTTON_TAG\"")
            val error = assertThrows(build.name, PatchException::class.java) { hideCreateGroupButtonPatch.execute(context) }
            assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("expected exactly one match, found 2"))
            assertEquals(build.name, 0, status(context))
        }
    }

    @Test
    fun `a jump onto the check is refused`() {
        for (build in Fixtures.declaredBuilds()) {
            val context = context(build)
            val bar = context.shareSheetBar()
            val method = context.mutable(bar.method)
            method.addInstructionsWithLabels(0, "goto :check", ExternalLabel("check", method.getInstruction(bar.guard)))
            val error = assertThrows(build.name, PatchException::class.java) { hideCreateGroupButtonPatch.execute(context) }
            assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("not only from the send button"))
            assertEquals(build.name, 0, status(context))
        }
    }

    private fun Method.body(): List<Instruction> = implementation!!.instructions.toList()

    private fun Method.signature() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

    private fun Instruction.calls(method: String) = getReference<MethodReference>()?.toString() == method

    private fun BytecodePatchContext.mutable(method: Method) = mutableClassDefBy(method.definingClass).findMutableMethodOf(method)

    private fun context(build: File) = PatchContexts.of(ExtensionDex.classes() + classes(build))

    /** The send button's class, whole: the bar is another of its methods. */
    private fun classes(build: File): List<ClassDef> = fixtures.getOrPut(build) {
        FixtureDex.classesWhere(build, { dex -> SEND_BUTTON_TAG in dex.stringSection }) { method ->
            method.implementation?.instructions?.any { it.isSendButtonTag() } == true
        }
    }

    private fun status(context: BytecodePatchContext) = (context.mutableClassDefBy(SETTINGS_STATUS).methods
        .single { it.name == "hideCreateGroupButton" }.implementation!!.instructions.first() as NarrowLiteralInstruction).narrowLiteral

    private companion object {
        /** One read of each build serves every test; each test patches its own copy. */
        val fixtures = PerBuild<List<ClassDef>>()
    }
}
