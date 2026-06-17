package net.fornwall.jelf

import com.vortexdbg.Emulator
import com.vortexdbg.Module
import com.vortexdbg.pointer.VortexdbgPointer
import com.vortexdbg.unwind.Frame
import com.vortexdbg.unwind.Unwinder
import com.vortexdbg.utils.Inspector
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.io.ByteArrayOutputStream
import java.util.Arrays
import java.util.Stack

class GnuEhFrameHeader internal constructor(parser: ElfParser, offset: Long, size: Int, virtual_address: Long) {

    private class TableEntry(@JvmField val location: Long, @JvmField val address: Long) {
        override fun toString(): String {
            return "TableEntry{" +
                "location=0x" + java.lang.Long.toHexString(location) +
                ", address=0x" + java.lang.Long.toHexString(address) +
                '}'
        }
    }

    private val entries: Array<TableEntry?>

    private fun search(fun_: Long): TableEntry? {
        var tableEntry: TableEntry? = null
        for (entry in entries) {
            if (fun_ >= entry!!.location) {
                tableEntry = entry
            } else {
                break
            }
        }
        return tableEntry
    }

    private val parser: ElfParser

    init {
        this.parser = parser
        parser.seek(offset)

        val off = Off(offset)
        val version = parser.readUnsignedByte().toInt(); off.pos++
        if (version != VERSION) {
            throw IllegalStateException("version is: $version")
        }

        val delta = virtual_address - offset
        val eh_frame_ptr_enc = parser.readUnsignedByte().toInt(); off.pos++
        val fde_count_enc = parser.readUnsignedByte().toInt(); off.pos++
        val table_enc = parser.readUnsignedByte().toInt(); off.pos++
        val eh_frame_ptr = readEncodedPointer(parser, eh_frame_ptr_enc, off, true)
        val fde_count = readEncodedPointer(parser, fde_count_enc, off, true)
        entries = arrayOfNulls(fde_count.toInt())
        for (i in 0 until fde_count) {
            val location = readEncodedPointer(parser, table_enc, off, true) + delta
            val address = readEncodedPointer(parser, table_enc, off, true)
            entries[i.toInt()] = TableEntry(location, address)
            if (log.isDebugEnabled) {
                log.debug("Table entry: eh_frame_ptr=0x{}, virtual_address=0x{}, location=0x{}, address=0x{}, size={}", java.lang.Long.toHexString(eh_frame_ptr), java.lang.Long.toHexString(virtual_address), java.lang.Long.toHexString(location), java.lang.Long.toHexString(address), size)
            }
        }

        if (off.pos - off.init != size.toLong()) {
            throw IllegalStateException("size=" + size + ", pos=" + off.pos)
        }
    }

    private class Off(@JvmField val init: Long) {
        @JvmField
        var pos: Long = init
    }

