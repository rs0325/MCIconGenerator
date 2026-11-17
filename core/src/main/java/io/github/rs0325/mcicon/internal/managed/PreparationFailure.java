package io.github.rs0325.mcicon.internal.managed;

import io.github.rs0325.mcicon.McIconException;
import io.github.rs0325.mcicon.internal.AdmissionEvidence;
import java.io.IOException;

/** 準備段をcauseへ添え、公開codeと回収失敗の判定を維持する。 */
final class PreparationFailure extends IOException {
    enum Stage {HELPER,RECOVERY,READER,ADMISSION}
    final Stage stage;
    private PreparationFailure(Stage stage,Throwable cause){super("managed準備に失敗しました: stage="+stage,cause);this.stage=stage;}
    @FunctionalInterface interface Operation<T> {T run() throws IOException;}
    static <T> T at(Stage stage,Operation<T> operation) throws IOException {
        try {return operation.run();}
        catch(ManagedBusyFailure|AdmissionEvidence.Busy failure){throw failure;}
        catch(IOException failure){throw new PreparationFailure(stage,failure);}
        catch(McIconException failure) {
            var wrapped=new McIconException(failure.code(),failure.getMessage(),failure.exitCode(),new PreparationFailure(stage,failure));
            for(Throwable cleanup:failure.getSuppressed())wrapped.addSuppressed(cleanup);
            throw wrapped;
        }
    }
}
