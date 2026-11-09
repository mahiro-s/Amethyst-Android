package app.mcrust;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.view.inputmethod.EditorInfo;
import android.view.KeyEvent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.mcrust.core.Loader;
import app.mcrust.core.ModSource;
import app.mcrust.core.ModStore;
import app.mcrust.core.Modrinth;
import app.mcrust.core.Profile;
import app.mcrust.core.Resolver;

/** Браузер модов Modrinth под версию и загрузчик профиля + список установленных. */
final class ModsScreen extends Screen {
    private static final int PAGE = 20;
    private static final String[] SORT_IDS = {"relevance", "downloads", "updated", "newest"};
    private static final String[] SORT_TITLES = {"Подходящие", "Популярные", "Недавно обновлённые", "Новые"};

    private final File instanceDir;
    private Profile profile;
    private ModStore store;

    private FrameLayout body;
    private LinearLayout catalog;
    private LinearLayout installed;
    private EditText query;
    private Spinner sort;
    private Spinner sourceSpin;
    private Button updateAll;
    private TextView status;
    private Button more;
    private Ui.Rows<Modrinth.Hit> hits;
    private Ui.Rows<ModStore.Mod> mods;
    private TextView problems;

    private int searchToken;
    private int offset;
    private boolean busy;

    ModsScreen(Host a, Profile profile, File instanceDir) {
        super(a);
        this.profile = profile;
        this.instanceDir = instanceDir;
    }

    @Override
    String title() {
        return "Моды";
    }

