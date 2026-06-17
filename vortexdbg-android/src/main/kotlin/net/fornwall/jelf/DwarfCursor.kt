package net.fornwall.jelf

abstract class DwarfCursor protected constructor(loc: Array<Long?>) {

    @JvmField
    var cfa: Long = 0 /* canonical frame address; aka frame-/stack-pointer */
    @JvmField
    var ip: Long = 0 /* instruction pointer */

    @JvmField
    val loc: Array<Long?> = loc

}
