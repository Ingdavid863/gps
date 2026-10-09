package com.david.gps3dar.mapsfixture;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.widget.*;
import android.graphics.Color;

/** Reproduces observed Spanish route controls and opens the REAL Android Sharesheet. */
public class MapsFixtureActivity extends Activity {
    private LinearLayout body;
    private String destination;
    private String url;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        incoming();
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        incoming();
    }
    private void incoming() {
        destination = getIntent().getStringExtra("destination");
        url = getIntent().getStringExtra("url");
        if (getIntent().getBooleanExtra("navigation", false)) navigation(); else preview();
    }
    private void screen() {
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(36,80,36,36);
        body.setBackgroundColor(Color.WHITE); setContentView(body);
    }
    private void button(String label, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setContentDescription(label); b.setOnClickListener(v -> action.run()); body.addView(b);
    }
    private void preview() {
        screen(); EditText origin = new EditText(this); origin.setText("Tu ubicación"); body.addView(origin);
        EditText dest = new EditText(this); dest.setText(destination); body.addView(dest); origin.clearFocus(); dest.clearFocus();
        TextView mode = new TextView(this); mode.setText("Coche · 24 min · 5.9 km"); body.addView(mode);
        button("Iniciar", this::navigation); button("Más opciones", this::menu);
    }
    private void navigation() { screen(); button("Salir de la navegación", this::preview); }
    private void menu() {
        button("Compartir tu ubicación", () -> { throw new AssertionError("Must never share current live location"); });
        button("Compartir indicaciones", () -> startActivity(Intent.createChooser(
            new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), "Compartir indicaciones")));
    }
}
