package io.github.rs0325.mcicon;

public final class McIconException extends RuntimeException {
    private final String code;
    private final int exitCode;
    public McIconException(String code, String message, int exitCode) { super(message); this.code=code; this.exitCode=exitCode; }
    public McIconException(String code, String message, int exitCode, Throwable cause) { super(message,cause); this.code=code; this.exitCode=exitCode; }
    public String code() { return code; }
    public int exitCode() { return exitCode; }
}
