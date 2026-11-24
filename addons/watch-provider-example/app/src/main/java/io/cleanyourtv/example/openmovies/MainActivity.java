package io.cleanyourtv.example.openmovies;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** The list of the four films, for opening the app from the TV's apps list. */
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (48 * d);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, pad, pad, pad);

        TextView header = text(getString(R.string.app_name), 34, Color.WHITE);
        column.addView(header);
        TextView sub = text(getString(R.string.subtitle), 18, 0xFFB8C4D0);
        sub.setPadding(0, (int) (6 * d), 0, (int) (24 * d));
        column.addView(sub);

        View first = null;
        for (final Film f : Catalog.FILMS) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackgroundResource(R.drawable.row_background);
            row.setFocusable(true);
            row.setClickable(true);
            int p = (int) (16 * d);
            row.setPadding(p, p, p, p);
            row.addView(text(f.title + " (" + f.year + ")", 24, Color.WHITE));
            row.addView(text(getString(R.string.film_meta, f.minutes, f.quality, f.licence), 18, 0xFFD5DDE5));
            row.addView(text(f.credit, 14, 0xFF8796A5));
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(f.uri())).setPackage(getPackageName()));
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = (int) (12 * d);
            column.addView(row, lp);
            if (first == null) first = row;
        }

        TextView footer = text(getString(R.string.footer), 14, 0xFF8796A5);
        footer.setGravity(Gravity.START);
        footer.setPadding(0, (int) (12 * d), 0, 0);
        column.addView(footer);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF0E1620);
        scroll.setFillViewport(true);
        scroll.addView(column);
        setContentView(scroll);
        if (first != null) first.requestFocus();
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }
}
