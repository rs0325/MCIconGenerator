package mcicon.qa;

import java.io.FileOutputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.HexFormat;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** 旧1.21.4の起動境界だけを記録するQA agent。製品配布には含めない。 */
public final class StartupStageAgent {
    private static FileOutputStream output;
    private static final String MAIN = "net/minecraft/client/main/Main";
    private static final String RECORDER = "mcicon/qa/StartupStageAgent";

    public static void premain(String path, Instrumentation instrumentation) throws Exception {
        output = new FileOutputStream(path);
        mark("agent.ready");
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> type,
                                    ProtectionDomain domain, byte[] bytes) {
                if (!MAIN.equals(name) && !"hkl".equals(name)) return null;
                try {
                    return instrument(name, bytes);
                } catch (Throwable failure) {
                    // transformer例外をJVMに無視させ、無計装の成功へすり替えない。
                    mark("agent.transform.failed");
                    failure.printStackTrace(System.err);
                    Runtime.getRuntime().halt(97);
                    return null;
                }
            }
        });
    }

    public static synchronized void mark(String stage) {
        try {
            output.write((stage + "\t" + System.nanoTime() + "\t" +
                    Thread.currentThread().threadId() + "\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();
        } catch (Exception failure) {
            Runtime.getRuntime().halt(98);
        }
    }

    static byte[] instrument(String name, byte[] bytes) throws Exception {
        String expected = MAIN.equals(name)
                ? "0f6cac210e06f12a82dcee3e3397012814a0ef984b5153989bff09a7f0a69065"
                : "83291f476b4ff65345b76cfd9bad2b3d1308827c9c9639d4f5ff4a643aa0009b";
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException("旧worker変換後のclassではありません: " + name + " " + actual);
        }
        ClassReader reader = new ClassReader(bytes);
        // frameを再計算してゲームclassを先行loadしない。記録呼出しの前後でstackは不変。
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String method, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor target = super.visitMethod(access, method, descriptor, signature, exceptions);
                boolean main = MAIN.equals(name) && method.equals("main") && descriptor.equals("([Ljava/lang/String;)V");
                boolean initializer = name.equals("hkl") && method.equals("<clinit>");
                if (!main && !initializer) return target;
                return new MethodVisitor(Opcodes.ASM9, target) {
                    private boolean telemetry;

                    private void stage(String value) {
                        super.visitLdcInsn(value);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, RECORDER, "mark", "(Ljava/lang/String;)V", false);
                    }

                    @Override public void visitCode() {
                        super.visitCode();
                        stage(main ? "main.entry" : "hkl.enter");
                    }

                    @Override public void visitInsn(int opcode) {
                        if (initializer && opcode == Opcodes.RETURN) stage("hkl.exit");
                        super.visitInsn(opcode);
                    }

                    @Override public void visitFieldInsn(int opcode, String owner, String field, String desc) {
                        super.visitFieldInsn(opcode, owner, field, desc);
                        if (main && !telemetry && opcode == Opcodes.GETSTATIC && owner.equals("hkl") && field.equals("a")) {
                            telemetry = true;
                            stage("main.telemetry.ready");
                        }
                    }

                    @Override public void visitTypeInsn(int opcode, String type) {
                        if (main && opcode == Opcodes.NEW && type.equals("flk")) stage("client.before");
                        super.visitTypeInsn(opcode, type);
                    }

                    @Override public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean itf) {
                        boolean bootstrap = main && opcode == Opcodes.INVOKESTATIC && owner.equals("akx") && method.equals("a") && desc.equals("()V");
                        boolean join = main && owner.equals("java/util/concurrent/CompletableFuture") && method.equals("join") && desc.equals("()Ljava/lang/Object;");
                        if (bootstrap) stage("bootstrap.before");
                        if (join) stage("join.before");
                        super.visitMethodInsn(opcode, owner, method, desc, itf);
                        if (bootstrap) stage("bootstrap.after");
                        if (join) stage("join.after");
                        if (main && opcode == Opcodes.INVOKESPECIAL && owner.equals("flk") && method.equals("<init>")) stage("client.after");
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }
}
