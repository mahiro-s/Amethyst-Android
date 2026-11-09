package app.mcrust.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Поиск обновлений для установленных модов. */
public final class Updater {
    private Updater() {}

    /** Даёт каталог по установленному моду (или null, если каталог недоступен, например нет ключа CurseForge). */
    public interface Sources {
        ModSource forMod(ModStore.Mod mod);
    }

    public static final class Result {
        /** Что поставить: по одному плану на каждый обновляемый мод. */
        public final List<Resolver.Plan> plans = new ArrayList<>();
        /** Названия обновляемых модов, в том же порядке, что plans. */
        public final List<String> titles = new ArrayList<>();
        public final List<String> from = new ArrayList<>();
        public final List<String> to = new ArrayList<>();
        /** Сообщения: какие моды не удалось проверить и почему. */
        public final List<String> notes = new ArrayList<>();

        public int count() {
            return plans.size();
        }

        /** Все планы в один, без повторов (общая зависимость нескольких модов ставится один раз). */
        public Resolver.Plan merged() {
            Resolver.Plan all = new Resolver.Plan();
            Set<String> seen = new HashSet<>();
            for (Resolver.Plan p : plans) {
                for (Resolver.Item it : p.items) if (seen.add(it.projectId)) all.items.add(it);
                all.warnings.addAll(p.warnings);
            }
            return all;
        }
    }

    /** Проверяет явно установленные включённые моды. Ошибка одного мода не мешает остальным. */
    public static Result check(ModStore store, Sources sources, String mc, Loader loader) {
        Result r = new Result();
        for (ModStore.Mod m : store.list()) {
            if (!m.explicit || !m.enabled) continue;
            ModSource src = sources.forMod(m);
            if (src == null) {
                r.notes.add(m.title + ": каталог " + m.source() + " сейчас недоступен (нет ключа?)");
                continue;
            }
            try {
                Modrinth.Ver best = Resolver.best(src.versions(m.projectId, mc, loader));
                if (best == null || best.id.equals(m.versionId)) continue;
                // установлена более новая версия (например, бета), чем лучший релиз: понижать не надо
                if (m.published != null && !m.published.isEmpty() && best.published != null && best.published.compareTo(m.published) <= 0) continue;
                Set<String> have = store.projectIds();
                have.remove(m.projectId);
                Resolver.Plan plan = Resolver.plan(src, m.projectId, m.title, mc, loader, have);
                r.plans.add(plan);
                r.titles.add(m.title);
                r.from.add(m.version);
                r.to.add(best.number);
            } catch (IOException e) {
                r.notes.add(m.title + ": " + e.getMessage());
            }
        }
        return r;
    }
}
