package com.vortexdbg.signal

import java.util.ArrayList

class UnixSigSet(private var mask: Long) : SigSet {

    override fun getMask(): Long {
        return mask
    }

    override fun setMask(mask: Long) {
        this.mask = mask
    }

    override fun blockSigSet(mask: Long) {
        this.mask = this.mask or mask
    }

    override fun unblockSigSet(mask: Long) {
        this.mask = this.mask and mask.inv()
    }

    override fun containsSigNumber(signum: Int): Boolean {
        val bit = signum - 1
        return (mask and (1L shl bit)) != 0L
    }

    override fun removeSigNumber(signum: Int) {
        val bit = signum - 1
        this.mask = this.mask and (1L shl bit)
    }

    override fun addSigNumber(signum: Int) {
        val bit = signum - 1
        this.mask = this.mask or (1L shl bit)
    }

    private inner class SigSetIterator(sigSet: UnixSigSet) : MutableIterator<Int> {

        private var mask: Long = sigSet.mask
        private var bit: Int = 0
        private var nextBit: Int = 0

        override fun hasNext(): Boolean {
            for (i in bit until 64) {
                if ((mask and (1L shl i)) != 0L) {
                    nextBit = i
                    return true
                }
            }
            return false
        }

        override fun next(): Int {
            bit = nextBit
            this.mask = this.mask and (1L shl bit).inv()
            return bit + 1
        }

        override fun remove() {
            this@UnixSigSet.mask = this@UnixSigSet.mask and (1L shl bit).inv()
        }
    }

    override fun iterator(): MutableIterator<Int> {
        return SigSetIterator(this)
    }

    override fun toString(): String {
        val list: MutableList<Int> = ArrayList(10)
        for (signum in this) {
            list.add(signum)
        }
        return list.toString()
    }

}
