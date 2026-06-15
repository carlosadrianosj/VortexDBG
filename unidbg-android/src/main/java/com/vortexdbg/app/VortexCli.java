package com.vortexdbg.app;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * CLI do Vortex-DBG (A1) — invoca um método de classe do app off-device.
 *
 * Ex.:
 *   java com.vortexdbg.app.VortexCli \
 *     --classes app-classes.jar --android-all android-all.jar \
 *     --class org.cf.crypto.XORCrypt --method encode \
 *     --types String,String --args "hello,k3y"
 *
 * (As classes do app vêm de JEB/dex2jar — extração manual, fora do escopo do Vortex.)
 */
public class VortexCli {

    public static void main(String[] args) throws Exception {
        Map<String, String> o = parse(args);
        if (o.containsKey("help") || !o.containsKey("class")) {
            usage();
            return;
        }

        VortexSession.Builder b = VortexSession.builder();
        if (o.containsKey("classes")) for (String p : o.get("classes").split(",")) b.classes(new File(p.trim()));
        if (o.containsKey("android-all")) b.androidAll(new File(o.get("android-all")));
        if (o.containsKey("so")) for (String p : o.get("so").split(",")) b.nativeLib(new File(p.trim()));
        if (o.containsKey("sdk")) b.sdk(Integer.parseInt(o.get("sdk")));
        if (o.containsKey("arch")) b.arch64(!"32".equals(o.get("arch")));
        if (o.containsKey("verbose")) b.verbose(true);
        if (o.containsKey("jni-onload")) b.callJniOnLoad(true);

        try (VortexSession s = b.open()) {
            String cls = o.get("class");
            String method = o.getOrDefault("method", "main");
            Class<?>[] types = parseTypes(o.get("types"));
            Object[] vals = parseArgs(o.get("args"), types);
            Object r = s.invokeStatic(cls, method, types, vals);
            System.out.println("=> " + stringify(r));
        }
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> o = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--")) {
                String key = a.substring(2);
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    o.put(key, args[++i]);
                } else {
                    o.put(key, "true");
                }
            }
        }
        return o;
    }

    private static Class<?>[] parseTypes(String spec) {
        if (spec == null || spec.isEmpty()) return new Class<?>[0];
        String[] parts = spec.split(",");
        Class<?>[] types = new Class<?>[parts.length];
        for (int i = 0; i < parts.length; i++) types[i] = typeOf(parts[i].trim());
        return types;
    }

    private static Class<?> typeOf(String t) {
        switch (t) {
            case "int": return int.class;
            case "long": return long.class;
            case "boolean": return boolean.class;
            case "double": return double.class;
            case "float": return float.class;
            case "String": case "java.lang.String": return String.class;
            case "int[]": return int[].class;
            case "byte[]": return byte[].class;
            default:
                try { return Class.forName(t); } catch (ClassNotFoundException e) {
                    throw new IllegalArgumentException("tipo desconhecido: " + t);
                }
        }
    }

    private static Object[] parseArgs(String spec, Class<?>[] types) {
        if (types.length == 0) return new Object[0];
        String[] parts = spec == null ? new String[0] : spec.split(",");
        if (parts.length != types.length) {
            throw new IllegalArgumentException("nº de --args (" + parts.length + ") != nº de --types (" + types.length + ")");
        }
        Object[] vals = new Object[types.length];
        for (int i = 0; i < types.length; i++) vals[i] = convert(parts[i].trim(), types[i]);
        return vals;
    }

    private static Object convert(String s, Class<?> t) {
        if (t == int.class) return Integer.parseInt(s);
        if (t == long.class) return Long.parseLong(s);
        if (t == boolean.class) return Boolean.parseBoolean(s);
        if (t == double.class) return Double.parseDouble(s);
        if (t == float.class) return Float.parseFloat(s);
        return s; // String e demais
    }

    private static String stringify(Object r) {
        if (r == null) return "null";
        if (r instanceof byte[]) {
            StringBuilder sb = new StringBuilder();
            for (byte x : (byte[]) r) sb.append(String.format("%02x", x & 0xff));
            return sb.toString();
        }
        return String.valueOf(r);
    }

    private static void usage() {
        System.out.println("Vortex-DBG CLI\n" +
                "  --classes <jar[,jar]>     .class do app (JEB/dex2jar)\n" +
                "  --android-all <jar>       camada de framework (Robolectric android-all)\n" +
                "  --so <lib[,lib]>          bibliotecas nativas do app\n" +
                "  --sdk <n>                 sdk Android (default 23)\n" +
                "  --arch <32|64>            arquitetura (default 64)\n" +
                "  --class <fqcn>            classe alvo (obrigatório)\n" +
                "  --method <nome>           método estático (default main)\n" +
                "  --types <t,t>             tipos dos parâmetros (int,String,...)\n" +
                "  --args <v,v>              valores dos argumentos\n" +
                "  --verbose --jni-onload");
    }
}