    @Override
    View build() {
        LinearLayout page = Ui.vertical(a);
        int p = Ui.dp(a, 8);
        page.setPadding(p, p, p, p);
        if (profile == null) {
            page.addView(Ui.text(a, "Профиль не найден", 16, Ui.ERROR));
            return page;
        }
        if (!profile.loader.supportsMods()) {
            TextView t = Ui.text(a, "У профиля «" + profile.name + "» нет загрузчика, поэтому моды не работают. Создай профиль с Fabric, Quilt или NeoForge.", 16, Ui.MUTED);
            t.setPadding(p, p, p, p);
            page.addView(t);
            return page;
        }
        try {
            store = new ModStore(instanceDir);
        } catch (IOException e) {
            page.addView(Ui.text(a, "Не открыть папку модов: " + e.getMessage(), 16, Ui.ERROR));
            return page;
        }

        TextView target = Ui.text(a, "Подбираю моды под " + profile.describe(), 13, Ui.MUTED);
        page.addView(target);

        LinearLayout tabs = Ui.horizontal(a);
        tabs.addView(Ui.button(a, "Каталог", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTab(true);
            }
        }), Ui.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tabs.addView(Ui.button(a, "Установленные", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTab(false);
            }
        }), Ui.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        page.addView(tabs);

        body = new FrameLayout(a);
        page.addView(body, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        buildCatalog();
        buildInstalled();
        showTab(true);
        search(true);
        return page;
    }

    private void showTab(boolean cat) {
        if (body == null) return;
        body.removeAllViews();
        body.addView(cat ? catalog : installed);
        if (!cat) refreshInstalled();
    }

    // ---------- каталог ----------

    private void buildCatalog() {
        catalog = Ui.vertical(a);
        sourceSpin = new Spinner(a);
        ArrayAdapter<String> sa = new ArrayAdapter<>(a, android.R.layout.simple_spinner_item, new String[]{"Каталог: Modrinth", "Каталог: CurseForge"});
        sa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sourceSpin.setAdapter(sa);
        sourceSpin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private boolean first = true;

            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (first) {
                    first = false;
                    return;
                }
                if (position == 1 && a.curseKey().isEmpty()) {
                    askKey();
                } else {
                    search(true);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        catalog.addView(sourceSpin);
        LinearLayout row = Ui.horizontal(a);
        query = new EditText(a);
        query.setHint("Поиск модов");
        query.setSingleLine(true);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setTextColor(Ui.TEXT);
        query.setHintTextColor(Ui.MUTED);
        query.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent e) {
                search(true);
                return true;
            }
        });
        row.addView(query, Ui.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(Ui.button(a, "Найти", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                search(true);
            }
        }));
        catalog.addView(row);

        sort = new Spinner(a);
        ArrayAdapter<String> ad = new ArrayAdapter<>(a, android.R.layout.simple_spinner_item, SORT_TITLES);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sort.setAdapter(ad);
        sort.setSelection(1); // сначала популярные
        sort.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private boolean first = true;

            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (first) {
                    first = false;
                    return;
                }
                search(true);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        catalog.addView(sort);

        status = Ui.text(a, "", 13, Ui.MUTED);
        catalog.addView(status);

        ListView list = new ListView(a);
        hits = new Ui.Rows<Modrinth.Hit>(a) {
            @Override
            boolean hasIcons() {
                return true;
            }

            @Override
            String icon(Modrinth.Hit h) {
                return h.iconUrl;
            }

            @Override
            void bind(Modrinth.Hit h, TextView title, TextView sub) {
                String mark = store.has(h.projectId) ? "  ✓" : "";
                title.setText(h.title + mark);
                sub.setText(h.author + " · " + count(h.downloads) + " загрузок\n" + h.description);
            }
        };
        more = Ui.button(a, "Показать ещё", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                search(false);
            }
        });
        more.setVisibility(View.GONE);
        list.addFooterView(more);
        list.setAdapter(hits);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < hits.items.size()) offerInstall(hits.items.get(position));
            }
        });
        catalog.addView(list, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    static String count(long n) {
        if (n >= 1000000) return String.format(java.util.Locale.ROOT, "%.1f млн", n / 1e6);
        if (n >= 1000) return String.format(java.util.Locale.ROOT, "%.1f тыс.", n / 1e3);
        return String.valueOf(n);
    }

    private void search(final boolean fresh) {
        final int token = fresh ? ++searchToken : searchToken;
        if (fresh) offset = 0;
        final String q = query.getText().toString().trim();
        final String idx = SORT_IDS[Math.max(0, sort.getSelectedItemPosition())];
        final int off = offset;
        final ModSource src = currentSource();
        if (src == null) {
            hits.set(new ArrayList<Modrinth.Hit>());
            more.setVisibility(View.GONE);
            status.setTextColor(Ui.ERROR);
            status.setText("Для CurseForge нужен бесплатный ключ API. Нажми «Каталог» и выбери CurseForge ещё раз.");
            return;
        }
        status.setTextColor(Ui.MUTED);
        status.setText("Ищу…");
        more.setEnabled(false);
        Async.run(a, new Async.Work<List<Modrinth.Hit>>() {
            @Override
            public List<Modrinth.Hit> run() throws Exception {
                return src.search(q, profile.mcVersion, profile.loader, off, PAGE, idx);
            }
        }, new Async.Done<List<Modrinth.Hit>>() {
            @Override
            public void ok(List<Modrinth.Hit> found) {
                if (token != searchToken) return;
                List<Modrinth.Hit> all = new ArrayList<>();
                if (!fresh) all.addAll(hits.items);
                all.addAll(found);
                hits.set(all);
                offset = all.size();
                more.setEnabled(true);
                more.setVisibility(found.size() >= PAGE ? View.VISIBLE : View.GONE);
                status.setText(all.isEmpty() ? "Под " + profile.describe() + " ничего не нашлось" : "Найдено: " + all.size() + (found.size() >= PAGE ? "+" : ""));
            }
        }, new Async.Fail() {
            @Override
            public void err(Exception e) {
                if (token != searchToken) return;
                more.setEnabled(true);
                status.setTextColor(Ui.ERROR);
                status.setText("Не получилось: " + e.getMessage());
            }
        });
    }

    private void offerInstall(final Modrinth.Hit h) {
        if (busy) {
            a.toast("Подожди, идёт установка");
            return;
        }
        final ModSource src = a.sourceFor(h.projectId);
        if (src == null) {
            a.toast("Для CurseForge нужен ключ API");
            return;
        }
        busy = true;
        status.setTextColor(Ui.MUTED);
        status.setText("Проверяю совместимость «" + h.title + "»…");
        final boolean update = store.has(h.projectId);
        final Set<String> have = store.projectIds();
        if (update) have.remove(h.projectId); // при обновлении корень пересобираем
        Async.run(a, new Async.Work<Resolver.Plan>() {
            @Override
            public Resolver.Plan run() throws Exception {
                return Resolver.plan(src, h.projectId, h.title, profile.mcVersion, profile.loader, have);
            }
        }, new Async.Done<Resolver.Plan>() {
            @Override
            public void ok(Resolver.Plan plan) {
                busy = false;
                status.setText("");
                confirm(h, plan, update);
            }
        }, new Async.Fail() {
            @Override
            public void err(Exception e) {
                busy = false;
                status.setTextColor(Ui.ERROR);
                status.setText("");
                new AlertDialog.Builder(a).setTitle(h.title).setMessage(e.getMessage()).setPositiveButton("Понятно", null).show();
            }
        });
    }

    private void confirm(final Modrinth.Hit h, final Resolver.Plan plan, boolean update) {
        StringBuilder msg = new StringBuilder();
        for (Resolver.Item it : plan.items) {
            msg.append(it.explicit ? "• " : "• (зависимость) ").append(it.title).append(' ').append(it.ver.number);
            if (!"release".equals(it.ver.type)) msg.append(" [").append(it.ver.type).append(']');
            msg.append('\n');
        }
        for (String w : plan.warnings) msg.append("\n⚠ ").append(w);
        new AlertDialog.Builder(a)
                .setTitle((update ? "Обновить " : "Установить ") + h.title + "?")
                .setMessage(msg.toString().trim())
                .setNegativeButton("Отмена", null)
                .setPositiveButton(update ? "Обновить" : "Установить", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        runInstall(plan);
                    }
                })
                .show();
    }

    private void runInstall(final Resolver.Plan plan) {
        busy = true;
        final TextView msg = Ui.text(a, "Начинаю…", 15, Ui.TEXT);
        int pad = Ui.dp(a, 20);
        msg.setPadding(pad, pad, pad, pad);
        final AlertDialog dlg = new AlertDialog.Builder(a).setTitle("Установка").setView(msg).setCancelable(false).create();
        dlg.show();
        Async.run(a, new Async.Work<Boolean>() {
            @Override
            public Boolean run() throws Exception {
                synchronized (store) {
                    store.install(a.net, plan, new ModStore.Progress() {
                        @Override
                        public void step(final String text) {
                            Async.ui(a, new Runnable() {
                                @Override
                                public void run() {
                                    msg.setText(text);
                                }
                            });
                        }
                    });
                }
                return Boolean.TRUE;
            }
        }, new Async.Done<Boolean>() {
            @Override
            public void ok(Boolean v) {
                busy = false;
                dlg.dismiss();
                a.toast("Установлено: " + plan.items.size());
                hits.notifyDataSetChanged();
                refreshInstalled();
            }
        }, new Async.Fail() {
            @Override
            public void err(Exception e) {
                busy = false;
                dlg.dismiss();
                hits.notifyDataSetChanged();
                new AlertDialog.Builder(a).setTitle("Не установилось").setMessage(e.getMessage()).setPositiveButton("Понятно", null).show();
            }
        });
    }

    // ---------- установленные ----------

    private void buildInstalled() {
        installed = Ui.vertical(a);
        updateAll = Ui.button(a, "Проверить обновления", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkUpdates();
            }
        });
        installed.addView(updateAll);
        problems = Ui.text(a, "", 13, Ui.ERROR);
        installed.addView(problems);
        ListView list = new ListView(a);
        mods = new Ui.Rows<ModStore.Mod>(a) {
            @Override
            void bind(ModStore.Mod m, TextView title, TextView sub) {
                title.setText(m.title + (m.enabled ? "" : "  (выключен)"));
                sub.setText(m.version + (m.explicit ? "" : " · зависимость") + " · " + ("curseforge".equals(m.source()) ? "CurseForge" : "Modrinth") + " · " + m.filename);
            }
        };
        list.setAdapter(mods);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                modMenu(mods.items.get(position));
            }
        });
        installed.addView(list, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private void refreshInstalled() {
        mods.set(store.list());
        List<String> bad = store.brokenRequirements();
        StringBuilder b = new StringBuilder();
        for (String s : bad) b.append("⚠ ").append(s).append('\n');
        problems.setText(b.toString().trim());
        problems.setVisibility(bad.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void modMenu(final ModStore.Mod m) {
        String[] items = {m.enabled ? "Выключить" : "Включить", "Удалить"};
        new AlertDialog.Builder(a)
                .setTitle(m.title)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        try {
                            if (which == 0) {
                                store.setEnabled(m.projectId, !m.enabled);
                            } else {
                                List<String> gone = store.remove(m.projectId);
                                a.toast("Удалено: " + join(gone));
                            }
                        } catch (IOException e) {
                            a.toast(e.getMessage());
                        }
                        refreshInstalled();
                        hits.notifyDataSetChanged();
                    }
                })
                .show();
    }

    private static String join(List<String> l) {
        StringBuilder b = new StringBuilder();
        for (String s : l) {
            if (b.length() > 0) b.append(", ");
            b.append(s);
        }
        return b.toString();
    }

    // ---------- источники, ключ, обновления ----------

    private ModSource currentSource() {
        return sourceSpin != null && sourceSpin.getSelectedItemPosition() == 1 ? a.curseForge() : a.modrinth;
    }

    /** Окно ввода ключа CurseForge. Без ключа возвращаем выбор на Modrinth. */
    private void askKey() {
        LinearLayout box = Ui.vertical(a);
        int pad = Ui.dp(a, 16);
        box.setPadding(pad, pad / 2, pad, 0);
        TextView help = Ui.text(a, "Ключ бесплатный: зарегистрируйся на console.curseforge.com, создай ключ API и вставь сюда. Он хранится только на этом телефоне и отправляется только на api.curseforge.com.", 13, Ui.MUTED);
        final EditText key = new EditText(a);
        key.setHint("Ключ API CurseForge");
        key.setSingleLine(true);
        key.setText(a.curseKey());
        box.addView(help);
        box.addView(key);
        new AlertDialog.Builder(a)
                .setTitle("CurseForge")
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("Отмена", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        sourceSpin.setSelection(0);
                    }
                })
                .setPositiveButton("Сохранить", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String k = key.getText().toString().trim();
                        if (k.isEmpty()) {
                            sourceSpin.setSelection(0);
                            return;
                        }
                        a.setCurseKey(k);
                        search(true);
                    }
                })
                .show();
    }

    private void checkUpdates() {
        if (busy) {
            a.toast("Подожди, идёт другая операция");
            return;
        }
        busy = true;
        updateAll.setEnabled(false);
        updateAll.setText("Проверяю…");
        Async.run(a, new Async.Work<app.mcrust.core.Updater.Result>() {
            @Override
            public app.mcrust.core.Updater.Result run() throws Exception {
                synchronized (store) {
                    return app.mcrust.core.Updater.check(store, a.updaterSources(), profile.mcVersion, profile.loader);
                }
            }
        }, new Async.Done<app.mcrust.core.Updater.Result>() {
            @Override
            public void ok(app.mcrust.core.Updater.Result r) {
                busy = false;
                updateAll.setEnabled(true);
                updateAll.setText("Проверить обновления");
                showUpdates(r);
            }
        }, new Async.Fail() {
            @Override
            public void err(Exception e) {
                busy = false;
                updateAll.setEnabled(true);
                updateAll.setText("Проверить обновления");
                a.toast("Не получилось проверить: " + e.getMessage());
            }
        });
    }

    private void showUpdates(final app.mcrust.core.Updater.Result r) {
        StringBuilder msg = new StringBuilder();
        if (r.count() == 0) msg.append("Все моды последней версии.");
        for (int i = 0; i < r.count(); i++) {
            msg.append("• ").append(r.titles.get(i)).append(": ").append(r.from.get(i)).append(" → ").append(r.to.get(i)).append('\n');
        }
        for (String w : r.merged().warnings) msg.append("\n⚠ ").append(w);
        for (String n : r.notes) msg.append("\n").append(n);
        AlertDialog.Builder b = new AlertDialog.Builder(a).setTitle(r.count() == 0 ? "Обновлений нет" : "Обновить моды: " + r.count()).setMessage(msg.toString().trim());
        if (r.count() > 0) {
            b.setNegativeButton("Отмена", null).setPositiveButton("Обновить всё", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int which) {
                    runInstall(r.merged());
                }
            });
        } else {
            b.setPositiveButton("Понятно", null);
        }
        b.show();
    }
}
