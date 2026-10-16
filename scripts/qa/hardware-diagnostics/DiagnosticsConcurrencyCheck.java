package io.github.rs0325.mcicon.worker;

import com.google.gson.JsonObject;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** 配布候補の変換器と公式OSHIを使い、外部起動の省略を並行条件で確認する。 */
public final class DiagnosticsConcurrencyCheck {
    private static final String TARGET = "oshi.util.ExecutingCommand";

    public static void main(String[] args) throws Exception {
        boolean fixed = args[0].equals("fixed");
        if (!fixed && !args[0].equals("original")) throw new IllegalArgumentException("mode");
        URL[] urls = new URL[args.length-1];
        for (int i=1; i<args.length; i++) urls[i-1]=Path.of(args[i]).toUri().toURL();
        try (URLClassLoader loader=new URLClassLoader(urls, DiagnosticsConcurrencyCheck.class.getClassLoader()) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (!fixed || !name.equals(TARGET)) return super.findClass(name);
                try (InputStream input=getResourceAsStream(name.replace('.', '/')+".class")) {
                    byte[] original=input.readAllBytes();
                    byte[] changed=new HardwareDiagnosticsTransformer().transform(this, name.replace('.', '/'), null, null, original);
                    return defineClass(name, changed, 0, changed.length);
                } catch (Exception error) {
                    throw new ClassNotFoundException(name, error);
                }
            }
        }) {
            Method command=Class.forName(TARGET, true, loader).getMethod("runNative", String[].class);
            JsonObject result=new JsonObject();
            result.addProperty("mode", args[0]);
            if (!fixed) {
                try {
                    call(command);
                    throw new AssertionError("元処理の外部起動要求を検出できなかった");
                } catch (InvocationTargetException error) {
                    if (!(error.getCause() instanceof AssertionError) || !"MCICON_QA_EXTERNAL_PROCESS_REQUEST".equals(error.getCause().getMessage())) throw error;
                    result.addProperty("originalRequestDetected", true);
                }
            } else {
                AtomicBoolean running=new AtomicBoolean(true);
                AtomicLong started=new AtomicLong();
                AtomicReference<Throwable> failure=new AtomicReference<>();
                Thread creator=new Thread(() -> {
                    try {
                        while (running.get()) {
                            Thread target=new Thread(started::incrementAndGet, "api-check-short");
                            target.setDaemon(true);
                            target.start();
                            target.join();
                        }
                    } catch (Throwable error) {
                        failure.set(error);
                    }
                }, "api-check-creator");
                creator.setDaemon(true);
                creator.start();
                long calls=0;
                long until=System.nanoTime()+5_000_000_000L;
                try {
                    do {
                        List<?> values=call(command);
                        if (!values.isEmpty()) throw new AssertionError("診断結果が空ではない");
                        calls++;
                    } while (System.nanoTime()<until);
                } finally {
                    running.set(false);
                    creator.join(2000);
                }
                if (creator.isAlive() || failure.get()!=null || started.get()<2 || calls<2) throw new AssertionError("並行条件の未完了", failure.get());
                result.addProperty("calls", calls);
                result.addProperty("startedTargetBodies", started.get());
                result.addProperty("creatorJoined", true);
            }
            result.addProperty("passed", true);
            System.out.println(result);
        }
    }

    private static List<?> call(Method command) throws Exception {
        return (List<?>)command.invoke(null, (Object)new String[]{"lspci", "-vnnm"});
    }
}
