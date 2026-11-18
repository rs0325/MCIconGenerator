package io.github.rs0325.mcicon.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** LeaseTrustで認証済みのreaderだけを、有限の出力と期限で実行する。 */
final class FileIdentityReader {
    enum Kind {START_FAILED,TIMEOUT,NONZERO_EXIT,OUTPUT_LIMIT,INVALID_RESPONSE,INTERRUPTED,READ_FAILED,CLEANUP_FAILED}
    static final class Failure extends IOException {
        final Kind kind;
        Integer exitCode;
        int stdoutBytes;
        boolean collected;
        String operation,nativeKind;
        Long win32Code;
        final String exceptionClass;
        final int line,column;
        Failure(Kind kind,Throwable cause){
            super("file identityの取得に失敗しました",kind==Kind.INVALID_RESPONSE?null:cause);this.kind=kind;
            exceptionClass=cause==null?null:cause.getClass().getName();
            var location=kind==Kind.INVALID_RESPONSE&&cause instanceof JsonProcessingException error?error.getLocation():null;
            line=location==null?-1:location.getLineNr();column=location==null?-1:location.getColumnNr();
        }
        @Override public String getMessage() {
            return super.getMessage()+": phase=FILE_IDENTITY kind="+kind+" exitCode="+exitCode+" stdoutBytes="+stdoutBytes+" collected="+collected
                    +(exceptionClass==null?"":" exceptionClass="+exceptionClass)
                    +(line>0?" line="+line:"")+(column>0?" column="+column:"")
                    +(operation==null?"":" nativeKind="+nativeKind+" operation="+operation+" win32Code="+win32Code);
        }
    }
    private static final class Capture {
        final byte[] bytes;
        volatile int size;
        volatile IOException failure;
        final Thread thread;
        Capture(InputStream input,int limit) {
            bytes=new byte[limit];
            thread=Thread.ofVirtual().start(()->{
                try(input){while(size<bytes.length){int count=input.read(bytes,size,bytes.length-size);if(count<0)break;size+=count;}}
                catch(IOException error){failure=error;}
            });
        }
        byte[] value(){return Arrays.copyOf(bytes,size);}
    }
    static String read(Path executable,Path file) throws IOException {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        Process child;
        try {child=new ProcessBuilder(executable.toString(),"file-identity",file.toString()).start();}
        catch(IOException failure){throw new Failure(Kind.START_FAILED,failure);}
        return read(child,end);
    }
    static String read(Process child,long end) throws IOException {
        long workEnd=end-TimeUnit.MILLISECONDS.toNanos(100);
        Capture stdout=new Capture(child.getInputStream(),4096),stderr=new Capture(child.getErrorStream(),512);
        Failure original=null;
        try {
            child.getOutputStream().close();
            while(child.isAlive()||stdout.thread.isAlive()||stderr.thread.isAlive()) {
                if(stdout.size==4096)throw new Failure(Kind.OUTPUT_LIMIT,null);
                if(stdout.failure!=null)throw new Failure(Kind.READ_FAILED,stdout.failure);
                long left=workEnd-System.nanoTime();if(left<=0)throw new Failure(Kind.TIMEOUT,new TimeoutException("file identityの期限を超えました"));
                if(child.isAlive())child.waitFor(Math.min(left,TimeUnit.MILLISECONDS.toNanos(10)),TimeUnit.NANOSECONDS);
                else {join(stdout.thread,Math.min(workEnd,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(10)));join(stderr.thread,Math.min(workEnd,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(10)));}
            }
            if(stdout.size==4096)throw new Failure(Kind.OUTPUT_LIMIT,null);
            if(stdout.failure!=null)throw new Failure(Kind.READ_FAILED,stdout.failure);
            if(child.exitValue()!=0) {
                Failure failure=new Failure(Kind.NONZERO_EXIT,null);nativeFailure(failure,stderr);throw failure;
            }
            try {
                JsonNode value=LeaseSnapshot.read(stdout.value());LeaseSnapshot.fields(value,"schemaVersion","volumeSerial","fileId");
                if(LeaseSnapshot.integer(value,"schemaVersion",1)!=1||!LeaseSnapshot.text(value,"volumeSerial",20).matches("[0-9]{1,20}")||!LeaseSnapshot.text(value,"fileId",32).matches("[0-9a-f]{32}"))throw new IOException("file identity応答が不正です");
                return "windows-file-id-v1:"+value.path("volumeSerial").asText()+":"+value.path("fileId").asText();
            }catch(IOException failure){throw new Failure(Kind.INVALID_RESPONSE,failure);}
        }catch(InterruptedException failure){original=new Failure(Kind.INTERRUPTED,failure);Thread.currentThread().interrupt();throw original;}
        catch(Failure failure){original=failure;throw failure;}
        catch(IOException failure){original=new Failure(Kind.READ_FAILED,failure);throw original;}
        finally {
            boolean interrupted=Thread.interrupted();
            try {
                if(child.isAlive())child.destroyForcibly();
                long left=end-System.nanoTime();if(child.isAlive()&&left>0)child.waitFor(left,TimeUnit.NANOSECONDS);
                join(stdout.thread,end);join(stderr.thread,end);
                boolean collected=!child.isAlive()&&!stdout.thread.isAlive()&&!stderr.thread.isAlive();
                if(original!=null)original.collected=collected;
                if(!collected)throw new Failure(Kind.CLEANUP_FAILED,null);
            }catch(InterruptedException failure){interrupted=true;Failure cleanup=new Failure(Kind.CLEANUP_FAILED,failure);if(original!=null)original.addSuppressed(cleanup);else {original=cleanup;throw original;}}
            catch(IOException|RuntimeException failure){Failure cleanup=failure instanceof Failure diagnostic?diagnostic:new Failure(Kind.CLEANUP_FAILED,failure);if(original!=null)original.addSuppressed(cleanup);else {original=cleanup;throw original;}}
            finally {
                if(original!=null){original.stdoutBytes=stdout.size;original.exitCode=child.isAlive()?null:child.exitValue();}
                if(interrupted)Thread.currentThread().interrupt();
            }
        }
    }
    private static void join(Thread thread,long end) throws InterruptedException {
        long left=end-System.nanoTime();if(thread.isAlive()&&left>0)thread.join(java.time.Duration.ofNanos(left));
    }
    private static void nativeFailure(Failure failure,Capture stderr) {
        if(stderr.size==512||stderr.failure!=null)return;
        try {
            JsonNode value=LeaseSnapshot.read(stderr.value());LeaseSnapshot.fields(value,"schemaVersion","phase","kind","operation","win32Code");
            if(LeaseSnapshot.integer(value,"schemaVersion",1)!=1||!LeaseSnapshot.text(value,"phase",32).equals("file-identity"))return;
            String kind=LeaseSnapshot.text(value,"kind",16),operation=LeaseSnapshot.text(value,"operation",32);
            if(!Set.of("CreateFileW","FileAttributeTagInfo","FileIdInfo","FileStandardInfo").contains(operation))return;
            Long code=null;
            if(kind.equals("WIN32")){long number=LeaseSnapshot.integer(value,"win32Code",0);if(number>0xffffffffL)return;code=number;}
            else if(!kind.equals("CONTRACT")||!value.path("win32Code").isNull())return;
            failure.nativeKind=kind;failure.operation=operation;failure.win32Code=code;
        }catch(IOException ignored){/* 固定schema以外のstderrは診断に採用しない。 */}
    }
}
