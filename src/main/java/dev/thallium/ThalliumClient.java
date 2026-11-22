package dev.thallium;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    private static final Identifier CAPE_THALLIUM = Identifier.of("thallium", "textures/cape/thallium.png");

    private static final int GOLD = 0xFFAA00;
    private static final int DIAMOND = 0x2A7FA8; // sunucudaki tags tablosunda "diamond" verilen oyuncuların varsayılan rengi

    /** Bir oyuncunun kozmetikleri. cape: "thallium" ya da null. tag: özel etiket. color: seçtiği [Th] rengi (null = varsayılan). */
    private record Cos(String cape, String tag, Integer color) {}

    /** Şu an çevrimiçi olan Thallium oyuncuları (arka plan iş parçacığı günceller, çizim okur). */
    private static volatile Map<UUID, Cos> players = Map.of();

    public static boolean isThallium(UUID id) {
        return id != null && players.containsKey(id);
    }

    /** Bu oyuncunun Thallium pelerini seçiliyse dokusu, yoksa null. */
    public static Identifier capeFor(UUID id) {
        Cos c = id == null ? null : players.get(id);
        return c != null && "thallium".equals(c.cape()) ? CAPE_THALLIUM : null;
    }

    /** İsmin başına gelen etiket: köşeli parantez içinde "Th". Renk: oyuncunun seçtiği, yoksa özel etiket, yoksa altın sarısı. */
    public static MutableText prefix(UUID id) {
        Cos c = id == null ? null : players.get(id);
        int color = GOLD;
        if (c != null) {
            if (c.color() != null) color = c.color();
            else if ("diamond".equals(c.tag())) color = DIAMOND;
        }
        return Text.literal("[Th] ").setStyle(Style.EMPTY.withColor(color));
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

    /** Oyuncu listesindeki herkesi sunucuya sor, Thallium kullanıcılarını ve kozmetiklerini kaydet. */
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
        Map<UUID, Cos> found = new HashMap<>();
        for (int i = 0; i < all.size(); i += 100) { // sunucu tek sorguda en fazla 100 kişi kabul eder
            JsonArray arr = new JsonArray();
            for (String s : all.subList(i, Math.min(all.size(), i + 100))) arr.add(s);
            JsonObject body = new JsonObject();
            body.add("uuids", arr);
            HttpResponse<String> r = HTTP.send(req("/lookup").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return; // hata varsa eski listeyi koru
            JsonObject ps = JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonObject("players");
            for (Map.Entry<String, JsonElement> e : ps.entrySet()) {
                UUID id = UUID.fromString(e.getKey());
                JsonObject p = e.getValue().getAsJsonObject();
                found.put(id, new Cos(str(p, "cape"), str(p, "tag"), color(p)));
            }
        }
        players = found;
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && !e.isJsonNull() ? e.getAsString() : null;
    }

    /** Sunucudan gelen "#rrggbb" rengini sayıya çevirir; yoksa ya da bozuksa null. */
    private static Integer color(JsonObject o) {
        String s = str(o, "color");
        if (s == null || !s.matches("#[0-9a-fA-F]{6}")) return null;
        return Integer.parseInt(s.substring(1), 16);
    }
}
