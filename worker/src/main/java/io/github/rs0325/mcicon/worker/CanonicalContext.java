package io.github.rs0325.mcicon.worker;

import java.lang.reflect.Method;
import java.awt.image.BufferedImage;
import java.util.Calendar;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/** Only game-owned call sites are redirected. Worker deadlines retain the real clock. */
public final class CanonicalContext {
    public static final long EPOCH_MILLIS=946684800000L;
    private static final ThreadLocal<Random> random=new ThreadLocal<Random>();
    private static final ConcurrentHashMap<String,Method> randomMethods=new ConcurrentHashMap<String,Method>();
    private static final ThreadLocal<BufferedImage> probeImage=new ThreadLocal<BufferedImage>();
    private static final ThreadLocal<Float> background=new ThreadLocal<Float>();
    private CanonicalContext() {}
    public static void beginProbe(float color) {begin();background.set(color);}
    static void probeImage(BufferedImage image) {probeImage.set(image);}
    static BufferedImage probeImage() {return probeImage.get();}
    public static boolean opaqueProbe() {return background.get()!=null;}
    public static float backgroundColor() {return opaqueProbe()?background.get():0f;}
    public static float backgroundAlpha() {return opaqueProbe()?1f:0f;}
    public static void begin() {random.set(new Random(0));}
    public static void end() {random.remove();background.remove();probeImage.remove();}
    public static boolean active() {return random.get()!=null;}
    // 描画外ではバニラの時計供給元と時刻の基準を維持する。
    public static long elapsedMillis(long original) {return active()?1000L:original;}
    public static long nanos(long original) {return active()?1000000000L:original;}
    public static long epochMillis(long original) {return active()?EPOCH_MILLIS:original;}
    public static double mathRandom() {return active()?random.get().nextDouble():Math.random();}
    public static void fixCalendar(Object calendar) {
        try {
            if(calendar instanceof Calendar)((Calendar)calendar).setTimeInMillis(EPOCH_MILLIS);
            else calendar.getClass().getMethod("setTimeInMillis",long.class).invoke(calendar,EPOCH_MILLIS);
        } catch(Exception e) {throw new IllegalStateException("Cannot fix vanilla calendar",e);}
    }
    public static float nextFloat(Object source) {return active()?random.get().nextFloat():((Number)forward(source,"nextFloat")).floatValue();}
    public static double nextDouble(Object source) {return active()?random.get().nextDouble():((Number)forward(source,"nextDouble")).doubleValue();}
    public static int nextInt(Object source) {return active()?random.get().nextInt():((Number)forward(source,"nextInt")).intValue();}
    public static int nextInt(Object source,int bound) {return active()?random.get().nextInt(bound):((Number)forward(source,"nextInt",bound)).intValue();}
    public static long nextLong(Object source) {return active()?random.get().nextLong():((Number)forward(source,"nextLong")).longValue();}
    public static boolean nextBoolean(Object source) {return active()?random.get().nextBoolean():(Boolean)forward(source,"nextBoolean");}
    private static Object forward(Object source,String name,Object...args) {
        if(source instanceof Random) {
            Random r=(Random)source;
            if(name.equals("nextFloat"))return r.nextFloat();
            if(name.equals("nextDouble"))return r.nextDouble();
            if(name.equals("nextLong"))return r.nextLong();
            if(name.equals("nextBoolean"))return r.nextBoolean();
            return args.length==0?r.nextInt():r.nextInt((Integer)args[0]);
        }
        try {
            String key=name+args.length;
            Method method=randomMethods.get(key);
            if(method==null) {
                Class<?> type=RenderAgent.symbols.type("net.minecraft.util.RandomSource");
                method=type.getMethod(RenderAgent.symbols.methodName("net.minecraft.util.RandomSource",name,args.length),args.length==0?new Class<?>[0]:new Class<?>[]{int.class});
                randomMethods.putIfAbsent(key,method);
            }
            return method.invoke(source,args);
        } catch(Exception e) {throw new IllegalStateException("Cannot invoke vanilla random source",e);}
    }
}
