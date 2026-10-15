package mcicon.qa;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.HexFormat;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** 変換が旧公式classの指定methodだけに境界を追加することを確認する。 */
public final class StartupStageCheck {
    static byte[] historicalInput(String name, byte[] original, Path mappings) throws Exception {
        Class<?> symbolsType = Class.forName("io.github.rs0325.mcicon.worker.Symbols");
        Object symbols = symbolsType.getConstructor(Path.class).newInstance(mappings);
        for (String type : List.of("CanonicalTransformer", "ResourceTransformer")) {
            Class<?> transformer = Class.forName("io.github.rs0325.mcicon.worker." + type);
            var constructor = transformer.getDeclaredConstructor(symbolsType, boolean.class);
            constructor.setAccessible(true);
            var transform = transformer.getDeclaredMethod("transformGameClass", String.class, byte[].class);
            transform.setAccessible(true);
            byte[] result = (byte[]) transform.invoke(constructor.newInstance(symbols, false), name, original);
            if (result != null) original = result;
        }
        return original;
    }

    public static void main(String[] args) throws Exception {
        try (JarFile jar = new JarFile(args[0])) {
            for (String name : List.of("net/minecraft/client/main/Main", "hkl")) {
                byte[] original = jar.getInputStream(jar.getJarEntry(name + ".class")).readAllBytes();
                String rawHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original));
                // 第二agentに届くのは、原本ではなく旧workerの変換を通過したbytes。
                original = historicalInput(name, original, Path.of(args[2]));
                byte[] transformed = StartupStageAgent.instrument(name, original);
                System.out.println(name + " raw=" + rawHash + " worker=" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)));
                List<String> stages = new ArrayList<>();
                new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public MethodVisitor visitMethod(int access, String method, String desc, String sig, String[] exceptions) {
                        return new MethodVisitor(Opcodes.ASM9) {
                            private Object previous;
                            @Override public void visitLdcInsn(Object value) { previous = value; }
                            @Override public void visitMethodInsn(int opcode, String owner, String target, String descriptor, boolean itf) {
                                if (owner.equals("mcicon/qa/StartupStageAgent") && target.equals("mark")) {
                                    if (!(method.equals("main") || method.equals("<clinit>"))) throw new AssertionError(method);
                                    stages.add((String) previous);
                                }
                            }
                        };
                    }
                }, 0);
                List<String> expected = name.equals("hkl") ? List.of("hkl.enter", "hkl.exit") : List.of(
                        "main.entry", "main.telemetry.ready", "bootstrap.before", "bootstrap.after",
                        "join.before", "join.after", "client.before", "client.after");
                if (!stages.equals(expected)) throw new AssertionError(stages);
                original[original.length - 1] ^= 1;
                try {
                    StartupStageAgent.instrument(name, original);
                    throw new AssertionError("異なるclass hashを受け入れた");
                } catch (IllegalArgumentException expectedFailure) { }
                Files.write(Path.of(args[1], name.replace('/', '_') + ".class"), transformed);
                System.out.println(name + " 境界/対象method/hash拒否 PASS");
            }
        }
    }
}
