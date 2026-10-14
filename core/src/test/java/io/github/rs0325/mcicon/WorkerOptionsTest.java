package io.github.rs0325.mcicon;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkerOptionsTest {
    @Test void previousConstructorsKeepTheDefaultBudgetAndJavaSelection() {
        var old=new WorkerOptions(Path.of("agent.jar"),"context","image",Duration.ofSeconds(1),Duration.ofSeconds(1));
        var selected=new WorkerOptions(Path.of("agent.jar"),"context","image",Duration.ofSeconds(1),Duration.ofSeconds(1),"/java");
        for(var options:new WorkerOptions[]{old,selected}) {
            assertEquals(3072,options.containerMemoryMiB());assertEquals(2048,options.heapMemoryMiB());
        }
        assertNull(old.javaHome());assertEquals("/java",selected.javaHome());
    }
    @Test void explicitBudgetIsNotAdjustedAndRejectsInvalidPairs() {
        var options=new WorkerOptions(Path.of("agent.jar"),"context","image",Duration.ofSeconds(1),Duration.ofSeconds(1),null,4096,2560);
        assertEquals(4096,options.containerMemoryMiB());assertEquals(2560,options.heapMemoryMiB());
        for(int[] pair:new int[][]{{0,1},{-1,1},{1,0},{1,-1},{1,1},{1,2}})
            assertThrows(IllegalArgumentException.class,()->new WorkerOptions(Path.of("agent.jar"),"context","image",Duration.ofSeconds(1),Duration.ofSeconds(1),null,pair[0],pair[1]));
        var small=new WorkerOptions(Path.of("agent.jar"),"context","image",Duration.ofSeconds(1),Duration.ofSeconds(1),null,2,1);
        assertEquals(2,small.containerMemoryMiB());assertEquals(1,small.heapMemoryMiB());
    }
}
