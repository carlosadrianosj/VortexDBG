package com.vortexdbg.app

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.commons.Method

/**
 * E (Vortex-DBG / A1) — instrumentação de bytecode dos métodos {@code native} das classes
 * do app: remove o modificador {@code native} e gera um corpo que roteia a chamada ao
 * {@link VortexNativeDispatch} (que executa a função nativa correspondente no UniDBG).
 *
 * Suporta retorno void/boolean/byte/short/char/int/long/objeto/array. float/double ainda
 * não suportados (raros em JNI de cripto/assinatura).
 */
object VortexNativeInstrumentor {

    private val DISPATCH = Type.getType(VortexNativeDispatch::class.java)
    private val OBJECT = Type.getType(Any::class.java)
    private val OBJECT_ARRAY = Type.getType(Array<Any>::class.java)
    private val STRING = Type.getType(String::class.java)

    @JvmStatic
    fun instrument(classBytes: ByteArray): ByteArray {
        val cr = ClassReader(classBytes)
        val cw = ClassWriter(cr, ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        cr.accept(Visitor(cw), 0)
        return cw.toByteArray()
    }

    private fun dispatchArgs(): Array<Type> {
        return arrayOf(STRING, STRING, STRING, OBJECT_ARRAY)
    }

    private class Visitor(cv: ClassVisitor) : ClassVisitor(Opcodes.ASM9, cv) {
        private var classNameDotted: String? = null

        override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<String>?) {
            this.classNameDotted = name.replace('/', '.')
            super.visit(version, access, name, signature, superName, interfaces)
        }

        override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<String>?): MethodVisitor? {
            if ((access and Opcodes.ACC_NATIVE) == 0) {
                return super.visitMethod(access, name, descriptor, signature, exceptions)
            }
            val newAccess = access and Opcodes.ACC_NATIVE.inv()
            val mv = super.visitMethod(newAccess, name, descriptor, signature, exceptions)
            generate(mv, newAccess, name, descriptor)
            return null // método nativo tratado (corpo gerado)
        }

        private fun generate(mv: MethodVisitor, access: Int, name: String, descriptor: String) {
            val ga = GeneratorAdapter(mv, access, name, descriptor)
            ga.visitCode()

            val argTypes = Type.getArgumentTypes(descriptor)
            ga.push(classNameDotted)
            ga.push(name)
            ga.push(descriptor)

            ga.push(argTypes.size)
            ga.newArray(OBJECT)
            for (i in argTypes.indices) {
                ga.dup()
                ga.push(i)
                ga.loadArg(i)
                ga.box(argTypes[i])
                ga.arrayStore(OBJECT)
            }

            val ret = Type.getReturnType(descriptor)
            when (ret.sort) {
                Type.VOID ->
                    ga.invokeStatic(DISPATCH, Method("dispatchVoid", Type.VOID_TYPE, dispatchArgs()))
                Type.BOOLEAN ->
                    ga.invokeStatic(DISPATCH, Method("dispatchBoolean", Type.BOOLEAN_TYPE, dispatchArgs()))
                Type.BYTE, Type.SHORT, Type.CHAR, Type.INT -> {
                    ga.invokeStatic(DISPATCH, Method("dispatchInt", Type.INT_TYPE, dispatchArgs()))
                    if (ret.sort != Type.INT) {
                        ga.cast(Type.INT_TYPE, ret)
                    }
                }
                Type.LONG ->
                    ga.invokeStatic(DISPATCH, Method("dispatchLong", Type.LONG_TYPE, dispatchArgs()))
                Type.FLOAT, Type.DOUBLE ->
                    throw UnsupportedOperationException("retorno float/double não suportado: $name")
                else -> { // objeto/array
                    ga.invokeStatic(DISPATCH, Method("dispatchObject", OBJECT, dispatchArgs()))
                    ga.checkCast(ret)
                }
            }
            ga.returnValue()
            ga.endMethod()
        }
    }
}
