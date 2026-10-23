package com.david.gps3dar.fixture;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public class MusicFixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        startForegroundService(new Intent(this, MusicFixtureService.class));
        finish();
    }
}
