package com.david.gps3dar.fixture;

import android.app.*;
import android.content.Intent;
import android.media.*;
import android.media.session.*;
import android.os.*;
import android.os.Process;
import android.view.KeyEvent;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Test-only independent music application with a real MediaPlayer and platform media session. */
public class MusicFixtureService extends Service {
    static volatile String snapshot = "{}";
    static MusicFixtureService current;
    private MediaSession session;
    private MediaPlayer player;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private int index, nextCount, previousCount, pauseCount, playCount;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() { publish(); main.postDelayed(this,100); }
    };
    @Override public void onCreate() {
        super.onCreate(); current=this;
        NotificationManager notifications=getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("fixture-music","GPS3D test audio",NotificationManager.IMPORTANCE_LOW));
        startForeground(229,new Notification.Builder(this,"fixture-music").setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("GPS3D external audio QA").build());
        audio=getSystemService(AudioManager.class);
        AudioAttributes attributes=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(change -> { if (current==this && (change==AudioManager.AUDIOFOCUS_LOSS || change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)) pause(); }).build();
        player=new MediaPlayer(); player.setAudioAttributes(attributes);
        session=new MediaSession(this,"external-gps3d-audio-test");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { play(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { nextCount++; change(1); }
            @Override public void onSkipToPrevious() { previousCount++; change(-1); }
        },main);
        session.setActive(true);
        load(false); main.post(ticker);
    }
    private File wave(int number) throws Exception {
        File file=new File(getFilesDir(),"track-"+number+".wav");
        if (!file.exists()) {
            int samples=8000*20;
            ByteBuffer wav=ByteBuffer.allocate(44+samples*2).order(ByteOrder.LITTLE_ENDIAN);
            wav.put("RIFF".getBytes()).putInt(36+samples*2).put("WAVEfmt ".getBytes()).putInt(16);
            wav.putShort((short)1).putShort((short)1).putInt(8000).putInt(16000).putShort((short)2).putShort((short)16);
            wav.put("data".getBytes()).putInt(samples*2);
            for(int i=0;i<samples;i++) wav.putShort((short)(1000*Math.sin(2*Math.PI*(220+number*110)*i/8000)));
            try(FileOutputStream out=new FileOutputStream(file)) { out.write(wav.array()); }
        }
        return file;
    }
    private void load(boolean resume) {
        try {
            player.reset();player.setDataSource(wave(index).getPath());player.setLooping(true);player.prepare();
            if(resume) player.start();publish();
        } catch(Exception error) { throw new IllegalStateException(error); }
    }
    private void change(int delta) { boolean playing=player.isPlaying();index=(index+delta+3)%3;load(playing); }
    private void play() {
        if(audio.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { playCount++;player.start();publish(); }
    }
    private void pause() { if(player.isPlaying()) { pauseCount++;player.pause(); } publish(); }
    private void publish() {
        try {
            boolean playing=player!=null && player.isPlaying();
            session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,"External track "+(index+1)).build());
            session.setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE |
                PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(playing?PlaybackState.STATE_PLAYING:PlaybackState.STATE_PAUSED,player.getCurrentPosition(),playing?1f:0f).build());
            snapshot=new JSONObject().put("uid",Process.myUid()).put("playing",playing).put("index",index)
                .put("position",player.getCurrentPosition()).put("next",nextCount).put("previous",previousCount)
                .put("pause",pauseCount).put("play",playCount).toString();
        } catch(Exception error) { throw new IllegalStateException(error); }
    }
    @Override public int onStartCommand(Intent intent,int flags,int id) { play();return START_NOT_STICKY; }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        main.removeCallbacksAndMessages(null);current=null;
        session.setActive(false);session.release();player.release();audio.abandonAudioFocusRequest(focus);
        snapshot="{}";super.onDestroy();
    }
}
