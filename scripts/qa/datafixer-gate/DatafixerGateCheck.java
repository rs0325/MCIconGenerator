package mcicon.qa;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** 元変換順と一意のgate挿入、待機・解除・期限失敗をゲームなしで確認する。 */
public final class DatafixerGateCheck {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        if (args.length <= 2) {
            if (args.length == 2) Files.writeString(directory.resolve("datafixer-release"), "late release\n");
            DatafixerGateAgent.setup(directory, 0);
            DatafixerGateAgent.wrap(Runnable::run).execute(() -> { throw new AssertionError("期限後にtaskが実行された"); });
            throw new AssertionError("期限を成功として通過した");
        }
        try (JarFile jar = new JarFile(args[1])) {
            byte[] raw = jar.getInputStream(jar.getJarEntry("bao.class")).readAllBytes();
            byte[] input = StartupStageCheck.historicalInput("bao", raw, Path.of(args[2]));
            byte[] output = DatafixerGateAgent.instrument(input);
            int[] hooks = {0};
            new ClassReader(output).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner, String method, String descriptor, boolean itf) {
                            if (owner.equals("mcicon/qa/DatafixerGateAgent") && method.equals("wrap")) {
                                if (!name.equals("a") || !desc.equals("(Ljava/util/Set;)Ljava/util/concurrent/CompletableFuture;")) throw new AssertionError(name);
                                hooks[0]++;
                            }
                        }
                    };
                }
            }, 0);
            if (hooks[0] != 1) throw new AssertionError("gateが一意でない");
            System.out.println("bao raw=" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)) +
                    " worker=" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input)));
            input[input.length - 1] ^= 1;
            try {
                DatafixerGateAgent.instrument(input);
                throw new AssertionError("非対応classを受け入れた");
            } catch (IllegalArgumentException expected) { }
        }
        DatafixerGateAgent.setup(directory, TimeUnit.SECONDS.toNanos(5));
        AtomicBoolean executed = new AtomicBoolean();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = CompletableFuture.runAsync(() -> executed.set(true), DatafixerGateAgent.wrap(executor));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!Files.readString(directory.resolve("datafixer-gate.tsv")).contains("gate.waiting")) {
                if (System.nanoTime() >= deadline) throw new AssertionError("待機に到達しない");
                Thread.sleep(10);
            }
            if (executed.get() || future.isDone()) throw new AssertionError("解除前にtaskが実行された");
            Files.writeString(directory.resolve("datafixer-release"), "release\n");
            future.get(2, TimeUnit.SECONDS);
        }
        if (!executed.get()) throw new AssertionError("解除後にtaskが実行されない");
        System.out.println("旧変換後のgate一意性/hash拒否/保留/解除 PASS");
    }
}
