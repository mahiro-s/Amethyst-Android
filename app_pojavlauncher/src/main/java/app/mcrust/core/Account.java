package app.mcrust.core;

/** Аккаунт Minecraft, прошедший вход Microsoft и проверку владения игрой. */
public final class Account {
    public final String name, uuid, accessToken, xuid;

    public Account(String name, String uuid, String accessToken, String xuid) {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("нет имени игрока");
        if (uuid == null || uuid.isEmpty()) throw new IllegalArgumentException("нет uuid");
        if (accessToken == null || accessToken.isEmpty()) throw new IllegalArgumentException("нет токена доступа");
        this.name = name;
        this.uuid = uuid;
        this.accessToken = accessToken;
        this.xuid = xuid == null ? "" : xuid;
    }

    public String userType() {
        return "msa";
    }
}
