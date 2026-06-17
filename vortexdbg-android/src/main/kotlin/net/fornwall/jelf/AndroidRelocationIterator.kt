package net.fornwall.jelf

import com.vortexdbg.Utils
import com.vortexdbg.utils.Inspector
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.nio.ByteBuffer

class AndroidRelocationIterator(
    private val objectSize: Int,
    symtab: SymbolLocator,
    private val buffer: ByteBuffer,
    private val rela: Boolean
) : Iterator<MemoizedObject<ElfRelocation>> {

    private fun readSleb128(): Long {
        return Utils.readSignedLeb128(buffer, if (objectSize == ElfFile.CLASS_32.toInt()) 32 else 64)
    }

    private var relocation_count_: Long
    private val reloc_: ElfRelocation
    private var relocation_index_: Long = 0
    private var relocation_group_index_: Long = 0
    private var group_size_: Long = 0

    init {
        reloc_ = ElfRelocation(objectSize, symtab)

        relocation_count_ = readSleb128()
        reloc_.offset = readSleb128()

        relocation_index_ = 0
        relocation_group_index_ = 0
        group_size_ = 0
    }

    override fun hasNext(): Boolean {
        val next = relocation_index_ < relocation_count_
        if (!next && log.isDebugEnabled) {
            val remaining = ByteArray(buffer.remaining())
            buffer.get(remaining)
            Inspector.inspect(remaining, "end")
        }
        return next
    }

    override fun next(): MemoizedObject<ElfRelocation> {
        if (relocation_group_index_ == group_size_) {
            if (!read_group_fields()) {
                // Iterator is inconsistent state; it should not be called again
                // but in case it is let's make sure has_next() returns false.
                relocation_index_ = 0
                relocation_count_ = 0
                return null as MemoizedObject<ElfRelocation>
            }
        }

        if (is_relocation_grouped_by_offset_delta()) {
            reloc_.offset += group_r_offset_delta_
        } else {
            reloc_.offset += readSleb128()
        }

        if (!is_relocation_grouped_by_info()) {
            reloc_.info = readSleb128()
        }

        if (is_relocation_group_has_addend() &&
                !is_relocation_grouped_by_addend()) {
            if (!rela) {
                throw IllegalStateException("unexpected r_addend in android.rel section")
            }
            reloc_.addend += readSleb128()
        }

        relocation_index_++
        relocation_group_index_++

        try {
            val copy = reloc_.clone()
            return object : MemoizedObject<ElfRelocation>() {
                @Throws(ElfException::class)
                override fun computeValue(): ElfRelocation {
                    return copy
                }
            }
        } catch (e: CloneNotSupportedException) {
            throw IllegalStateException(e)
        }
    }

    private var group_flags_: Long = 0
    private var group_r_offset_delta_: Long = 0

    private fun read_group_fields(): Boolean {
        group_size_ = readSleb128()
        group_flags_ = readSleb128()

        if (is_relocation_grouped_by_offset_delta()) {
            group_r_offset_delta_ = readSleb128()
        }

        if (is_relocation_grouped_by_info()) {
            reloc_.info = readSleb128()
        }

        if (is_relocation_group_has_addend() &&
                is_relocation_grouped_by_addend()) {
            if (!rela) {
                throw IllegalStateException("unexpected r_addend in android.rel section")
            }
            reloc_.addend += readSleb128()
        } else if (!is_relocation_group_has_addend()) {
            if (rela) {
                reloc_.addend = 0
            }
        }

        relocation_group_index_ = 0
        return true
    }

    private fun is_relocation_grouped_by_info(): Boolean {
        return (group_flags_ and RELOCATION_GROUPED_BY_INFO_FLAG.toLong()) != 0L
    }

    private fun is_relocation_grouped_by_offset_delta(): Boolean {
        return (group_flags_ and RELOCATION_GROUPED_BY_OFFSET_DELTA_FLAG.toLong()) != 0L
    }

    private fun is_relocation_grouped_by_addend(): Boolean {
        return (group_flags_ and RELOCATION_GROUPED_BY_ADDEND_FLAG.toLong()) != 0L
    }

    private fun is_relocation_group_has_addend(): Boolean {
        return (group_flags_ and RELOCATION_GROUP_HAS_ADDEND_FLAG.toLong()) != 0L
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(AndroidRelocationIterator::class.java)

        private const val RELOCATION_GROUPED_BY_INFO_FLAG = 1
        private const val RELOCATION_GROUPED_BY_OFFSET_DELTA_FLAG = 2
        private const val RELOCATION_GROUPED_BY_ADDEND_FLAG = 4
        private const val RELOCATION_GROUP_HAS_ADDEND_FLAG = 8
    }
}
