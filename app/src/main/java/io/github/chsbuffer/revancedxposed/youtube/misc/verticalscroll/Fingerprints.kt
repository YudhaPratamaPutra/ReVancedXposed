package io.github.chsbuffer.revancedxposed.youtube.misc.verticalscroll

import io.github.chsbuffer.revancedxposed.AccessFlags
import io.github.chsbuffer.revancedxposed.Opcode
import io.github.chsbuffer.revancedxposed.fingerprint
import org.luckypray.dexkit.query.enums.StringMatchType

val canScrollVerticallyFingerprint = fingerprint {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returns("Z")
    parameters()
    opcodes(
        Opcode.MOVE_RESULT,
        Opcode.RETURN,
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT
    )
    classMatcher { className(".SwipeRefreshLayout", StringMatchType.EndsWith) }
}