package app.mcrust.core;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Вход через Microsoft по коду на другом устройстве: Microsoft -> Xbox Live -> XSTS -> Minecraft Services.
 * Игра запускается только с аккаунтом, у которого эта цепочка прошла и у которого есть профиль Minecraft Java.
 * client_id приложения Azure задаёт тот, кто собирает лаунчер (см. ANDROID.md).
 */
public final class MicrosoftAuth {
    private MicrosoftAuth() {}

    static final String DEVICE_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
    static final String TOKEN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    static final String XBL_URL = "https://user.auth.xboxlive.com/user/authenticate";
    static final String XSTS_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
    static final String MC_LOGIN_URL = "https://api.minecraftservices.com/authentication/login_with_xbox";
    static final String MC_PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";
    static final String SCOPE = "XboxLive.signin offline_access";

    /** Вход не удался по понятной причине; сообщение можно показывать пользователю. */
    public static final class AuthException extends IOException {
        public AuthException(String message) {
            super(message);
        }
    }

    public static final class DeviceCode {
        public final String deviceCode, userCode, verificationUri;
        public final int expiresIn, interval;

        DeviceCode(String deviceCode, String userCode, String verificationUri, int expiresIn, int interval) {
            this.deviceCode = deviceCode;
            this.userCode = userCode;
            this.verificationUri = verificationUri;
            this.expiresIn = expiresIn;
            this.interval = interval;
        }
    }

    /** Токены Microsoft: refresh хранится на устройстве, чтобы не просить код каждый раз. */
    public static final class Tokens {
        public final String access, refresh;

        Tokens(String access, String refresh) {
            this.access = access;
            this.refresh = refresh;
        }
    }

