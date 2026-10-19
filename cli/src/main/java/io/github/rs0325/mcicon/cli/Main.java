package io.github.rs0325.mcicon.cli;

import io.github.rs0325.mcicon.*;
import io.github.rs0325.mcicon.internal.Json;
import io.github.rs0325.mcicon.internal.ToolVersion;
import picocli.CommandLine;
import picocli.CommandLine.*;
import java.nio.file.Path;
import java.io.IOException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.concurrent.*;
import java.time.Duration;

@Command(name="mcicon", version="MCIconGenerator " + ToolVersion.VERSION, mixinStandardHelpOptions=true,
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
        return new CommandLine(root).setExecutionStrategy(parsed->{
            try(StartupControl control=StartupControl.open(request(parsed),ToolVersion.VERSION,Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()))) {
                for(CommandLine line:parsed.asCommandLineList()){Common common=common(line.getCommand());if(common!=null)common.startup=control;}
                if(control!=null&&control.switching())return 0;return new CommandLine.RunLast().execute(parsed);
            }catch(CommandLine.ExecutionException failure){throw failure;}
            catch(Exception failure){return root.failure(failure,parsed.commandSpec().commandLine());}
            finally{for(CommandLine line:parsed.asCommandLineList()){Common common=common(line.getCommand());if(common!=null)common.startup=null;}}
        }).setExecutionExceptionHandler((exception,line,result)->root.failure(exception,line));
    }
    private int failure(Throwable cause,CommandLine line) {
        while(cause instanceof CompletionException&&cause.getCause()!=null)cause=cause.getCause();
        String code=cause instanceof McIconException m?m.code():cause instanceof IllegalArgumentException?"INVALID_REQUEST":"INTERNAL_ERROR";
        int exit=cause instanceof McIconException m?m.exitCode():cause instanceof IllegalArgumentException?2:1;
        line.getErr().println(code+": "+cause.getMessage());if(debug)cause.printStackTrace(line.getErr());line.getErr().flush();return exit;
    }
    private static Common common(Object command) {
        return switch(command){case Render value->value.common;case Catalog value->value.common;case Doctor value->value.common;case Cache value->value.common;case Extract value->value.common;case Versions value->value.common;default->null;};
    }
    static ObjectNode request(CommandLine.ParseResult parsed) {
        CommandLine.ParseResult leaf=parsed;boolean help=false,version=false;
        while(true){help|=leaf.isUsageHelpRequested();version|=leaf.isVersionHelpRequested();if(!leaf.hasSubcommand())break;leaf=leaf.subcommand();}
        Object command=leaf.commandSpec().userObject();Common common=common(command);WorkerArguments worker=switch(command){case Render value->value.worker;case Catalog value->value.worker;case Doctor value->value.worker;default->null;};
        if(!help&&!version){if(command instanceof Render render)render.prepareRequest();else if(command instanceof Catalog catalog)catalog.prepareKind();}
        boolean managed=!help&&!version&&worker!=null&&worker.managed();ManagedOptions options=managed?worker.managedOptions():ManagedOptions.defaults();
        ObjectNode request=StartupControl.JSON.createObjectNode().put("command",help?"help":version?"version":command instanceof Main?"root":leaf.commandSpec().name()).put("managed",managed).put("offline",common!=null&&common.offline).put("help",help||version).put("preparationMillis",options.preparationTimeout().toMillis()).put("startupMillis",options.startupTimeout().toMillis()).put("itemMillis",options.itemTimeout().toMillis());request.set("args",StartupControl.JSON.valueToTree(parsed.originalArgs()));return request;
    }
    @Command(name="doctor",description="管理環境または指定DockerのJava・Mesa・adapterを診断します。")
    static final class Doctor implements Callable<Integer> {
        @Mixin Common common;
        @Mixin ReleaseHelp help;
        @Mixin WorkerArguments worker;
        @Option(names="--version",required=true) String version;
        public Integer call() throws Exception {
            boolean managed=worker.managed();
            try(MCIconGenerator generator=common.generator(new CancellationToken())) {
                DiagnosticReport report=(managed?generator.doctorManaged(version,worker.managedOptions()):generator.doctor(version,worker.options())).join();
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
        StartupControl startup;
        @Option(names="--cache",description="Input cache directory.") Path cache=Path.of(".mcicon-cache");
        @Option(names="--offline",description="Use verified cached inputs only.") boolean offline;
        @Option(names="--minecraft-dir",description="既存インストールの公式入力を検証してcacheへコピーする。") Path minecraft;
        @Option(names="--json",description="Write structured JSON to stdout.") boolean json;
        MCIconGenerator generator(CancellationToken token) {
            StartupControl control=startup;if(control!=null)control.cancellation(token::cancel);
            return new MCIconGenerator(cache,offline,event->{if(control!=null&&event.phase().equals("startup-ready"))control.ready();System.err.printf("%s %s %d/%d%n",event.phase(),event.id(),event.completed(),event.total());},token,minecraft);
        }
    }
    // The standard help mixin also defines --version, which conflicts with the
    // Minecraft release selector and causes picocli to remove that entire mixin.
    static class ReleaseHelp {
        @Option(names={"-h","--help"},usageHelp=true,description="Show this help message and exit.") boolean help;
    }
    static class WorkerArguments {
        @Spec Model.CommandSpec spec;
        @Option(names="--runtime",description="実行方式: managed または docker。既定はmanaged、旧Docker指定があればdocker。") String runtime;
        @Option(names="--worker-jar",description="Rendering agent JAR.") Path agent;
        @Option(names="--docker-context",defaultValue="colima-mcicon") String context;
        @Option(names="--docker-image",defaultValue="mcicon-render:dev") String image;
        @Option(names="--startup-timeout",defaultValue="180",description="Startup timeout in seconds.") int startup;
        @Option(names="--item-timeout",defaultValue="30",description="Per-item timeout in seconds.") int timeout;
        @Option(names="--java-home",description="Docker image内のJavaディレクトリ。ホストのJavaは使用しない。") String javaHome;
        @Option(names="--memory-mib",defaultValue="3072",description="ゲームworkerのcontainerメモリ上限（正のMiB整数）。") int memory;
        @Option(names="--heap-mib",defaultValue="2048",description="ゲームworkerのJava heap上限（正のMiB整数、container未満）。") int heap;
        boolean managed() {
            if(runtime!=null&&!Set.of("managed","docker").contains(runtime))throw new IllegalArgumentException("--runtimeはmanagedまたはdockerを指定してください");
            boolean docker=List.of("--docker-context","--docker-image","--worker-jar","--java-home").stream().anyMatch(spec.commandLine().getParseResult()::hasMatchedOption);
            if("managed".equals(runtime)&&docker)throw new IllegalArgumentException("--runtime managedと旧Docker指定は併用できません");
            return !"docker".equals(runtime)&&!docker;
        }
        ManagedOptions managedOptions() {
            ManagedOptions defaults=ManagedOptions.defaults();
            return new ManagedOptions(defaults.runtimeRoot(),defaults.preparationTimeout(),Duration.ofSeconds(startup),Duration.ofSeconds(timeout),memory,heap);
        }
        WorkerOptions options() throws Exception {
            Path path=agent;
            if(path==null) {
                Path location=Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                path=location.getParent().getParent().resolve("worker/worker-agent.jar");
            }
            return new WorkerOptions(path,context,image,Duration.ofSeconds(startup),Duration.ofSeconds(timeout),javaHome,memory,heap);
        }
    }
    @Command(name="list",description="Read item/block registries from the exact vanilla client.")
    static final class Catalog implements Callable<Integer> {
        @Mixin Common common;
        @Mixin ReleaseHelp help;
        @Mixin WorkerArguments worker;
        @Option(names="--version",required=true) String version;
        @Option(names="--kind",defaultValue="all") String kind;
        String validatedKind;
        void prepareKind(){if(!Set.of("all","item","block").contains(kind))throw new McIconException("INVALID_KIND","Kind must be all, item, or block",2);validatedKind=kind;}
        public Integer call() throws Exception {
            boolean managed=worker.managed();
            try(MCIconGenerator generator=common.generator(new CancellationToken())) {
                List<CatalogEntry> entries=(managed?generator.listEntriesManaged(version,worker.managedOptions()):generator.listEntries(version,worker.options())).join().stream().filter(e->validatedKind.equals("all")||e.kind().equals(validatedKind)).toList();
                if(common.json)System.out.println(Json.MAPPER.writeValueAsString(entries));
                else for(CatalogEntry entry:entries)System.out.println(entry.id()+"\t"+entry.kind()+"\t"+entry.status());
                return 0;
            }
        }
    }
    @Command(name="render",description="版のsupport状態に従いインベントリPNGを生成します。")
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
        @Option(names="--allow-experimental",description="experimental版の描画を許可します。verified版では不要です。") boolean allow;
        static final class Selection {
            @Option(names="--id") List<String> ids;
            @Option(names="--all") boolean all;
        }
        RenderRequest prepared;
        void prepareRequest() {
            try {prepared=new RenderRequest(version,selection.ids==null?List.of():selection.ids.stream().map(ResourceId::parse).toList(),selection.all,kind,size,output,overwrite,failFast,allow);}
            catch(IllegalArgumentException e){throw new McIconException("INVALID_REQUEST",e.getMessage(),2);}
        }
        public Integer call() throws Exception {
            RenderRequest request=prepared;boolean managed=worker.managed();
            CancellationToken token=new CancellationToken();Thread hook=new Thread(token::cancel,"mcicon-cancel");Runtime.getRuntime().addShutdownHook(hook);
            try(MCIconGenerator generator=common.generator(token)) {
                ExportResult result=(managed?generator.renderManaged(request,worker.managedOptions()):generator.render(request,worker.options())).join();
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
