package mcicon.qa;

import io.github.rs0325.mcicon.qa.SpawnGuard;
import io.github.rs0325.mcicon.worker.shaded.asm.*;
import java.io.FileOutputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/** 固定1.21.4のpreload直前で実OSHIを反復する、製品外の有限QA。 */
public final class GameOshiStressAgent {
    private static FileOutputStream log;

    public static void premain(String path, Instrumentation instrumentation) throws Exception {
        log = new FileOutputStream(Path.of(path).getParent().resolve("oshi-stress.tsv").toFile());
        boolean guard;
        try (var input = GameOshiStressAgent.class.getResourceAsStream("/qa-guard")) {
            String value = new String(input.readAllBytes(), StandardCharsets.US_ASCII).trim();
            if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("guard設定");
            guard = Boolean.parseBoolean(value);
        }
        if (guard) SpawnGuard.premain(null, instrumentation);
        mark("agent.ready", Boolean.toString(guard));
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> type,
                                              ProtectionDomain domain, byte[] bytes) {
                if (!name.equals("net/minecraft/client/main/Main")) return null;
                try {
                    String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                    if (!sha.equals("0f6cac210e06f12a82dcee3e3397012814a0ef984b5153989bff09a7f0a69065"))
                        throw new IllegalArgumentException("固定worker変換後Mainが不一致: " + sha);
                    int[] hooks = {0};
                    ClassReader reader = new ClassReader(bytes);
                    ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                    reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                        @Override public MethodVisitor visitMethod(int access, String method, String desc,
                                                                   String sig, String[] exceptions) {
                            MethodVisitor original = super.visitMethod(access, method, desc, sig, exceptions);
                            if (!method.equals("main") || !desc.equals("([Ljava/lang/String;)V")) return original;
                            return new MethodVisitor(Opcodes.ASM9, original) {
                                @Override public void visitMethodInsn(int opcode, String owner, String name,
                                                                      String descriptor, boolean itf) {
                                    if (opcode == Opcodes.INVOKESTATIC && owner.equals("o") && name.equals("g") && descriptor.equals("()V")) {
                                        super.visitMethodInsn(Opcodes.INVOKESTATIC, "mcicon/qa/GameOshiStressAgent", "probe", "()V", false);
                                        hooks[0]++;
                                    }
                                    super.visitMethodInsn(opcode, owner, name, descriptor, itf);
                                }
                            };
                        }
                    }, 0);
                    if (hooks[0] != 1) throw new IllegalStateException("preload hook件数: " + hooks[0]);
                    mark("main.transformed", sha);
                    return writer.toByteArray();
                } catch (Throwable failure) {
                    failure.printStackTrace(System.err);
                    mark("transform.failed", failure.toString());
                    Runtime.getRuntime().halt(97);
                    return null;
                }
            }
        });
    }

    private static synchronized void mark(String event, String detail) {
        try {
            log.write((event + "\t" + System.nanoTime() + "\t" + detail + "\n").getBytes(StandardCharsets.UTF_8));
            log.flush();
        } catch (Exception failure) {
            failure.printStackTrace(System.err);
            Runtime.getRuntime().halt(98);
        }
    }

    public static void probe() throws Exception {
        mark("probe.init", "oshi.util.ExecutingCommand");
        Class<?> oshi = Class.forName("oshi.util.ExecutingCommand", true, Thread.currentThread().getContextClassLoader());
        Method command = oshi.getMethod("runNative", String[].class);
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicLong bodies = new AtomicLong();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread[] creators = new Thread[2];
        long deadline = System.nanoTime() + 90_000_000_000L;
        int completed = 0;
        try {
            for (int index = 0; index < creators.length; index++) {
                creators[index] = new Thread(() -> {
                    try {
                        while (running.get()) {
                            Thread target = new Thread(() -> {
                                bodies.incrementAndGet();
                                LockSupport.parkNanos(100_000);
                            }, "oshi-stress-short");
                            target.setDaemon(true);
                            target.start();
                            target.join();
                        }
                    } catch (Throwable error) { failure.compareAndSet(null, error); }
                }, "oshi-stress-creator-" + index);
                creators[index].setDaemon(true);
                creators[index].start();
            }
            long readyDeadline = System.nanoTime() + 3_000_000_000L;
            while (bodies.get() < 100 && failure.get() == null && System.nanoTime() < readyDeadline) Thread.sleep(1);
            if (bodies.get() < 100 || failure.get() != null) throw new AssertionError("churn未開始", failure.get());
            mark("probe.started", Long.toString(bodies.get()));
            while (completed < 10000 && System.nanoTime() < deadline) {
                mark("command.enter", completed + "\t" + bodies.get());
                Object result = command.invoke(null, (Object)new String[]{"lspci", "-vnnm"});
                if (!(result instanceof List<?>) || !((List<?>)result).isEmpty()) throw new AssertionError("欠落lspciの結果契約");
                mark("command.returned", completed + "\t" + bodies.get());
                completed++;
            }
        } finally {
            running.set(false);
            for (Thread creator : creators) {
                if (creator == null) continue;
                creator.join(2000);
                if (creator.isAlive()) throw new AssertionError("creator終了期限");
            }
        }
        if (failure.get() != null) throw new AssertionError("creator異常", failure.get());
        mark("probe.complete", completed + "\t" + bodies.get());
    }
}
