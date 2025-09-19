package com.jacocanete.autoshutdown;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.*;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public class PterodactylAPI {
    private final OkHttpClient client;
    private final String apiUrl;
    private final String apiKey;

    public PterodactylAPI(String apiUrl, String apiKey) {
        this.client = new OkHttpClient();
        this.apiUrl = apiUrl.endsWith("/") ? apiUrl : apiUrl + "/";
        this.apiKey = apiKey;
    }

    public CompletableFuture<Boolean> startServer(String serverId) {
        return sendPowerSignal(serverId, "start");
    }

    public CompletableFuture<Boolean> stopServer(String serverId) {
        return sendPowerSignal(serverId, "stop");
    }

    private CompletableFuture<Boolean> sendPowerSignal(String serverId, String signal) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonObject powerAction = new JsonObject();
                powerAction.addProperty("signal", signal);

                RequestBody body = RequestBody.create(
                    MediaType.parse("application/json"),
                    powerAction.toString()
                );

                Request request = new Request.Builder()
                    .url(apiUrl + "api/client/servers/" + serverId + "/power")
                    .post(body)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .build();

                try (Response response = client.newCall(request).execute()) {
                    return response.isSuccessful();
                }
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
        });
    }

    public CompletableFuture<String> getServerStatus(String serverId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Request request = new Request.Builder()
                    .url(apiUrl + "api/client/servers/" + serverId + "/resources")
                    .get()
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Accept", "application/json")
                    .build();

                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        String responseBody = response.body().string();
                        JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
                        JsonObject attributes = json.getAsJsonObject("attributes");
                        return attributes.get("current_state").getAsString();
                    }
                    return "offline";
                }
            } catch (IOException e) {
                e.printStackTrace();
                return "offline";
            }
        });
    }

    public void shutdown() {
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }
}