    public interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    private static Map<String, String> form() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/x-www-form-urlencoded");
        return h;
    }

    private static Map<String, String> json() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        return h;
    }

    private static String body(String... kv) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (b.length() > 0) b.append('&');
            b.append(Net.enc(kv[i])).append('=').append(Net.enc(kv[i + 1]));
        }
        return b.toString();
    }

    /** Шаг 1: получить код, который человек вводит на microsoft.com/link на любом устройстве. */
    public static DeviceCode start(Http h, String clientId) throws IOException {
        requireClient(clientId);
        Object j = Json.parse(h.send("POST", DEVICE_URL, form(), body("client_id", clientId, "scope", SCOPE)));
        String dc = Json.text(j, "device_code"), uc = Json.text(j, "user_code"), uri = Json.text(j, "verification_uri");
        if (dc == null || uc == null || uri == null) throw new AuthException("Microsoft не выдал код для входа");
        return new DeviceCode(dc, uc, uri, (int) Json.num(j, 900, "expires_in"), (int) Json.num(j, 5, "interval"));
    }

    /** Шаг 2: ждать, пока человек введёт код. Возвращает токены или бросает AuthException (отказ, истёк срок). */
    public static Tokens poll(Http h, String clientId, DeviceCode dc, Sleeper sleeper) throws IOException, InterruptedException {
        long waited = 0;
        long interval = Math.max(1, dc.interval);
        while (waited <= dc.expiresIn) {
            try {
                String text = h.send("POST", TOKEN_URL, form(), body("grant_type", "urn:ietf:params:oauth:grant-type:device_code",
                        "client_id", clientId, "device_code", dc.deviceCode));
                return tokens(text);
            } catch (Http.Error e) {
                String err = Json.text(safeParse(e.body), "error");
                if ("authorization_pending".equals(err)) {
                    // ждём дальше
                } else if ("slow_down".equals(err)) {
                    interval += 5;
                } else if ("authorization_declined".equals(err)) {
                    throw new AuthException("Вход отклонён на стороне Microsoft");
                } else if ("expired_token".equals(err) || "bad_verification_code".equals(err)) {
                    throw new AuthException("Код входа устарел, начните вход заново");
                } else {
                    throw new AuthException("Microsoft отклонил вход" + (err == null ? "" : ": " + err));
                }
            }
            sleeper.sleep(interval * 1000);
            waited += interval;
        }
        throw new AuthException("Код входа не введён вовремя, начните вход заново");
    }

    /** Обновление без участия человека по сохранённому refresh-токену. */
    public static Tokens refresh(Http h, String clientId, String refreshToken) throws IOException {
        requireClient(clientId);
        try {
            return tokens(h.send("POST", TOKEN_URL, form(), body("grant_type", "refresh_token", "client_id", clientId,
                    "refresh_token", refreshToken, "scope", SCOPE)));
        } catch (Http.Error e) {
            throw new AuthException("Сохранённый вход больше не действует, войдите заново");
        }
    }

    private static Tokens tokens(String text) throws IOException {
        Object j = Json.parse(text);
        String a = Json.text(j, "access_token");
        if (a == null) throw new AuthException("Microsoft не вернул токен доступа");
        return new Tokens(a, Json.text(j, "refresh_token"));
    }

    private static Object safeParse(String s) {
        try {
            return Json.parse(s);
        } catch (IOException e) {
            return null;
        }
    }

    /** Шаг 3: Xbox Live -> XSTS -> Minecraft; проверяет, что у аккаунта есть игра. */
    public static Account login(Http h, String msaAccessToken) throws IOException {
        Object xbl = Json.parse(h.send("POST", XBL_URL, json(), "{\"Properties\":{\"AuthMethod\":\"RPS\",\"SiteName\":\"user.auth.xboxlive.com\","
                + "\"RpsTicket\":" + Json.write("d=" + msaAccessToken) + "},\"RelyingParty\":\"http://auth.xboxlive.com\",\"TokenType\":\"JWT\"}"));
        String xblToken = Json.text(xbl, "Token");
        if (xblToken == null) throw new AuthException("Xbox Live не выдал токен");

        Object xsts;
        try {
            xsts = Json.parse(h.send("POST", XSTS_URL, json(), "{\"Properties\":{\"SandboxId\":\"RETAIL\",\"UserTokens\":[" + Json.write(xblToken)
                    + "]},\"RelyingParty\":\"rp://api.minecraftservices.com/\",\"TokenType\":\"JWT\"}"));
        } catch (Http.Error e) {
            throw new AuthException(xstsMessage(e.body));
        }
        String xstsToken = Json.text(xsts, "Token");
        Object claims = Json.arr(Json.at(xsts, "DisplayClaims", "xui")).isEmpty() ? null : Json.arr(Json.at(xsts, "DisplayClaims", "xui")).get(0);
        String uhs = Json.text(claims, "uhs");
        if (xstsToken == null || uhs == null) throw new AuthException("Xbox не выдал токен для Minecraft");

        Object mc = Json.parse(h.send("POST", MC_LOGIN_URL, json(), "{\"identityToken\":" + Json.write("XBL3.0 x=" + uhs + ";" + xstsToken) + "}"));
        String token = Json.text(mc, "access_token");
        if (token == null) throw new AuthException("Minecraft не принял вход");

        Map<String, String> auth = new LinkedHashMap<>();
        auth.put("Authorization", "Bearer " + token);
        Object profile;
        try {
            profile = Json.parse(h.send("GET", MC_PROFILE_URL, auth, null));
        } catch (Http.Error e) {
            if (e.status == 404) {
                throw new AuthException("У этого аккаунта Microsoft нет Minecraft: Java Edition. Войдите аккаунтом, на котором куплена игра");
            }
            throw e;
        }
        String id = Json.text(profile, "id"), name = Json.text(profile, "name");
        if (id == null || name == null) throw new AuthException("Профиль Minecraft не найден");
        return new Account(name, dashed(id), token, uhs);
    }

    private static String xstsMessage(String body) {
        String xerr = String.valueOf((long) Json.num(safeParse(body), 0, "XErr"));
        switch (xerr) {
            case "2148916233":
                return "У аккаунта Microsoft нет профиля Xbox. Создайте его на xbox.com и повторите вход";
            case "2148916235":
                return "Xbox Live недоступен в стране этого аккаунта";
            case "2148916236":
            case "2148916237":
                return "Аккаунту нужна проверка возраста на странице Xbox";
            case "2148916238":
                return "Детский аккаунт: взрослый должен добавить его в семейную группу Microsoft";
            default:
                return "Xbox отклонил вход (код " + xerr + ")";
        }
    }

    /** id профиля Minecraft приходит без дефисов; игра ожидает UUID с ними. */
    static String dashed(String id) {
        if (id.length() != 32) return id;
        return id.substring(0, 8) + "-" + id.substring(8, 12) + "-" + id.substring(12, 16) + "-" + id.substring(16, 20) + "-" + id.substring(20);
    }

    private static void requireClient(String clientId) throws AuthException {
        if (clientId == null || clientId.trim().isEmpty()) {
            throw new AuthException("Не задан client_id приложения Azure: без него вход Microsoft невозможен");
        }
    }
}
