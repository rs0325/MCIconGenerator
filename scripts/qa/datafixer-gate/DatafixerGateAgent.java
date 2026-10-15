package mcicon.qa;

import java.io.FileOutputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.HexFormat;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** 最初のDatafixer taskを明示解除まで保留する、旧1.21.4専用の制御QA。 */
public final class DatafixerGateAgent {
    private static FileOutputStream output;
    private static Path release;
    private static long deadline;
    private static final AtomicBoolean first = new AtomicBoolean();

    static void setup(Path directory, long waitNanos) throws Exception {
        output = new FileOutputStream(directory.resolve("datafixer-gate.tsv").toFile());
        release = directory.resolve("datafixer-release");
        deadline = System.nanoTime() + waitNanos;
        mark("agent.ready");
    }

    public static void premain(String directory, Instrumentation instrumentation) throws Exception {
        setup(Path.of(directory), TimeUnit.SECONDS.toNanos(45));
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> type,
                                               ProtectionDomain domain, byte[] bytes) {
                if (!"bao".equals(name)) return null;
                try {
                    return instrument(bytes);
                } catch (Throwable failure) {
                    mark("agent.transform.failed");
                    failure.printStackTrace(System.err);
                    Runtime.getRuntime().halt(97);
                    return null;
                }
            }
        });
    }

    private static synchronized void mark(String name) {
        try {
            output.write((name + "\t" + System.nanoTime() + "\t" + Thread.currentThread().threadId() + "\n")
                    .getBytes(StandardCharsets.US_ASCII));
            output.flush();
        } catch (Exception failure) {
            Runtime.getRuntime().halt(98);
        }
    }

    public static Executor wrap(Executor target) {
        return task -> target.execute(() -> {
            boolean held = first.compareAndSet(false, true);
            if (held) {
                mark("gate.waiting");
                while (true) {
                    if (System.nanoTime() >= deadline) {
                        mark("gate.expired");
                        Runtime.getRuntime().halt(99);
                    }
                    if (Files.exists(release)) break;
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException failure) {
                        mark("gate.interrupted");
                        Runtime.getRuntime().halt(99);
                    }
                }
                mark("gate.released");
            }
            task.run();
            if (held) mark("gate.first-task.finished");
        });
    }

    static byte[] instrument(byte[] bytes) throws Exception {
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if (!actual.equals("909a5e42f9ae15a8adbd5a219ba67a6498227d41354df62449f7ccdea5290468")) {
            throw new IllegalArgumentException("旧worker変換後のDataFixersではありません: " + actual);
        }
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
                MethodVisitor target = super.visitMethod(access, name, desc, sig, exceptions);
                if (!name.equals("a") || !desc.equals("(Ljava/util/Set;)Ljava/util/concurrent/CompletableFuture;")) return target;
                return new MethodVisitor(Opcodes.ASM9, target) {
                    @Override public void visitMethodInsn(int opcode, String owner, String method, String descriptor, boolean itf) {
                        if (owner.equals("com/mojang/datafixers/DataFixerBuilder$Result") && method.equals("optimize") && descriptor.equals("(Ljava/util/Set;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;")) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "mcicon/qa/DatafixerGateAgent", "wrap", "(Ljava/util/concurrent/Executor;)Ljava/util/concurrent/Executor;", false);
                        }
                        super.visitMethodInsn(opcode, owner, method, descriptor, itf);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }
}
