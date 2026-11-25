package io.github.pahpuhbinny.usbcapture.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;

import java.util.function.IntConsumer;

import io.github.pahpuhbinny.usbcapture.log.AppLog;

/** Builds settings rows (headers, chips, sliders, switches, buttons) in code. */
public final class PanelBuilder {
    public interface FloatConsumer {
        void accept(float v);
    }

    public interface BoolConsumer {
        void accept(boolean v);
    }

    public interface Fmt {
        String format(float v);
    }

    private final Context ctx;
    private final LinearLayout root;
    private final float dp;

    public PanelBuilder(Context ctx, LinearLayout root) {
        this.ctx = ctx;
        this.root = root;
        this.dp = ctx.getResources().getDisplayMetrics().density;
    }

    public LinearLayout root() {
        return root;
    }

    private int px(float v) {
        return Math.round(v * dp);
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout.LayoutParams lp(int topMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = px(topMargin);
        return p;
    }

    public TextView header(String title) {
        TextView t = text(title, 13, 0xFF80DEEA, true);
        t.setAllCaps(true);
        t.setLetterSpacing(0.08f);
        root.addView(t, lp(root.getChildCount() == 0 ? 4 : 22));
        return t;
    }

    public TextView note(String s) {
        TextView t = text(s, 13, 0xB3FFFFFF, false);
        root.addView(t, lp(6));
        return t;
    }

    public TextView mono(String s) {
        TextView t = text(s, 11, 0xE6FFFFFF, false);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        root.addView(t, lp(6));
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s, 14, 0xF2FFFFFF, false);
        root.addView(t, lp(12));
        return t;
    }

    public ChipGroup chips(String title, String[] options, int selected, IntConsumer onSelect) {
        return chips(title, options, selected, null, onSelect);
    }

    public ChipGroup chips(String title, String[] options, int selected, boolean[] enabled, IntConsumer onSelect) {
        if (title != null) label(title);
        ChipGroup g = new ChipGroup(ctx);
        g.setSingleSelection(true);
        g.setChipSpacingHorizontal(px(6));
        g.setChipSpacingVertical(px(2));
        for (int i = 0; i < options.length; i++) {
            Chip c = new Chip(ctx);
            c.setText(options[i]);
            c.setCheckable(true);
            c.setCheckedIconVisible(true);
            c.setId(View.generateViewId());
            c.setChecked(i == selected);
            if (enabled != null && i < enabled.length && !enabled[i]) c.setEnabled(false);
            final int idx = i;
            c.setOnClickListener(v -> {
                if (!c.isChecked()) c.setChecked(true);
                try {
                    onSelect.accept(idx);
                } catch (Throwable t) {
                    AppLog.e("Panel", "Option failed", t);
                }
            });
            g.addView(c);
        }
        root.addView(g, lp(4));
        return g;
    }

    public Slider slider(String title, float min, float max, float step, float value, Fmt fmt, FloatConsumer onChange) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView name = text(title, 14, 0xF2FFFFFF, false);
        TextView val = text("", 13, 0xFF80DEEA, true);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(val);
        root.addView(row, lp(12));

        Slider s = new Slider(ctx);
        if (!(max > min)) {
            max = min + 1;
            s.setEnabled(false);
        }
        float st = step;
        if (st > 0) {
            float n = (max - min) / st;
            if (Math.abs(n - Math.round(n)) > 1e-3f) st = 0; // range not a whole number of steps
        }
        float v = Math.max(min, Math.min(max, value));
        if (st > 0) v = min + Math.round((v - min) / st) * st;
        v = Math.max(min, Math.min(max, v));
        try {
            s.setValueFrom(min);
            s.setValueTo(max);
            s.setStepSize(Math.max(0f, st));
            s.setValue(v);
        } catch (Throwable t) {
            AppLog.w("Panel", "Slider " + title + " setup failed: " + t);
            s.setStepSize(0f);
            s.setValue(min);
        }
        s.setLabelBehavior(com.google.android.material.slider.LabelFormatter.LABEL_GONE);
        val.setText(fmt != null ? fmt.format(v) : String.valueOf(v));
        s.addOnChangeListener((sl, value1, fromUser) -> {
            val.setText(fmt != null ? fmt.format(value1) : String.valueOf(value1));
            if (fromUser) {
                try {
                    onChange.accept(value1);
                } catch (Throwable t) {
                    AppLog.e("Panel", "Slider failed", t);
                }
            }
        });
        root.addView(s, lp(0));
        return s;
    }

    public MaterialSwitch toggle(String title, String summary, boolean checked, BoolConsumer onChange) {
        MaterialSwitch sw = new MaterialSwitch(ctx);
        sw.setText(title);
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        sw.setTextColor(0xF2FFFFFF);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener((b, isChecked) -> {
            try {
                onChange.accept(isChecked);
            } catch (Throwable t) {
                AppLog.e("Panel", "Switch failed", t);
            }
        });
        root.addView(sw, lp(8));
        if (summary != null && !summary.isEmpty()) {
            TextView t = text(summary, 12, 0x99FFFFFF, false);
            root.addView(t, lp(0));
        }
        return sw;
    }

    public MaterialButton button(String title, Runnable action) {
        MaterialButton b = new MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(title);
        b.setAllCaps(false);
        b.setOnClickListener(v -> {
            try {
                action.run();
            } catch (Throwable t) {
                AppLog.e("Panel", "Button failed", t);
            }
        });
        root.addView(b, lp(8));
        return b;
    }

    public void buttonRow(String[] titles, Runnable[] actions) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < titles.length; i++) {
            MaterialButton b = new MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            b.setText(titles[i]);
            b.setAllCaps(false);
            final Runnable a = actions[i];
            b.setOnClickListener(v -> {
                try {
                    a.run();
                } catch (Throwable t) {
                    AppLog.e("Panel", "Button failed", t);
                }
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) p.leftMargin = px(8);
            row.addView(b, p);
        }
        root.addView(row, lp(8));
    }

    public void keyValue(String k, String v) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView a = text(k, 13, 0x99FFFFFF, false);
        TextView b = text(v, 13, 0xF2FFFFFF, false);
        b.setGravity(Gravity.END);
        row.addView(a, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));
        root.addView(row, lp(4));
    }

    public void spacer(int dpHeight) {
        View v = new View(ctx);
        root.addView(v, new LinearLayout.LayoutParams(1, px(dpHeight)));
    }
}
