package com.david.gps3dar.fixture;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

public class MusicFixtureProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort) {
        MatrixCursor cursor = new MatrixCursor(new String[]{"state"});
        cursor.addRow(new Object[]{MusicFixtureService.snapshot});
        return cursor;
    }
    @Override public Bundle call(String method,String arg,Bundle extras) {
        if ("stop".equals(method)) new Handler(Looper.getMainLooper()).post(() -> {
            if (MusicFixtureService.current != null) MusicFixtureService.current.stopSelf();
        });
        return Bundle.EMPTY;
    }
    @Override public String getType(Uri uri) { return "application/json"; }
    @Override public Uri insert(Uri uri,ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri,String selection,String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args) { throw new UnsupportedOperationException(); }
}
