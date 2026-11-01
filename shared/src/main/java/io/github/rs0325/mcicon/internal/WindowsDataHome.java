package io.github.rs0325.mcicon.internal;

import java.nio.file.Path;
import java.util.Locale;

/** Windowsの任意data rootを解決する、bootstrap/core共通の内部実装。 */
public final class WindowsDataHome {
    private WindowsDataHome() {}

    /**
     * WindowsでのみMCICON_DATA_HOMEを読み、通常のdrive付き絶対pathを正規化する。
     * 未指定または他OSではnullを返す。展開・trim・I/O・directory作成は行わず、
     * owner/ACL等の安全性検査は利用先の責務とする。不正指定時に旧rootへfallbackしない。
     * 明示runtime/cacheの優先順位は各consumerが決める。
     * @return 指定された絶対root、または未指定を表すnull
     * @throws IllegalArgumentException 指定が空、不正、相対、UNC/device pathの場合
     */
    public static Path configuredRoot() {
        if(!System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows"))return null;
        String value=System.getenv("MCICON_DATA_HOME");if(value==null)return null;
        if(!value.matches("^[A-Za-z]:[\\\\/].*"))throw new IllegalArgumentException("MCICON_DATA_HOMEは通常のdrive付き絶対pathを指定してください");
        Path path=Path.of(value);
        if(!path.isAbsolute())throw new IllegalArgumentException("MCICON_DATA_HOMEは通常のdrive付き絶対pathを指定してください");
        return path.normalize();
    }
}
