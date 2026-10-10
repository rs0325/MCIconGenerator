import io.github.rs0325.mcicon.*;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletionException;

/** Local acceptance actor; uses only the public API and runtime-downloaded assets. */
public final class ExportFaultProbe {
    public static void main(String[] args) throws Exception {
        String mode=args[0];
        CancellationToken token=new CancellationToken();
        ProgressListener progress=event->{
            if(event.phase().equals("extract")&&event.completed()==3) {
                if(mode.equals("cancel"))token.cancel();
                if(mode.equals("hold")) {
                    System.out.println("READY_FOR_KILL");System.out.flush();
                    try {new CountDownLatch(1).await();}
                    catch(InterruptedException e){Thread.currentThread().interrupt();}
                }
            }
        };
        try(var generator=new MCIconGenerator(Path.of(args[1]),true,progress,token)) {
            var result=generator.extract(new ExtractRequest("1.13",List.of(),true,Path.of(args[2]),false,false)).join();
            System.out.println("complete="+result.complete()+" succeeded="+result.succeeded()+" failed="+result.failed());
            if(mode.equals("cancel")?(result.complete()||result.succeeded()!=3||result.failed()==0):!result.complete())
                throw new AssertionError("Unexpected fault/recovery result");
        } catch(CompletionException error) {
            Throwable cause=error;
            while(cause.getCause()!=null)cause=cause.getCause();
            System.err.println("ROOT_CAUSE: "+cause);System.exit(1);
        }
    }
}
