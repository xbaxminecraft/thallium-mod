package dev.thallium;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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

    /** Bir oyuncunun seçtiği kozmetikler. cape: "thallium", "custom" ya da null. tv: yüklenen dosyaların sürümü. */
    private record Cos(String cape, boolean skin, boolean slim, int tv) {}

    /** Şu an çevrimiçi olan Thallium oyuncuları ve kozmetikleri (arka plan iş parçacığı günceller, çizim okur). */
    private static volatile Map<UUID, Cos> players = Map.of();
    /** İndirilip oyuna kaydedilmiş dokular. Anahtar: "uuid/skin/sürüm" ya da "uuid/cape/sürüm". */
    private static final Map<String, Identifier> TEX = new ConcurrentHashMap<>();
    private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();
    private static ScheduledExecutorService ex;

    public static boolean isThallium(UUID id) {
        return id != null && players.containsKey(id);
    }

    private static String key(UUID id, String kind, int tv) {
        return id + "/" + kind + "/" + tv;
    }

    /** Bu oyuncunun pelerin dokusu (Thallium pelerini ya da yüklediği), yoksa null. */
    public static Identifier capeFor(UUID id) {
        Cos c = id == null ? null : players.get(id);
        if (c == null || c.cape() == null) return null;
        if ("thallium".equals(c.cape())) return CAPE_THALLIUM;
        if ("custom".equals(c.cape())) return TEX.get(key(id, "cape", c.tv()));
        return null;
    }

    /** Bu oyuncunun yüklediği skin dokusu (indirilmişse), yoksa null. */
    public static Identifier skinFor(UUID id) {
        Cos c = id == null ? null : players.get(id);
        return c != null && c.skin() ? TEX.get(key(id, "skin", c.tv())) : null;
    }

    public static boolean slimFor(UUID id) {
        Cos c = id == null ? null : players.get(id);
        return c != null && c.slim();
    }

    /** İsmin başına gelen etiket: altın sarısı, kalın değil, köşeli parantez içinde "Th". */
    public static MutableText prefix() {
        return Text.literal("[Th] ").setStyle(Style.EMPTY.withColor(0xFFAA00));
    }

    @Override
    public void onInitializeClient() {
        if (API == null || TOKEN == null) return;
        ex = Executors.newScheduledThreadPool(2, r -> {
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
                JsonElement c = p.get("cape");
                String cape = c != null && !c.isJsonNull() ? c.getAsString() : null;
                boolean skin = p.has("skin") && p.get("skin").getAsBoolean();
                boolean slim = p.has("model") && "slim".equals(p.get("model").getAsString());
                int tv = p.has("tv") ? p.get("tv").getAsInt() : 0;
                found.put(id, new Cos(cape, skin, slim, tv));
            }
        }
        players = found;
        // Yüklenen dosyaları (kendi pelerin / skin) arka planda indir
        for (Map.Entry<UUID, Cos> e : found.entrySet()) {
            Cos c = e.getValue();
            if ("custom".equals(c.cape())) fetchTexture(e.getKey(), "cape", c.tv());
            if (c.skin()) fetchTexture(e.getKey(), "skin", c.tv());
        }
    }

    /** Sunucudan PNG'yi indirip oyunun doku sistemine kaydeder (bir kez, sürüm başına). */
    private static void fetchTexture(UUID id, String kind, int tv) {
        String k = key(id, kind, tv);
        if (TEX.containsKey(k) || !PENDING.add(k)) return;
        ex.execute(() -> {
            try {
                HttpResponse<byte[]> r = HTTP.send(
                        HttpRequest.newBuilder(URI.create(API + "/tex/" + id + "/" + kind + "?v=" + tv)).timeout(Duration.ofSeconds(8)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                if (r.statusCode() != 200) { PENDING.remove(k); return; }
                byte[] data = r.body();
                MinecraftClient mc = MinecraftClient.getInstance();
                mc.execute(() -> { // dokular sadece oyun iş parçacığında kaydedilebilir
                    try {
                        NativeImage img = NativeImage.read(new ByteArrayInputStream(data));
                        Identifier ident = Identifier.of("thallium", kind + "/" + id + "/" + tv);
                        mc.getTextureManager().registerTexture(ident, new NativeImageBackedTexture(img));
                        TEX.put(k, ident);
                    } catch (Throwable ignored) {
                    } finally {
                        PENDING.remove(k);
                    }
                });
            } catch (Throwable t) {
                PENDING.remove(k);
            }
        });
    }
}
