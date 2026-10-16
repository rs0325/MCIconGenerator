package mcicon.qa;

import io.github.rs0325.mcicon.worker.shaded.asm.*;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

/** 実Mainの変換境界と未知入力の拒否を、ゲームを起動せず確認する。 */
public final class StressTransformCheck {
    public static void main(String[] args) throws Exception {
        List<ClassFileTransformer> transformers = new ArrayList<>();
        Instrumentation instrumentation = (Instrumentation)Proxy.newProxyInstance(
                StressTransformCheck.class.getClassLoader(), new Class<?>[]{Instrumentation.class},
                (proxy, method, values) -> {
                    if (!method.getName().equals("addTransformer")) throw new AssertionError(method);
                    transformers.add((ClassFileTransformer)values[0]);
                    return null;
                });
        GameOshiStressAgent.premain(args[0], instrumentation);
        if (transformers.size() != 1) throw new AssertionError("transformer数");
        byte[] source;
        try (JarFile client = new JarFile(args[1])) {
            source = client.getInputStream(client.getJarEntry("net/minecraft/client/main/Main.class")).readAllBytes();
        }
        source = StartupStageCheck.historicalInput("net/minecraft/client/main/Main", source, Path.of(args[2]));
        if (args.length == 4) source[20] ^= 1;
        byte[] changed = transformers.get(0).transform(null, "net/minecraft/client/main/Main", null, null, source);
        int[] calls = {0};
        new ClassReader(changed).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int opcode, String owner, String method, String descriptor, boolean itf) {
                        if (owner.equals("mcicon/qa/GameOshiStressAgent") && method.equals("probe")) calls[0]++;
                    }
                };
            }
        }, 0);
        if (calls[0] != 1) throw new AssertionError("probe数");
        if (transformers.get(0).transform(null, "not/the/target", null, null, source) != null) throw new AssertionError("対象外");
        Files.write(Path.of(args[0]).getParent().resolve("Main-with-probe.class"), changed);
        System.out.println("固定Mainのprobe1箇所・対象外維持 PASS");
    }
}
