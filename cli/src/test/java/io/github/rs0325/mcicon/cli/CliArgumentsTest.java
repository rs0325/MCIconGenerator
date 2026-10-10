package io.github.rs0325.mcicon.cli;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CliArgumentsTest {
    @Test void everySubcommandShowsHelpWithoutRequiredArgumentsOrIo() {
        assertEquals(0,Main.execute("--help"));
        for(String command:new String[]{"versions","list","render","extract","doctor","cache"}) {
            assertEquals(0,Main.execute(command,"--help"),command);
            assertEquals(0,Main.execute(command,"-h"),command);
        }
    }
    @Test void idAndAllAreMutuallyExclusiveWithoutStartingWorker() {
        assertEquals(2,Main.execute("render","--version","1.21.4","--all","--id","stone","--out","out"));
        assertEquals(2,Main.execute("extract","--version","1.21.4","--all","--texture","item/diamond","--out","out"));
    }
    @Test void invalidIdAndSizeReturnUsageErrorsWithoutNetwork() {
        assertEquals(2,Main.execute("render","--version","1.21.4","--id","../stone","--out","out"));
        assertEquals(2,Main.execute("render","--version","1.21.4","--id","stone","--size","63","--out","out"));
        assertEquals(2,Main.execute("render","--version","1.21.4","--id","stone","--item-timeout","0","--out","out"));
    }
}
