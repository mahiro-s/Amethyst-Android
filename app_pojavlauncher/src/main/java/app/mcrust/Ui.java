package app.mcrust;

import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Мелкие помощники для интерфейса, собираемого кодом (без XML-разметки). */
final class Ui {
    private Ui() {}

    static final int BG = Color.parseColor("#121417");
    static final int SURFACE = Color.parseColor("#1c2026");
    static final int SELECTED = Color.parseColor("#26443a");
    static final int ACCENT = Color.parseColor("#4cc38a");
    static final int TEXT = Color.parseColor("#e8eaed");
    static final int MUTED = Color.parseColor("#9aa0a6");
    static final int ERROR = Color.parseColor("#f28b82");

    static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    static TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    static Button button(Context c, String label, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    static LinearLayout vertical(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout horizontal(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    /** Список строк «значок + заголовок + подпись»; выделенная строка подсвечивается. */
    abstract static class Rows<T> extends BaseAdapter {
        final Host ctx;
        final List<T> items = new ArrayList<>();

        Rows(Host ctx) {
            this.ctx = ctx;
        }

        abstract void bind(T item, TextView title, TextView sub);

        boolean selected(T item) {
            return false;
        }

        /** Есть ли у строк значки (тогда место под них резервируется). */
        boolean hasIcons() {
            return false;
        }

        String icon(T item) {
            return null;
        }

        void set(List<T> list) {
            items.clear();
            items.addAll(list);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int i) {
            return items.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View old, ViewGroup parent) {
            LinearLayout row;
            ImageView img;
            TextView title;
            TextView sub;
            if (old instanceof LinearLayout) {
                row = (LinearLayout) old;
                img = (ImageView) row.getChildAt(0);
                LinearLayout col = (LinearLayout) row.getChildAt(1);
                title = (TextView) col.getChildAt(0);
                sub = (TextView) col.getChildAt(1);
            } else {
                row = horizontal(ctx);
                int p = dp(ctx, 12);
                row.setPadding(p, dp(ctx, 8), p, dp(ctx, 8));
                img = new ImageView(ctx);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                row.addView(img, lp(dp(ctx, 48), dp(ctx, 48), 0f));
                LinearLayout col = vertical(ctx);
                col.setPadding(dp(ctx, 12), 0, 0, 0);
                title = text(ctx, "", 16, TEXT);
                title.setSingleLine(true);
                title.setEllipsize(TextUtils.TruncateAt.END);
                sub = text(ctx, "", 13, MUTED);
                sub.setMaxLines(3);
                sub.setEllipsize(TextUtils.TruncateAt.END);
                col.addView(title);
                col.addView(sub);
                row.addView(col, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            }
            T item = items.get(i);
            bind(item, title, sub);
            sub.setVisibility(sub.getText().length() == 0 ? View.GONE : View.VISIBLE);
            img.setVisibility(hasIcons() ? View.VISIBLE : View.GONE);
            if (hasIcons()) Icons.load(ctx, icon(item), img);
            row.setBackgroundColor(selected(item) ? SELECTED : Color.TRANSPARENT);
            return row;
        }
    }
}
