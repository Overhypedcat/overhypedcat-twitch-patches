package app.morphed.patches.twitch.ad.client

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.getReference
import app.morphed.patches.twitch.shared.Constants.COMPATIBILITY_TWITCH
import app.morphed.patches.twitch.shared.indicesOf
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val CLIENT_ADS_CLASS = "Lapp/morphed/extension/twitch/patches/ClientAdsPatch;"

@Suppress("unused")
val blockClientAdsPatch = bytecodePatch(
    name = "Block client-side ads",
    description = "Makes the app treat every ad eligibility check like a Turbo viewer, so it never starts " +
        "the ads it plays itself (a black screen while their ad server is blocked).",
    default = true
) {
    compatibleWith(COMPATIBILITY_TWITCH)

    extendWith("extensions/twitch.mpe")

    execute {
        AdIneligibilityReasonsFingerprint.method.apply {
            // The reasons enum is obfuscated, field names included. It is the enum whose own constants
            // the method reads (a static field of its own type); the extension finds the Turbo reason
            // in it by the constant's name, which the enum keeps.
            val reasonClass = implementation!!.instructions.firstNotNullOfOrNull { instruction ->
                if (instruction.opcode != Opcode.SGET_OBJECT) return@firstNotNullOfOrNull null
                instruction.getReference<FieldReference>()
                    ?.takeIf { it.type == it.definingClass }
                    ?.definingClass
            } ?: error("Ad ineligibility reasons enum not found in $definingClass->$name")

            indicesOf(Opcode.RETURN_OBJECT).forEach { returnIndex ->
                val set = getInstruction<OneRegisterInstruction>(returnIndex).registerA
                // The method ends here, so any other low register is free to hold the class.
                val type = if (set == 0) 1 else 0
                check(set <= 15 && implementation!!.registerCount > 1) { "Unexpected registers in $name" }
                addInstructions(
                    returnIndex,
                    """
                        const-class v$type, $reasonClass
                        invoke-static { v$set, v$type }, $CLIENT_ADS_CLASS->markIneligible(Ljava/util/Set;Ljava/lang/Class;)V
                    """
                )
            }
        }
    }
}
