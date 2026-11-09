package app.mcrust.core;

import java.io.IOException;
import java.util.List;

/** Каталог модов: Modrinth или CurseForge. Идентификаторы проектов у разных каталогов не пересекаются (у CurseForge префикс «cf:»). */
public interface ModSource {
    /** Короткое имя: modrinth или curseforge. */
    String id();

    /** @param sort relevance, downloads, updated или newest */
    List<Modrinth.Hit> search(String query, String mc, Loader loader, int offset, int limit, String sort) throws IOException;

    /** Версии проекта для игры и загрузчика, новые сверху. */
    List<Modrinth.Ver> versions(String projectId, String mc, Loader loader) throws IOException;

    /** Конкретная версия по номеру (нужна для зависимостей с точной версией). */
    Modrinth.Ver version(String versionId) throws IOException;

    String projectTitle(String projectId) throws IOException;
}
