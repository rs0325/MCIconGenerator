package io.github.rs0325.mcicon.cli;

import io.github.rs0325.mcicon.*;
import io.github.rs0325.mcicon.internal.Json;
import picocli.CommandLine;
import picocli.CommandLine.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.time.Duration;

@Command(name="mcicon", version="MCIconGenerator 0.1.0-SNAPSHOT", mixinStandardHelpOptions=true,
        description="Export vanilla Minecraft Java textures and inventory icons.",
        subcommands={Main.Versions.class,Main.Extract.class,Main.Render.class,Main.Catalog.class,Main.Doctor.class,Main.Cache.class})
public final class Main implements Runnable {
    @Spec Model.CommandSpec spec;
    @Option(names="--debug",scope=ScopeType.INHERIT,description="Print exception causes to stderr on failure.") boolean debug;
    @Override public void run() {spec.commandLine().usage(System.out);}
    public static void main(String[] args) {System.exit(execute(args));}
    public static int execute(String...args) {
        return commandLine().execute(args);
    }
    static CommandLine commandLine() {
        Main root=new Main();
        return new CommandLine(root).setExecutionExceptionHandler((exception,line,result)-> {
            Throwable cause=exception;
            while(cause instanceof CompletionException&&cause.getCause()!=null)cause=cause.getCause();
            String code=cause instanceof McIconException m?m.code():cause instanceof IllegalArgumentException?"INVALID_REQUEST":"INTERNAL_ERROR";
            int exit=cause instanceof McIconException m?m.exitCode():cause instanceof IllegalArgumentException?2:1;
            line.getErr().println(code+": "+cause.getMessage());
            if(root.debug)cause.printStackTrace(line.getErr());
            line.getErr().flush();return exit;
        });
    }
    @Command(name="doctor",description="Check the local Docker image, JVM, Mesa, and adapter.")
    static final class Doctor implements Callable<Integer> {
        @Mixin Common common;
        @Mixin ReleaseHelp help;
        @Mixin WorkerArguments worker;
        @Option(names="--version",required=true) String version;
        public Integer call() throws Exception {
            try(MCIconGenerator generator=common.generator(new CancellationToken())) {
                DiagnosticReport report=generator.doctor(version,worker.options()).join();
                if(common.json)System.out.println(Json.MAPPER.writeValueAsString(report));
                else for(var check:report.checks())System.out.println((check.passed()?"PASS":"FAIL")+"\t"+check.name()+"\t"+check.detail());
                return report.healthy()?0:1;
            }
        }
    }
    @Command(name="cache",description="Inspect or verify cached input SHA-256 checksums.",mixinStandardHelpOptions=true)
    static final class Cache implements Callable<Integer> {
        @Mixin Common common;
        @Parameters(index="0",defaultValue="status",description="status or verify") String action;
        public Integer call() throws Exception {
            if(!Set.of("status","verify").contains(action))throw new McIconException("INVALID_CACHE_ACTION","Use cache status or cache verify",2);
            try(MCIconGenerator generator=common.generator(new CancellationToken())) {
                CacheReport report=generator.cache(action.equals("verify")).join();
                if(common.json)System.out.println(Json.MAPPER.writeValueAsString(report));
                else System.out.printf("%d files, %d bytes, %d verified, %d failures%n",report.files(),report.bytes(),report.verified(),report.failures().size());
                return report.failures().isEmpty()?0:1;
            }
        }
    }
    static class Common {
        @Option(names="--cache",description="Input cache directory.") Path cache=Path.of(".mcicon-cache");
        @Option(names="--offline",description="Use verified cached inputs only.") boolean offline;
        @Option(names="--json",description="Write structured JSON to stdout.") boolean json;
        MCIconGenerator generator(CancellationToken token) {
            return new MCIconGenerator(cache,offline,event->System.err.printf("%s %s %d/%d%n",event.phase(),event.id(),event.completed(),event.total()),token);
        }
    }
    // The standard help mixin also defines --version, which conflicts with the
    // Minecraft release selector and causes picocli to remove that entire mixin.
    static class ReleaseHelp {
        @Option(names={"-h","--help"},usageHelp=true,description="Show this help message and exit.") boolean help;
    }
    static class WorkerArguments {
        @Option(names="--worker-jar",description="Rendering agent JAR.") Path agent;
        @Option(names="--docker-context",defaultValue="colima-mcicon") String context;
        @Option(names="--docker-image",defaultValue="mcicon-render:dev") String image;
        @Option(names="--startup-timeout",defaultValue="180",description="Startup timeout in seconds.") int startup;
        @Option(names="--item-timeout",defaultValue="30",description="Per-item timeout in seconds.") int timeout;
        WorkerOptions options() throws Exception {
            Path path=agent;
            if(path==null) {
                Path location=Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                path=location.getParent().getParent().resolve("worker/worker-agent.jar");
            }
            return new WorkerOptions(path,context,image,Duration.ofSeconds(startup),Duration.ofSeconds(timeout));
        }
    }
    @Command(name="list",description="Read item/block registries from the exact vanilla client.")
    static final class Catalog implements Callable<Integer> {
        @Mixin Common common;
        @Mixin ReleaseHelp help;
        @Mixin WorkerArguments worker;
        @Option(names="--version",required=true) String version;
        @Option(names="--kind",defaultValue="all") String kind;
        public Integer call() throws Exception {
            if(!Set.of("all","item","block").contains(kind))throw new McIconException("INVALID_KIND","Kind must be all, item, or block",2);
            try(MCIconGenerator generator=common.generator(new CancellationToken())) {
                List<CatalogEntry> entries=generator.listEntries(version,worker.options()).join().stream().filter(e->kind.equals("all")||e.kind().equals(kind)).toList();
                if(common.json)System.out.println(Json.MAPPER.writeValueAsString(entries));
                else for(CatalogEntry entry:entries)System.out.println(entry.id()+"\t"+entry.kind()+"\t"+entry.status());
                return 0;
            }
        }
    }
    @Command(name="render",description="Render inventory PNGs using an experimental vanilla adapter.")
    static final class Render implements Callable<Integer> {
        @Mixin Common common;
        @Mixin ReleaseHelp help;
        @Mixin WorkerArguments worker;
        @Option(names="--version",required=true) String version;
        @ArgGroup(exclusive=true,multiplicity="1") Selection selection;
        @Option(names="--kind",defaultValue="all") String kind;
        @Option(names="--size",defaultValue="64") int size;
        @Option(names="--out",required=true) Path output;
        @Option(names="--overwrite") boolean overwrite;
        @Option(names="--fail-fast") boolean failFast;
        @Option(names="--allow-experimental") boolean allow;
        static final class Selection {
            @Option(names="--id") List<String> ids;
            @Option(names="--all") boolean all;
        }
        public Integer call() throws Exception {
            RenderRequest request;
            try {request=new RenderRequest(version,selection.ids==null?List.of():selection.ids.stream().map(ResourceId::parse).toList(),selection.all,kind,size,output,overwrite,failFast,allow);}
            catch(IllegalArgumentException e){throw new McIconException("INVALID_REQUEST",e.getMessage(),2);}
            CancellationToken token=new CancellationToken();Thread hook=new Thread(token::cancel,"mcicon-cancel");Runtime.getRuntime().addShutdownHook(hook);
            try(MCIconGenerator generator=common.generator(token)) {
                ExportResult result=generator.render(request,worker.options()).join();
                if(common.json)System.out.println(Json.MAPPER.writeValueAsString(result));
                else System.out.printf("%s: %d succeeded, %d failed; manifest: %s%n",result.version(),result.succeeded(),result.failed(),result.manifest());
                return result.exitCode();
            }finally{Runtime.getRuntime().removeShutdownHook(hook);}
        }
    }
    @Command(name="versions",description="List official Java releases from 1.13 onward.",mixinStandardHelpOptions=true)
    static final class Versions implements Callable<Integer> {
        @Mixin Common common;
        public Integer call() throws Exception {
            try(MCIconGenerator generator=common.generator(new CancellationToken())) {
                List<VersionInfo> versions=generator.listVersions().join();
                if(common.json)System.out.println(Json.MAPPER.writeValueAsString(versions));
                else for(VersionInfo v:versions)System.out.println(v.id()+"\t"+v.support());
                return 0;
            }
        }
    }
    @Command(name="extract",description="Extract original PNG textures and animation metadata.")
    static final class Extract implements Callable<Integer> {
        @Mixin Common common;
        @Mixin ReleaseHelp help;
        @Option(names="--version",required=true,description="Exact release ID, or latest.") String version;
        @ArgGroup(exclusive=true,multiplicity="1") Selection selection;
        @Option(names="--out",required=true) Path output;
        @Option(names="--overwrite") boolean overwrite;
        @Option(names="--fail-fast") boolean failFast;
        static final class Selection {
            @Option(names="--texture",description="Texture ID; repeat for multiple textures.") List<String> textures;
            @Option(names="--all",description="All textures/item and textures/block PNGs.") boolean all;
        }
        public Integer call() throws Exception {
            List<ResourceId> ids=new ArrayList<>();
            try {if(selection.textures!=null)for(String value:selection.textures)ids.add(ResourceId.parse(value));}
            catch(IllegalArgumentException e) {throw new McIconException("INVALID_TEXTURE_ID",e.getMessage(),2);}
            CancellationToken token=new CancellationToken();
            Thread hook=new Thread(token::cancel,"mcicon-cancel");Runtime.getRuntime().addShutdownHook(hook);
            try(MCIconGenerator generator=common.generator(token)) {
                ExportResult result=generator.extract(new ExtractRequest(version,ids,selection.all,output,overwrite,failFast)).join();
                if(common.json)System.out.println(Json.MAPPER.writeValueAsString(result));
                else System.out.printf("%s: %d succeeded, %d failed; manifest: %s%n",result.version(),result.succeeded(),result.failed(),result.manifest());
                return result.exitCode();
            } finally {Runtime.getRuntime().removeShutdownHook(hook);}
        }
    }
}
