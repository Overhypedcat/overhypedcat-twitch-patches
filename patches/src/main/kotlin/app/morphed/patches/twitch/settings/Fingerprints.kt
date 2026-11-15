package app.morphed.patches.twitch.settings

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** The app's Application class: everything else starts after its onCreate begins. */
object ApplicationOnCreateFingerprint : Fingerprint(
    definingClass = "Ltv/twitch/android/app/consumer/TwitchApplication;",
    name = "onCreate",
    returnType = "V",
    parameters = listOf(),
)

/**
 * A Settings row composable: (title, description, onClick, icon, modifier, composer, changed, defaults).
 */
internal fun isSettingsRow(reference: MethodReference): Boolean {
    val types = reference.parameterTypes.map { it.toString() }
    return reference.returnType == "V" && types.size == 8 &&
        types[0] == "Ljava/lang/String;" && types[1] == "Ljava/lang/String;" &&
        types[2].startsWith("L") && types[3] == "I" && types[4].startsWith("L") && types[5].startsWith("L") &&
        types[6] == "I" && types[7] == "I"
}

/** The indexes of a method's calls to the Settings row composable. */
internal fun settingsRowCalls(method: Method): List<Int> {
    val instructions = method.implementation?.instructions?.toList() ?: return emptyList()
    val calls = instructions.withIndex().filter { (_, instruction) ->
        instruction.opcode == Opcode.INVOKE_STATIC_RANGE &&
            instruction.getReference<MethodReference>()?.let(::isSettingsRow) == true
    }
    // All of them to the same row composable.
    val target = calls.firstOrNull()?.value?.getReference<MethodReference>()?.toString() ?: return emptyList()
    return calls.filter { it.value.getReference<MethodReference>().toString() == target }.map { it.index }
}

/**
 * The Settings page's account group (Account, Preferences, Notifications, Content Preferences, Family Center,
 * Security & Privacy, Connections), built with the row composable (seven times in 31.8.0; a row more or less is
 * still found) and closed with a divider. The group above it has six rows. Jetpack
 * Compose: the names are obfuscated, so it's found by that shape.
 */
object SettingsAccountGroupFingerprint : Fingerprint(
    returnType = "V",
    custom = { method, _ ->
        method.parameterTypes.firstOrNull()?.toString() == "Z" && settingsRowCalls(method).size in 7..9 &&
            method.parameterTypes.size >= 8
    },
)
