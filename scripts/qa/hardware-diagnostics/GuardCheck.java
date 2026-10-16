public final class GuardCheck {
    public static void main(String[] args) throws Exception {
        Runtime.getRuntime().exec(new String[]{"lspci", "-vnnm"});
        throw new AssertionError("guardが起動を拒否しなかった");
    }
}
