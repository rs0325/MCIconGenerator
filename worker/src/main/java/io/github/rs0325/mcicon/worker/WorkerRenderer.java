package io.github.rs0325.mcicon.worker;
import com.google.gson.*;
public interface WorkerRenderer {
    String environment() throws Exception;
    JsonArray catalog() throws Exception;
    JsonObject render(JsonObject request) throws Exception;
}