    fun dwarf_step(emulator: Emulator<*>, unwinder: Unwinder, module: Module, fun_: Long, context: DwarfCursor): Frame? {
        val entry = search(fun_) ?: return null

        val fde = dwarf_get_fde(entry.address, fun_)
        if (log.isDebugEnabled) {
            log.debug("dwarf_step entry={}, fun=0x{}, fde={}, module={}", entry, java.lang.Long.toHexString(fun_), fde, module)
        }
        val loc = if (fde == null) null else dwarf_get_loc(emulator, fde, fun_, module)
        if (loc != null) {
            val vsp: VortexdbgPointer
            when (loc.cfa_rule.type) {
                DW_LOC_REGISTER -> {
                    vsp = VortexdbgPointer.pointer(emulator, context.loc[loc.cfa_rule.values[0].toInt()]!! + loc.cfa_rule.values[1])
                    assert(vsp != null)
                    context.loc[if (emulator.is32Bit()) DwarfCursor32.SP else DwarfCursor64.SP] = vsp.peer
                    if (log.isDebugEnabled) {
                        log.debug("dwarf_step cfa = {}{} + {} => 0x{}", if (emulator.is32Bit()) "r" else "x", loc.cfa_rule.values[0], loc.cfa_rule.values[1], java.lang.Long.toHexString(vsp.peer))
                    }
                }
                DW_LOC_VAL_EXPRESSION -> {
                    log.warn("dwarf_step DW_LOC_VAL_EXPRESSION for cfa not supported, module={}", module)
                    return null
                }
                else -> throw UnsupportedOperationException("dwarf_step type=" + loc.cfa_rule.type)
            }

            for (i in loc.reg_rules.indices) {
                val rule = loc.reg_rules[i] ?: continue

                when (rule.type) {
                    DW_LOC_OFFSET -> {
                        val value = vsp.getPointer(rule.values[0])
                        context.loc[i] = if (value == null) 0L else value.peer
                        if (log.isDebugEnabled) {
                            log.debug("dwarf_step {}{} + ({}) => 0x{}", if (emulator.is32Bit()) "r" else "x", i, rule.values[0], java.lang.Long.toHexString(context.loc[i]!!))
                        }
                    }
                    DW_LOC_VAL_OFFSET -> {
                        context.loc[i] = vsp.peer + rule.values[0]
                        if (log.isDebugEnabled) {
                            log.debug("dwarf_step {}{} val_offset ({}) => 0x{}", if (emulator.is32Bit()) "r" else "x", i, rule.values[0], java.lang.Long.toHexString(context.loc[i]!!))
                        }
                    }
                    DW_LOC_REGISTER -> {
                        context.loc[i] = context.loc[rule.values[0].toInt()]
                        if (log.isDebugEnabled) {
                            log.debug("dwarf_step DW_LOC_REGISTER {}{} = {}{}", if (emulator.is32Bit()) "r" else "x", i, if (emulator.is32Bit()) "r" else "x", rule.values[0])
                        }
                    }
                    DW_LOC_EXPRESSION, DW_LOC_VAL_EXPRESSION -> {
                        log.warn("dwarf_step DWARF expression for {}{} not supported, module={}", if (emulator.is32Bit()) "r" else "x", i, module)
                    }
                    DW_LOC_UNDEFINED -> {
                    }
                    else -> throw UnsupportedOperationException("dwarf_step type=" + rule.type)
                }
            }

            val ip = context.loc[fde!!.cie.return_address_register]!!
            if (log.isDebugEnabled) {
                log.debug("dwarf_step cfa=0x{}, ip=0x{}", java.lang.Long.toHexString(vsp.peer), java.lang.Long.toHexString(ip))
            }

            context.ip = ip
            context.cfa = vsp.peer
            val frame = unwinder.createFrame(VortexdbgPointer.pointer(emulator, ip), VortexdbgPointer.pointer(emulator, context.cfa))
            if (frame != null) {
                context.ip = frame.ip!!.peer
            }
            return frame
        }

        return null
    }

    private class dwarf_loc_rule_t {
        @JvmField
        var type: Int = 0
        @JvmField
        val values = LongArray(2)
        fun copy(): dwarf_loc_rule_t {
            val copy = dwarf_loc_rule_t()
            copy.type = this.type
            System.arraycopy(values, 0, copy.values, 0, values.size)
            return copy
        }
    }

    private class dwarf_loc_t {
        @JvmField
        val cfa_rule: dwarf_loc_rule_t
        @JvmField
        val reg_rules: Array<dwarf_loc_rule_t?>
        constructor() {
            cfa_rule = dwarf_loc_rule_t()
            reg_rules = arrayOfNulls(DWARF_REG_NUM)
        }
        constructor(copy: dwarf_loc_t) {
            cfa_rule = copy.cfa_rule.copy()
            reg_rules = arrayOfNulls(DWARF_REG_NUM)
            for (i in 0 until DWARF_REG_NUM) {
                val src = copy.reg_rules[i]
                if (src != null) {
                    reg_rules[i] = src.copy()
                }
            }
        }
        fun get_reg_rule(i: Long): dwarf_loc_rule_t {
            var rule = reg_rules[i.toInt()]
            if (rule == null) {
                rule = dwarf_loc_rule_t()
                reg_rules[i.toInt()] = rule
            }
            return rule
        }
    }

