package com.vortexdbg.arm.backend.hypervisor.arm64

import capstone.api.Instruction
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class SimpleMemorySizeDetector : MemorySizeDetector {

    override fun detectReadSize(insn: Instruction): Int {
        return when (insn.getMnemonic()) {
            "ldrb", "ldursb", "ldarb", "ldaprb" -> 1
            "ldursh", "ldrh", "ldarh", "ldaprh" -> 2
            "ldr", "ldxr", "ldaxr", "ldur", "ldar", "ldapr",
            "ldadd", "ldadda", "ldaddal", "ldaddl",
            "ldclr", "ldclra", "ldclral", "ldclrl",
            "ldeor", "ldeora", "ldeoral", "ldeorl",
            "ldset", "ldseta", "ldsetal", "ldsetl",
            "swp", "swpa", "swpal", "swpl",
            "cas", "casa", "casal", "casl" -> detectSingleRegSize(insn.getOpStr())
            "ldp", "ldxp", "ldaxp" -> detectPairRegSize(insn.getOpStr())
            else -> {
                log.info("detectReadSize: insn={}", insn)
                0
            }
        }
    }

    override fun detectWriteSize(insn: Instruction): Int {
        val opStr = insn.getOpStr()
        return when (insn.getMnemonic()) {
            "strb", "sturb", "stlrb" -> 1
            "strh", "sturh", "stlrh" -> 2
            "str", "stur", "stlr",
            "ldadd", "ldadda", "ldaddal", "ldaddl",
            "ldclr", "ldclra", "ldclral", "ldclrl",
            "ldeor", "ldeora", "ldeoral", "ldeorl",
            "ldset", "ldseta", "ldsetal", "ldsetl",
            "swp", "swpa", "swpal", "swpl",
            "cas", "casa", "casal", "casl" -> detectSingleRegSize(opStr)
            "stxr", "stlxr" -> detectSingleRegSize(extractAfterFirstComma(opStr))
            "stp" -> detectPairRegSize(opStr)
            "stxp", "stlxp" -> detectPairRegSize(extractAfterFirstComma(opStr))
            else -> {
                log.info("detectWriteSize: insn={}", insn)
                0
            }
        }
    }

    companion object {

        private val log: Logger = LoggerFactory.getLogger(SimpleMemorySizeDetector::class.java)

        private fun detectSingleRegSize(opStr: String): Int {
            if (opStr.startsWith("w")) return 4
            if (opStr.startsWith("x")) return 8
            return 0
        }

        private fun detectPairRegSize(opStr: String): Int {
            if (opStr.startsWith("w")) return 8
            if (opStr.startsWith("x")) return 16
            return 0
        }

        private fun extractAfterFirstComma(opStr: String): String {
            val commaIdx = opStr.indexOf(',')
            return if (commaIdx >= 0) opStr.substring(commaIdx + 1).trim() else opStr
        }
    }

}
