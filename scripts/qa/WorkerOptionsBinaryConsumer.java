import io.github.rs0325.mcicon.WorkerOptions;
import java.nio.file.Path;
import java.time.Duration;

public final class WorkerOptionsBinaryConsumer {
    public static void main(String[] args) {
        var five=new WorkerOptions(Path.of("agent.jar"),"context","image",Duration.ofSeconds(180),Duration.ofSeconds(30));
        var six=new WorkerOptions(Path.of("agent.jar"),"context","image",Duration.ofSeconds(180),Duration.ofSeconds(30),"/opt/java/java21");
        if(!five.agentJar().equals(six.agentJar())||five.javaHome()!=null||!six.javaHome().equals("/opt/java/java21"))throw new AssertionError("従来constructorとaccessorの互換性");
        System.out.println("旧5/6引数consumer: PASS");
    }
}
