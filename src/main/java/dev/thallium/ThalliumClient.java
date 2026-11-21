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
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ThalliumClient implements ClientModInitializer {
    private static final String API = System.getProperty("thallium.api");
    private static final String TOKEN = System.getProperty("thallium.token");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private static final Identifier LOCAL_SKIN = Identifier.of("thallium", "1761b2ef1d869a2c.png");
    private static final Identifier LOCAL_CAPE = Identifier.of("thallium", "thallium_cape.png");

    private static volatile Set<UUID> known = Set.of();
    private static volatile Map<UUID, String> capes = Map.of();
    private static volatile Map<UUID, Boolean> slims = Map.of();
    private static volatile Map<String, UUID> names = Map.of();

    private static final Map<UUID, Identifier> customSkins = new ConcurrentHashMap<>();
    private static final Map<UUID, Identifier> customCapes = new ConcurrentHashMap<>();
    private static final Map<UUID, String> skinHashes = new ConcurrentHashMap<>();
    private static final Map<UUID, String> capeHashes = new ConcurrentHashMap<>();

    public static boolean isThallium(UUID id) {
        return id != null && known.contains(id);
    }

    public static Identifier skinFor(UUID id) {
        if (id == null) return null;
        Identifier custom = customSkins.get(id);
        if (custom != null) return custom;
        return isThallium(id) ? LOCAL_SKIN : null;
    }

    public static boolean slimFor(UUID id) {
        return id != null && Boolean.TRUE.equals(slims.get(id));
    }

    public static Identifier capeFor(UUID id) {
        if (id == null) return null;
        Identifier custom = customCapes.get(id);
        if (custom != null) return custom;
        if (isThallium(id)) return LOCAL_CAPE;
        String c = capes.get(id);
        return "thallium".equals(c) ? LOCAL_CAPE : null;
    }

    public static MutableText prefix() {
        return Text.literal("[Th] ").setStyle(Style.EMPTY.withColor(0xFFAA00));
    }

    public static Text injectChatPrefix(Text message) {
        if (message == null || names.isEmpty()) return message;
        String plain = message.getString().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, UUID> entry : names.entrySet()) {
            String playerName = entry.getKey();
            UUID id = entry.getValue();
            if (!playerName.isEmpty() && isThallium(id) && plain.contains(playerName)) {
                return prefix().append(message);
            }
        }
        return message;
    }

    @Override
    public void onInitializeClient() {
        if (API == null || TOKEN == null) return;
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "thallium-net");
            t.setDaemon(true);
            return t;
        });
        ex.scheduleWithFixedDelay(() -> safe(ThalliumClient::heartbeat), 2, 30, TimeUnit.SECONDS);
        ex.scheduleWithFixedDelay(() -> safe(ThalliumClient::refresh), 4, 10, TimeUnit.SECONDS);
    }

    private static void safe(ThrowingRunnable r) {
        try { r.run(); } catch (Throwable ignored) {}
    }

    private interface ThrowingRunnable { void run() throws Exception; }

    private static HttpRequest.Builder req(String path) {
        return HttpRequest.newBuilder(URI.create(API + path))
                .timeout(Duration.ofSeconds(8))
                .header("content-type", "application/json")
                .header("authorization", "Bearer " + TOKEN);
    }

    private static void heartbeat() throws Exception {
        if (MinecraftClient.getInstance().world == null) return;
        HTTP.send(req("/online").POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.discarding());
    }

    private static String strOrNull(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return (el != null && !el.isJsonNull()) ? el.getAsString() : null;
    }

    private static String cleanBase64(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        int comma = raw.indexOf(',');
        return comma >= 0 ? raw.substring(comma + 1).trim() : raw.trim();
    }

    private static void syncTexture(MinecraftClient mc, UUID id, String rawB64, String kind,
                                    Map<UUID, String> hashMap, Map<UUID, Identifier> texMap) {
        String b64 = cleanBase64(rawB64);
        if (b64 == null || b64.isEmpty()) {
            if (hashMap.remove(id) != null) {
                Identifier old = texMap.remove(id);
                if (old != null) mc.execute(() -> mc.getTextureManager().destroyTexture(old));
            }
            return;
        }
        String hash = Integer.toHexString(b64.hashCode()) + "_" + b64.length();
        if (Objects.equals(hashMap.get(id), hash)) return;

        try {
            byte[] bytes = Base64.getDecoder().decode(b64);
            NativeImage img = NativeImage.read(new ByteArrayInputStream(bytes));
            Identifier texId = Identifier.of("thallium", "dynamic/" + kind + "/" + id.toString().replace("-", ""));
            mc.execute(() -> {
                try {
                    mc.getTextureManager().registerTexture(texId, new NativeImageBackedTexture(img));
                    hashMap.put(id, hash);
                    texMap.put(id, texId);
                } catch (Throwable t) {
                    img.close();
                }
            });
        } catch (Throwable ignored) {}
    }

    private static void refresh() throws Exception {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return;

        Map<String, UUID> nameMap = new HashMap<>();
        mc.execute(() -> {
            ClientPlayNetworkHandler h = mc.getNetworkHandler();
            if (h != null) {
                for (PlayerListEntry e : h.getPlayerList()) {
                    UUID id = e.getProfile().getId();
                    String n = e.getProfile().getName();
                    if (id != null && n != null && !n.isEmpty()) {
                        synchronized (nameMap) {
                            nameMap.put(n.toLowerCase(Locale.ROOT), id);
                        }
                    }
                }
            }
            if (mc.player != null) {
                synchronized (nameMap) {
                    nameMap.putIfAbsent(mc.player.getName().getString().toLowerCase(Locale.ROOT), mc.player.getUuid());
                }
            }
        });

        Thread.sleep(300);
        Map<String, UUID> snapshot;
        synchronized (nameMap) {
            snapshot = new HashMap<>(nameMap);
        }
        if (snapshot.isEmpty()) return;

        List<String> all = new ArrayList<>();
        for (UUID id : new HashSet<>(snapshot.values())) {
            all.add(id.toString());
        }

        Set<UUID> found = new HashSet<>();
        Map<UUID, String> caps = new HashMap<>();
        Map<UUID, Boolean> slimMap = new HashMap<>();

        for (int i = 0; i < all.size(); i += 100) {
            JsonArray arr = new JsonArray();
            for (String s : all.subList(i, Math.min(all.size(), i + 100))) arr.add(s);
            JsonObject body = new JsonObject();
            body.add("uuids", arr);
            HttpResponse<String> r = HTTP.send(req("/lookup").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return;

            JsonObject players = JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonObject("players");
            for (Map.Entry<String, JsonElement> e : players.entrySet()) {
                UUID id = UUID.fromString(e.getKey());
                JsonObject p = e.getValue().getAsJsonObject();
                found.add(id);

                String cape = strOrNull(p, "cape");
                if (cape != null) caps.put(id, cape);

                String skinModel = strOrNull(p, "skinModel");
                slimMap.put(id, "slim".equalsIgnoreCase(skinModel));

                String skinPng = strOrNull(p, "skinPng");
                String capePng = strOrNull(p, "capePng");
                syncTexture(mc, id, skinPng, "skin", skinHashes, customSkins);
                syncTexture(mc, id, capePng, "cape", capeHashes, customCapes);
            }
        }

        names = snapshot;
        known = found;
        capes = caps;
        slims = slimMap;
    }
}
