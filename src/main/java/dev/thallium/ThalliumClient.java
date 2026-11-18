package dev.thallium;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Launcher oyunu açarken şu JVM argümanlarını verir:
 *   -Dthallium.api=https://...   -Dthallium.token=...
 * Argümanlar yoksa mod hiçbir şey yapmaz (launcher dışında açılan oyunda sessiz kalır).
 */
public class ThalliumClient implements ClientModInitializer {
    private static final String API = System.getProperty("thallium.api");
    private static final String TOKEN = System.getProperty("thallium.token");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** Şu an çevrimiçi olan Thallium oyuncuları (arka plan iş parçacığı günceller, çizim okur). */
    private static volatile Set<UUID> known = Set.of();

    public static boolean isThallium(UUID id) {
        return id != null && known.contains(id);
    }

    public static MutableText prefix() {
        return Text.literal("Thallium ").setStyle(Style.EMPTY.withColor(0x52B043).withBold(true));
    }

    @Override
    public void onInitializeClient() {
        if (API == null || TOKEN == null) return;
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "thallium-net");
            t.setDaemon(true);
            return t;
        });
        ex.scheduleWithFixedDelay(() -> safe(ThalliumClient::heartbeat), 5, 30, TimeUnit.SECONDS);
        ex.scheduleWithFixedDelay(() -> safe(ThalliumClient::refresh), 8, 10, TimeUnit.SECONDS);
    }

    private static void safe(ThrowingRunnable r) {
        try { r.run(); } catch (Throwable ignored) { /* ağ hatası oyunu etkilemesin */ }
    }

    private interface ThrowingRunnable { void run() throws Exception; }

    private static HttpRequest.Builder req(String path) {
        return HttpRequest.newBuilder(URI.create(API + path))
                .timeout(Duration.ofSeconds(8))
                .header("content-type", "application/json")
                .header("authorization", "Bearer " + TOKEN);
    }

    /** "Ben çevrimiçiyim" bildirimi (sadece bir dünyadayken). */
    private static void heartbeat() throws Exception {
        if (MinecraftClient.getInstance().world == null) return;
        HTTP.send(req("/online").POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.discarding());
    }

    /** Oyuncu listesindeki herkesi sunucuya sor, Thallium kullanıcılarını kaydet. */
    private static void refresh() throws Exception {
        MinecraftClient mc = MinecraftClient.getInstance();
        CompletableFuture<List<String>> ids = new CompletableFuture<>();
        mc.execute(() -> { // oyuncu listesi sadece oyun iş parçacığında güvenle okunur
            try {
                List<String> out = new ArrayList<>();
                ClientPlayNetworkHandler h = mc.getNetworkHandler();
                if (h != null) for (PlayerListEntry e : h.getPlayerList()) out.add(e.getProfile().getId().toString());
                ids.complete(out);
            } catch (Throwable t) {
                ids.completeExceptionally(t);
            }
        });
        List<String> all = ids.get(5, TimeUnit.SECONDS);
        Set<UUID> found = new HashSet<>();
        for (int i = 0; i < all.size(); i += 100) { // sunucu tek sorguda en fazla 100 kişi kabul eder
            JsonArray arr = new JsonArray();
            for (String s : all.subList(i, Math.min(all.size(), i + 100))) arr.add(s);
            JsonObject body = new JsonObject();
            body.add("uuids", arr);
            HttpResponse<String> r = HTTP.send(req("/lookup").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return; // hata varsa eski listeyi koru
            JsonObject players = JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonObject("players");
            for (String k : players.keySet()) found.add(UUID.fromString(k));
        }
        known = found;
    }
}
