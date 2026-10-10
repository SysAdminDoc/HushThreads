/*
 * Forked from https://github.com/SysAdminDoc/Hushfacebook at c15d4f79 (GPL-3.0),
 * modified for HushThreads (Threads), 2026.
 *
 * Split out of RestoreTrustPatch.kt. The FBNS signer read itself was found by measuring #6 on
 * re-signed stock 450 (2026-10-06).
 */
package app.morphe.patches.threads.misc.resignedtrust

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.threads.misc.extension.threadsExtensionPatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val PACKAGE_INFO = "Landroid/content/pm/PackageInfo;"

private const val FBNS_SIGNERS = "Lapp/morphe/extension/hushthreads/misc/ThreadsSignature;->" +
    "fbnsSigners(Landroid/content/pm/PackageInfo;[Landroid/content/pm/Signature;)[Landroid/content/pm/Signature;"

/**
 * Part of every build, with no name to deselect it by: the settings patch, which every HushThreads
 * patch depends on, depends on this one. Re-signing alone is what makes FBNS reject Threads and leak
 * a thread each time the app comes back (#6), whatever else is patched in, so no selection may leave
 * it out. A Threads build it can't be placed in stops the whole run rather than ship without it.
 *
 * Its own file, so the settings patch can name it without a cycle between top-level values: the
 * re-signed screens patch depends on the settings patch, and a cycle would hand one of them null.
 */
internal val fbnsSignersPatch = bytecodePatch {
    dependsOn(threadsExtensionPatch)

    execute {
        FbnsPackageCheckFingerprint.method.routeFbnsSigners()
    }
}

/**
 * FBNS, the push service Meta's apps share, checks each package it may hand pushes to against
 * Meta's certificates, reading `PackageInfo.signatures` itself. On a re-signed build Threads fails
 * its own check, and each time the app comes back to the front FBNS starts another broadcast thread
 * that never ends (#6). The array that read gives goes through the extension, which answers the
 * original certificate for this app and the system's answer for any other package.
 *
 * The call goes straight after the read and names its two registers. `invoke-static` takes 4-bit
 * registers, so both must be v15 or lower, and the read must not overwrite the package it read
 * from, which the call still needs.
 */
internal fun MutableMethod.routeFbnsSigners() {
    val (index, info, signatures) = fbnsSignersRead()
    addInstructions(
        index + 1,
        """
            invoke-static { v$info, v$signatures }, $FBNS_SIGNERS
            move-result-object v$signatures
        """,
    )
}

/** FBNS's one read of `PackageInfo.signatures`: its index, the package register and the signatures register. */
internal fun MutableMethod.fbnsSignersRead(): Triple<Int, Int, Int> {
    val reads = instructions.withIndex().filter { (_, instruction) ->
        instruction.opcode == Opcode.IGET_OBJECT &&
            ((instruction as ReferenceInstruction).reference as FieldReference).let {
                it.definingClass == PACKAGE_INFO && it.name == "signatures"
            }
    }
    val (index, read) = reads.singleOrNull()
        ?: throw PatchException("FBNS signer fix: expected one read of PackageInfo.signatures in FBNS's package check, found ${reads.size}")
    val signatures = (read as TwoRegisterInstruction).registerA
    val info = read.registerB
    if (signatures == info || signatures > 15 || info > 15) {
        throw PatchException("FBNS signer fix: FBNS's package check reads signatures into v$signatures from v$info, which the call can't name")
    }
    return Triple(index, info, signatures)
}
