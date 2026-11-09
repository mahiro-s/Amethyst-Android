package app.mcrust;

import android.view.View;

/** Один экран приложения. Стек экранов ведёт Host. */
abstract class Screen {
    final Host a;
    private View view;

    Screen(Host a) {
        this.a = a;
    }

    abstract String title();

    abstract View build();

    /** Вызывается при каждом показе, в том числе при возврате назад. */
    void onShow() {}

    final View view() {
        if (view == null) view = build();
        return view;
    }
}
