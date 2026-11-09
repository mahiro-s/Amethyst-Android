package app.mcrust.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Загрузчик модов профиля. */
public enum Loader {
    VANILLA("vanilla", "без модов"),
    FABRIC("fabric", "Fabric"),
    QUILT("quilt", "Quilt"),
    NEOFORGE("neoforge", "NeoForge"),
    FORGE("forge", "Forge");

    public final String id;
    public final String title;

    Loader(String id, String title) {
        this.id = id;
        this.title = title;
    }

    /** Под какие загрузчики искать моды на Modrinth. Quilt запускает и моды для Fabric. */
    public List<String> modrinthLoaders() {
        switch (this) {
            case FABRIC: return Collections.singletonList("fabric");
            case QUILT: return Arrays.asList("quilt", "fabric");
            case NEOFORGE: return Collections.singletonList("neoforge");
            case FORGE: return Collections.singletonList("forge");
            default: return Collections.emptyList();
        }
    }

    /** Номер загрузчика в API CurseForge (modLoaderType). Для Quilt берутся моды Fabric: отдельных у него мало. */
    public int curseForgeType() {
        switch (this) {
            case FORGE: return 1;
            case FABRIC: return 4;
            case QUILT: return 4;
            case NEOFORGE: return 6;
            default: return 0;
        }
    }

    public boolean supportsMods() {
        return this != VANILLA;
    }

    public static Loader byId(String id) {
        for (Loader l : values()) if (l.id.equals(id)) return l;
        return VANILLA;
    }
}