    private class dwarf_cfa_t(t1: Int, t2: Int) {
        @JvmField
        val operand_types: IntArray = intArrayOf(t1, t2)
    }

    private class FDE(@JvmField val cie: CIE, @JvmField val pc_start: Long, @JvmField val pc_end: Long, @JvmField val cfa_instructions: ByteArray) {
        fun merge(): ByteArray {
            val instructions = ByteArray(cie.cfa_instructions.size + cfa_instructions.size)
            System.arraycopy(cie.cfa_instructions, 0, instructions, 0, cie.cfa_instructions.size)
            System.arraycopy(cfa_instructions, 0, instructions, cie.cfa_instructions.size, cfa_instructions.size)
            return instructions
        }
    }

    private fun dwarf_get_fde(fde_offset: Long, fun_: Long): FDE? {
        val off = Off(fde_offset)
        parser.seek(fde_offset)
        val length = parser.readInt(); off.pos += 4
        if (length == -1) {
            throw UnsupportedOperationException("64bits DWARF FDE")
        }
        val cur_field_offset = off.pos
        val cie_pointer = parser.readInt(); off.pos += 4
        if (cie_pointer == 0) {
            throw IllegalStateException("Invalid cie_pointer")
        }
        val cie_offset = cur_field_offset - cie_pointer
        val cie = dwarf_get_cie(cie_offset)
        parser.seek(off.pos)
        val pc_start = readEncodedPointer(parser, cie.fde_address_encoding, off, true)
        val adjust = off.pos // PC Range is always an absolute value
        val pc_range = readEncodedPointer(parser, cie.fde_address_encoding, off, true) - adjust
        val pc_end = pc_start + pc_range
        if (fun_ >= pc_end) {
            return null
        }

        if (cie.augmentation_string[0] == 'z') {
            val v64 = readULEB128(parser, off)
            var i: Long = 0
            while (i < v64) {
                parser.readUnsignedByte(); off.pos++
                i++
            }
        }
        val baos = ByteArrayOutputStream()
        var i = off.pos - fde_offset - 4
        while (i < length) {
            baos.write(parser.readUnsignedByte().toInt())
            i++
        }
        val cfa_instructions = baos.toByteArray()
        if (log.isDebugEnabled) {
            log.debug(Inspector.inspectString(cfa_instructions, "dwarf_get_fde length=0x" + Integer.toHexString(length) + ", cie_offset=0x" + java.lang.Long.toHexString(cie_offset) +
                ", pc_start=0x" + java.lang.Long.toHexString(pc_start) + ", pc_end=0x" + java.lang.Long.toHexString(pc_end)))
        }

        return FDE(cie, pc_start, pc_end, cfa_instructions)
    }

    private class CIE(@JvmField val fde_address_encoding: Int, @JvmField val augmentation_string: String, @JvmField val code_alignment_factor: Long, @JvmField val data_alignment_factor: Long, @JvmField val return_address_register: Int, @JvmField val cfa_instructions: ByteArray)

