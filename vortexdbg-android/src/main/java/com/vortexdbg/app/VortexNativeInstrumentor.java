package com.vortexdbg.app;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;

/**
 * E (Vortex-DBG / A1) — instrumentação de bytecode dos métodos {@code native} das classes
 * do app: remove o modificador {@code native} e gera um corpo que roteia a chamada ao
 * {@link VortexNativeDispatch} (que executa a função nativa correspondente no UniDBG).
 *
 * Suporta retorno void/boolean/byte/short/char/int/long/objeto/array. float/double ainda
 * não suportados (raros em JNI de cripto/assinatura).
 */
public final class VortexNativeInstrumentor {

    private static final Type DISPATCH = Type.getType(VortexNativeDispatch.class);
    private static final Type OBJECT = Type.getType(Object.class);
    private static final Type OBJECT_ARRAY = Type.getType(Object[].class);
    private static final Type STRING = Type.getType(String.class);

    private VortexNativeInstrumentor() {}

    public static byte[] instrument(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cr.accept(new Visitor(cw), 0);
        return cw.toByteArray();
    }

    private static Type[] dispatchArgs() {
        return new Type[]{STRING, STRING, STRING, OBJECT_ARRAY};
    }

    private static final class Visitor extends ClassVisitor {
        private String classNameDotted;

        Visitor(ClassVisitor cv) { super(Opcodes.ASM9, cv); }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            this.classNameDotted = name.replace('/', '.');
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            if ((access & Opcodes.ACC_NATIVE) == 0) {
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }
            int newAccess = access & ~Opcodes.ACC_NATIVE;
            MethodVisitor mv = super.visitMethod(newAccess, name, descriptor, signature, exceptions);
            generate(mv, newAccess, name, descriptor);
            return null; // método nativo tratado (corpo gerado)
        }

        private void generate(MethodVisitor mv, int access, String name, String descriptor) {
            GeneratorAdapter ga = new GeneratorAdapter(mv, access, name, descriptor);
            ga.visitCode();

            Type[] argTypes = Type.getArgumentTypes(descriptor);
            ga.push(classNameDotted);
            ga.push(name);
            ga.push(descriptor);

            ga.push(argTypes.length);
            ga.newArray(OBJECT);
            for (int i = 0; i < argTypes.length; i++) {
                ga.dup();
                ga.push(i);
                ga.loadArg(i);
                ga.box(argTypes[i]);
                ga.arrayStore(OBJECT);
            }

            Type ret = Type.getReturnType(descriptor);
            switch (ret.getSort()) {
                case Type.VOID:
                    ga.invokeStatic(DISPATCH, new Method("dispatchVoid", Type.VOID_TYPE, dispatchArgs()));
                    break;
                case Type.BOOLEAN:
                    ga.invokeStatic(DISPATCH, new Method("dispatchBoolean", Type.BOOLEAN_TYPE, dispatchArgs()));
                    break;
                case Type.BYTE:
                case Type.SHORT:
                case Type.CHAR:
                case Type.INT:
                    ga.invokeStatic(DISPATCH, new Method("dispatchInt", Type.INT_TYPE, dispatchArgs()));
                    if (ret.getSort() != Type.INT) {
                        ga.cast(Type.INT_TYPE, ret);
                    }
                    break;
                case Type.LONG:
                    ga.invokeStatic(DISPATCH, new Method("dispatchLong", Type.LONG_TYPE, dispatchArgs()));
                    break;
                case Type.FLOAT:
                case Type.DOUBLE:
                    throw new UnsupportedOperationException("retorno float/double não suportado: " + name);
                default: // objeto/array
                    ga.invokeStatic(DISPATCH, new Method("dispatchObject", OBJECT, dispatchArgs()));
                    ga.checkCast(ret);
                    break;
            }
            ga.returnValue();
            ga.endMethod();
        }
    }
}