    private fun dwarf_get_cie(cie_offset: Long): CIE {
        parser.seek(cie_offset)
        val off = Off(cie_offset)

        val length = parser.readInt(); off.pos += 4
        if (length == -1) {
            throw UnsupportedOperationException("64bits DWARF CIE")
        }
        var fde_address_encoding = DW_EH_PE_sdata4
        val cie_id = parser.readInt(); off.pos += 4
        if (cie_id != 0) {
            throw IllegalStateException("Invalid CIE")
        }
        val cie_version = parser.readUnsignedByte().toInt(); off.pos++
        if (cie_version != 1) {
            throw IllegalStateException("Invalid CIE version: $cie_version")
        }

        // get augmentation string
        val baos = ByteArrayOutputStream(8)
        for (i in 0 until 8) {
            val b = parser.readUnsignedByte().toInt(); off.pos++
            if (b == 0) {
                break
            } else {
                baos.write(b)
            }
        }
        if (baos.size() == 0) {
            throw IllegalStateException("Invalid CIE augmentation string")
        }
        val augmentation_string = baos.toString()

        val code_alignment_factor = readULEB128(parser, off)
        val data_alignment_factor = readSLEB128(parser, off)
        val return_address_register = parser.readUnsignedByte().toInt(); off.pos++
        val cfa_instructions_offset: Long
        if ('z' != augmentation_string[0]) {
            cfa_instructions_offset = off.pos
        } else {
            val v64 = readULEB128(parser, off)
            cfa_instructions_offset = off.pos + v64
            val as_ = augmentation_string.toCharArray()
            for (i in 1 until as_.size) {
                when (as_[i]) {
                    'R' -> {
                        fde_address_encoding = parser.readUnsignedByte().toInt(); off.pos++
                    }
                    'L' -> {
                        parser.readUnsignedByte(); off.pos++ // skip LSDA encoding
                    }
                    'P' -> {
                        // get personality routine encoding
                        val encoding = parser.readUnsignedByte().toInt(); off.pos++
                        // skip personality routine
                        readEncodedPointer(parser, encoding, off, false)
                    }
                    else -> throw UnsupportedOperationException("augmentation_string=$augmentation_string")
                }
            }
        }
        baos.reset()
        var i = cfa_instructions_offset - cie_offset - 4
        while (i < length) {
            baos.write(parser.readUnsignedByte().toInt())
            i++
        }
        val cfa_instructions = baos.toByteArray()
        if (log.isDebugEnabled) {
            log.debug(Inspector.inspectString(cfa_instructions, "dwarf_get_cie length=0x" + Integer.toHexString(length) + ", augmentation_string=" + augmentation_string +
                ", code_alignment_factor=" + code_alignment_factor + ", data_alignment_factor=" + data_alignment_factor + ", return_address_register=" + return_address_register +
                ", fde_address_encoding=0x" + Integer.toHexString(fde_address_encoding) + ", cfa_instructions_offset=0x" + java.lang.Long.toHexString(cfa_instructions_offset)))
        }
        return CIE(fde_address_encoding, augmentation_string, code_alignment_factor, data_alignment_factor, return_address_register, cfa_instructions)
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(GnuEhFrameHeader::class.java)

        private const val VERSION = 1

        private const val DW_EH_PE_omit = 0xff /* GNU. Means no value present. */

        private const val DW_EH_PE_absptr = 0x00
        private const val DW_EH_PE_uleb128 = 0x01
        private const val DW_EH_PE_udata2 = 0x02
        private const val DW_EH_PE_udata4 = 0x03
        private const val DW_EH_PE_udata8 = 0x04
        private const val DW_EH_PE_sleb128 = 0x09
        private const val DW_EH_PE_sdata2 = 0x0A
        private const val DW_EH_PE_sdata4 = 0x0B
        private const val DW_EH_PE_sdata8 = 0x0C
        private const val DW_EH_PE_udata1 = 0x0D
        private const val DW_EH_PE_block = 0x0F
        private const val DW_EH_PE_pcrel = 0x10
        private const val DW_EH_PE_textrel = 0x20
        private const val DW_EH_PE_datarel = 0x30
        private const val DW_EH_PE_funcrel = 0x40
        private const val DW_EH_PE_aligned = 0x50
        private const val DW_EH_PE_indirect = 0x80 /* gcc extension */

        private fun readLEB128(dataIn: ElfDataIn, off: Off, signed: Boolean): Long {
            var result: Long = 0
            var shift = 0
            var b: Int
            do {
                b = dataIn.readUnsignedByte().toInt(); off.pos++
                result = result or ((b and 0x7f).toLong() shl shift)
                shift += 7
            } while ((b and 0x80) != 0)
            if (signed && ((b and 0x40) != 0)) {
                result = result or -(1L shl shift)
            }
            return result
        }

        private fun readULEB128(dataIn: ElfDataIn, off: Off): Long {
            return readLEB128(dataIn, off, false)
        }

        private fun readSLEB128(dataIn: ElfDataIn, off: Off): Long {
            return readLEB128(dataIn, off, true)
        }

        private fun readEncodedPointer(dataIn: ElfDataIn, encoding: Int, off: Off, checkIndirect: Boolean): Long {
            if (encoding == DW_EH_PE_omit) {
                return 0
            }
            val last_pos = off.pos

            var result: Long
            /* first get value */
            when (encoding and 0xf) {
                /*case DW_EH_PE_absptr:
                    result = *((uintptr_t*)p);
                    p += sizeof(uintptr_t);
                    break;*/
                DW_EH_PE_uleb128 -> result = readULEB128(dataIn, off)
                DW_EH_PE_udata1 -> {
                    result = dataIn.readUnsignedByte().toLong(); off.pos++
                }
                DW_EH_PE_udata2 -> {
                    result = dataIn.readShort().toLong() and 0xffffL; off.pos += 2
                }
                DW_EH_PE_udata4 -> {
                    result = dataIn.readInt().toLong() and 0xffffffffL; off.pos += 4
                }
                DW_EH_PE_sdata2 -> {
                    result = dataIn.readShort().toLong(); off.pos += 2
                }
                DW_EH_PE_sdata4 -> {
                    result = dataIn.readInt().toLong(); off.pos += 4
                }
                DW_EH_PE_udata8, DW_EH_PE_sdata8 -> {
                    result = dataIn.readLong(); off.pos += 8
                }
                DW_EH_PE_sleb128 -> result = readSLEB128(dataIn, off)
                else -> throw IllegalStateException("not supported: encoding=0x" + Integer.toHexString(encoding))
            }

            /* then add relative offset */
            when (encoding and 0x70) {
                DW_EH_PE_absptr -> {
                    /* do nothing */
                }
                DW_EH_PE_pcrel -> result += last_pos
                DW_EH_PE_datarel -> result += off.init
                DW_EH_PE_textrel, DW_EH_PE_funcrel, DW_EH_PE_aligned -> throw IllegalStateException("not supported: encoding=0x" + Integer.toHexString(encoding))
                else -> throw IllegalStateException("not supported: encoding=0x" + Integer.toHexString(encoding))
            }

            /* then apply indirection */
            if ((encoding and DW_EH_PE_indirect) != 0) {
//            result = *((uintptr_t*)result);
                if (checkIndirect) {
                    throw IllegalStateException("DW_EH_PE_indirect: encoding=0x" + Integer.toHexString(encoding))
                }
            }

            return result
        }

        private const val DWARF_REG_NUM = 0x100

        private val dwarf_cfa_table = arrayOf<dwarf_cfa_t?>(
/* 0x00 */ dwarf_cfa_t(DW_EH_PE_omit, DW_EH_PE_omit),
/* 0x01 */ dwarf_cfa_t(DW_EH_PE_absptr, DW_EH_PE_omit),
/* 0x02 */ dwarf_cfa_t(DW_EH_PE_udata1, DW_EH_PE_omit),
/* 0x03 */ dwarf_cfa_t(DW_EH_PE_udata2, DW_EH_PE_omit),
/* 0x04 */ dwarf_cfa_t(DW_EH_PE_udata4, DW_EH_PE_omit),
/* 0x05 */ dwarf_cfa_t(DW_EH_PE_uleb128, DW_EH_PE_uleb128),
/* 0x06 */ dwarf_cfa_t(DW_EH_PE_uleb128, DW_EH_PE_omit),
/* 0x07 */ dwarf_cfa_t(DW_EH_PE_uleb128, DW_EH_PE_omit),
/* 0x08 */ dwarf_cfa_t(DW_EH_PE_uleb128, DW_EH_PE_omit),
/* 0x09 */ dwarf_cfa_t(DW_EH_PE_uleb128, DW_EH_PE_uleb128),
/* 0x0a */ dwarf_cfa_t(DW_EH_PE_omit, DW_EH_PE_omit),
/* 0x0b */ dwarf_cfa_t(DW_EH_PE_omit, DW_EH_PE_omit),
/* 0x0c */ dwarf_cfa_t(DW_EH_PE_uleb128, DW_EH_PE_uleb128),
/* 0x0d */ dwarf_cfa_t(DW_EH_PE_uleb128, DW_EH_PE_omit),
/* 0x0e */ dwarf_cfa_t(DW_EH_PE_uleb128, DW_EH_PE_omit),
/* 0x0f */ dwarf_cfa_t(DW_EH_PE_block, DW_EH_PE_omit),
/* 0x10 */ dwarf_cfa_t(DW_EH_PE_uleb128, DW_EH_PE_block),
/* 0x11 */ dwarf_cfa_t(DW_EH_PE_uleb128, DW_EH_PE_sleb128),
/* 0x12 */ dwarf_cfa_t(DW_EH_PE_uleb128, DW_EH_PE_sleb128),
/* 0x13 */ dwarf_cfa_t(DW_EH_PE_sleb128, DW_EH_PE_omit),
/* 0x14 */ dwarf_cfa_t(DW_EH_PE_uleb128, DW_EH_PE_uleb128),
/* 0x15 */ dwarf_cfa_t(DW_EH_PE_uleb128, DW_EH_PE_sleb128),
/* 0x16 */ dwarf_cfa_t(DW_EH_PE_uleb128, DW_EH_PE_block),
/* 0x17 */ null,
/* 0x18 */ null,
/* 0x19 */ null,
/* 0x1a */ null,
/* 0x1b */ null,
/* 0x1c */ null,
/* 0x1d */ null,
/* 0x1e */ null,
/* 0x1f */ null,
/* 0x20 */ null,
/* 0x21 */ null,
/* 0x22 */ null,
/* 0x23 */ null,
/* 0x24 */ null,
/* 0x25 */ null,
/* 0x26 */ null,
/* 0x27 */ null,
/* 0x28 */ null,
/* 0x29 */ null,
/* 0x2a */ null,
/* 0x2b */ null,
/* 0x2c */ null,
/* 0x2d */ dwarf_cfa_t(DW_EH_PE_omit, DW_EH_PE_omit),
/* 0x2e */ dwarf_cfa_t(DW_EH_PE_uleb128, DW_EH_PE_omit),
/* 0x2f */ dwarf_cfa_t(DW_EH_PE_uleb128, DW_EH_PE_uleb128),
/* 0x30 */ null,
/* 0x31 */ null,
/* 0x32 */ null,
/* 0x33 */ null,
/* 0x34 */ null,
/* 0x35 */ null,
/* 0x36 */ null,
/* 0x37 */ null,
/* 0x38 */ null,
/* 0x39 */ null,
/* 0x3a */ null,
/* 0x3b */ null,
/* 0x3c */ null,
/* 0x3d */ null,
/* 0x3e */ null,
/* 0x3f */ null
        )

        // location rule type
        private const val DW_LOC_INVALID = 0
        private const val DW_LOC_UNDEFINED = 1
        private const val DW_LOC_OFFSET = 2
        private const val DW_LOC_VAL_OFFSET = 3
        private const val DW_LOC_REGISTER = 4
        private const val DW_LOC_EXPRESSION = 5
        private const val DW_LOC_VAL_EXPRESSION = 6

        private fun dwarf_get_loc(emulator: Emulator<*>, fde: FDE, pc: Long, module: Module): dwarf_loc_t? {
            var cur_pc = fde.pc_start
            var loc: dwarf_loc_t
            val operands = LongArray(2)

            val loc_init = dwarf_loc_t()
            var loc_pc: dwarf_loc_t
            loc = loc_init
            val loc_node_stack = Stack<dwarf_loc_t>()

            val instructions = fde.merge()
            var stepped = false
            var i = 0
            while (i < instructions.size) {
                if (cur_pc > pc) {
                    stepped = true
                    break // have stepped to the LOC
                }

                if (i == fde.cie.cfa_instructions.size) {
                    loc_pc = dwarf_loc_t(loc_init)
                    loc = loc_pc
                    stepped = true
                }

                val op = instructions[i].toInt() and 0xff
                val cfa_op = op shr 6
                val cfa_op_ext = op and 0x3f
                if (log.isDebugEnabled) {
                    log.debug("dwarf_get_loc i={}, op=0x{}, cfa_op={}, cfa_op_ext=0x{}, cur_pc=0x{}", i, Integer.toHexString(op), cfa_op, Integer.toHexString(cfa_op_ext), java.lang.Long.toHexString(cur_pc))
                }

                when (cfa_op) {
                    0x1 -> { // DW_CFA_advance_loc
                        val step = cfa_op_ext * fde.cie.code_alignment_factor
                        cur_pc += step
                        if (log.isDebugEnabled) {
                            log.debug("DW_CFA_advance_loc: {} to {}", step, java.lang.Long.toHexString(cur_pc))
                        }
                    }
                    0x2 -> { // DW_CFA_offset
                        val off = Off(0)
                        val dataIn: ElfDataIn = ElfBuffer(Arrays.copyOfRange(instructions, i + 1, instructions.size))
                        val v64 = readULEB128(dataIn, off)
                        val rule = loc.get_reg_rule(cfa_op_ext.toLong())
                        rule.type = DW_LOC_OFFSET
                        rule.values[0] = v64 * fde.cie.data_alignment_factor
                        i += off.pos.toInt()
                        if (log.isDebugEnabled) {
                            log.debug("DW_CFA_offset: r{} at cfa{}", cfa_op_ext, rule.values[0])
                        }
                    }
                    0x3 -> { // DW_CFA_restore
                        loc.reg_rules[cfa_op_ext] = loc_init.reg_rules[cfa_op_ext]
                        if (log.isDebugEnabled) {
                            log.debug("DW_CFA_restore: r{}", cfa_op_ext)
                        }
                    }
                    else -> { // case 0 / default
                        val cfa = dwarf_cfa_table[cfa_op_ext]
                        if (cfa == null) {
                            log.warn("dwarf_get_loc unsupported cfa opcode: cfa_op=0x{}, cfa_op_ext=0x{}, module={}", Integer.toHexString(cfa_op), Integer.toHexString(cfa_op_ext), module)
                            return null
                        }
                        for (m in 0 until 2) {
                            val type = cfa.operand_types[m]
                            if (type == DW_EH_PE_omit) {
                                break
                            } else if (type == DW_EH_PE_block) {
                                val off = Off(0)
                                val dataIn: ElfDataIn = ElfBuffer(Arrays.copyOfRange(instructions, i + 1, instructions.size))
                                val blockLen = readULEB128(dataIn, off)
                                off.pos += blockLen
                                operands[m] = 0
                                i += off.pos.toInt()
                            } else {
                                val off = Off(0)
                                val dataIn: ElfDataIn = ElfBuffer(Arrays.copyOfRange(instructions, i + 1, instructions.size))
                                val v64 = readEncodedPointer(dataIn, type, off, true)
                                operands[m] = v64
                                i += off.pos.toInt()
                            }
                        }
                        when (cfa_op_ext) {
                            0x00 -> {
                                if (log.isDebugEnabled) {
                                    log.debug("DW_CFA_nop")
                                }
                            }
                            0x01 -> cur_pc = operands[0]
                            0x02 -> {
                                val step = operands[0] * fde.cie.code_alignment_factor
                                cur_pc += step
                                if (log.isDebugEnabled) {
                                    log.debug("DW_CFA_advance_loc1: {} to {}", step, java.lang.Long.toHexString(cur_pc))
                                }
                            }
                            0x03, 0x04 -> cur_pc += operands[0] * fde.cie.code_alignment_factor
                            0x05 -> {
                                val rule = loc.get_reg_rule(operands[0])
                                rule.type = DW_LOC_OFFSET
                                rule.values[0] = operands[1]
                            }
                            0x06 -> loc.reg_rules[operands[0].toInt()] = loc_init.reg_rules[operands[0].toInt()]
                            0x07 -> {
                                val rule = loc.get_reg_rule(operands[0])
                                rule.type = DW_LOC_UNDEFINED
                            }
                            0x08 -> {
                                val rule = loc.get_reg_rule(operands[0])
                                rule.type = DW_LOC_INVALID
                            }
                            0x09 -> {
                                val rule = loc.get_reg_rule(operands[0])
                                rule.type = DW_LOC_REGISTER
                                rule.values[0] = operands[1]
                            }
                            0x0a -> {
                                val loc_node = dwarf_loc_t(loc)
                                loc_node_stack.push(loc_node)
                            }
                            0x0b -> {
                                val loc_node = loc_node_stack.pop()
                                loc_pc = dwarf_loc_t(loc_node)
                                loc = loc_pc
                            }
                            0x0c -> {
                                loc.cfa_rule.type = DW_LOC_REGISTER
                                loc.cfa_rule.values[0] = operands[0]
                                loc.cfa_rule.values[1] = operands[1]
                                if (log.isDebugEnabled) {
                                    log.debug("DW_CFA_def_cfa: {}{} ofs {}", if (emulator.is32Bit()) "r" else "x", operands[0], operands[1])
                                }
                            }
                            0x0d ->
                                if (loc.cfa_rule.type != DW_LOC_REGISTER) {
                                    throw IllegalStateException("NOT DW_LOC_REGISTER")
                                } else {
                                    loc.cfa_rule.values[0] = operands[0]
                                }
                            0x0e ->
                                if (loc.cfa_rule.type != DW_LOC_REGISTER) {
                                    throw IllegalStateException("NOT DW_LOC_REGISTER")
                                } else {
                                    loc.cfa_rule.values[1] = operands[0]
                                    if (log.isDebugEnabled) {
                                        log.debug("DW_CFA_def_cfa_offset: {}", operands[0])
                                    }
                                }
                            0x0f -> loc.cfa_rule.type = DW_LOC_VAL_EXPRESSION
                            0x10 -> {
                                val rule = loc.get_reg_rule(operands[0])
                                rule.type = DW_LOC_EXPRESSION
                            }
                            0x11 -> {
                                val rule = loc.get_reg_rule(operands[0])
                                rule.type = DW_LOC_OFFSET
                                rule.values[0] = operands[1] * fde.cie.data_alignment_factor
                                if (log.isDebugEnabled) {
                                    log.debug("DW_CFA_offset_extended_sf: r{} at cfa{}", operands[0], rule.values[0])
                                }
                            }
                            0x12 -> {
                                loc.cfa_rule.type = DW_LOC_REGISTER
                                loc.cfa_rule.values[0] = operands[0]
                                loc.cfa_rule.values[1] = operands[1] * fde.cie.data_alignment_factor
                                if (log.isDebugEnabled) {
                                    log.debug("DW_CFA_def_cfa_sf: {}{} ofs {}", if (emulator.is32Bit()) "r" else "x", operands[0], loc.cfa_rule.values[1])
                                }
                            }
                            0x13 -> {
                                if (loc.cfa_rule.type != DW_LOC_REGISTER) {
                                    throw IllegalStateException("NOT DW_LOC_REGISTER")
                                }
                                loc.cfa_rule.values[1] = operands[0] * fde.cie.data_alignment_factor
                                if (log.isDebugEnabled) {
                                    log.debug("DW_CFA_def_cfa_offset_sf: {}", loc.cfa_rule.values[1])
                                }
                            }
                            0x14 -> {
                                val rule = loc.get_reg_rule(operands[0])
                                rule.type = DW_LOC_VAL_OFFSET
                                rule.values[0] = operands[1] * fde.cie.data_alignment_factor
                            }
                            0x15 -> {
                                val rule = loc.get_reg_rule(operands[0])
                                rule.type = DW_LOC_VAL_OFFSET
                                rule.values[0] = operands[1] * fde.cie.data_alignment_factor
                            }
                            0x16 -> {
                                val rule = loc.get_reg_rule(operands[0])
                                rule.type = DW_LOC_VAL_EXPRESSION
                            }
                            0x2d, 0x2e -> {
                            }
                            0x2f -> {
                                val rule = loc.get_reg_rule(operands[0])
                                rule.type = DW_LOC_OFFSET
                                rule.values[0] = -(operands[1] * fde.cie.data_alignment_factor)
                            }
                            else -> throw IllegalStateException("dwarf_get_loc cfa_op=" + cfa_op + ", i=" + i + ", cfa_op_ext=0x" + Integer.toHexString(cfa_op_ext))
                        }
                    }
                }
                i++
            }

            return if (stepped) loc else loc_init
        }
    }
}
