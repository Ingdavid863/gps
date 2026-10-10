package com.david.gps3dar.car

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Looper
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.david.gps3dar.RealisticMapActivity
import com.google.android.gms.location.*

/** Keeps the same live journey updating when the phone's screen is off. */
class CarLocationService : Service() {
    private var speech: TextToSpeech?=null
    private var speechReady=false
    private var spoken=""
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private val focus by lazy { AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).setOnAudioFocusChangeListener {}.build() }
    private val observer: (CarSnapshot)->Unit = { state ->
        if(CarNavigation.phoneVisible || !CarNavigation.voiceEnabled || state.route==null) speech?.stop()
        else if(speechReady && !state.pending && state.onRoute) {
            val phase=when { state.turnMeters<35 -> "ahora";state.turnMeters<200 -> "próxima";else -> "lejos" }
            val token="${state.revision}:${state.instruction}:$phase"
            if(token!=spoken) {
                spoken=token
                val cue=if(phase=="ahora")state.instruction else "En ${state.turnMeters.toInt()} metros, ${state.instruction}"
                if(audio.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                    speech?.speak(cue,TextToSpeech.QUEUE_FLUSH,null,token)
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        speech=TextToSpeech(this) { status ->
            speechReady=status==TextToSpeech.SUCCESS
            if(speechReady)speech?.language=java.util.Locale("es","MX")
        }
        speech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?)=Unit
            override fun onDone(id: String?) { audio.abandonAudioFocusRequest(focus) }
            @Deprecated("Framework callback") override fun onError(id: String?) { audio.abandonAudioFocusRequest(focus) }
        })
        CarNavigation.observe(observer)
    }
    private val callback=object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) { result.locations.forEach { CarNavigation.fix(it) } }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        CarNavigation.initialize(this)
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
            stopSelf();return START_NOT_STICKY
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("car-navigation","Navegación en el auto",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,0,Intent(this,RealisticMapActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        startForeground(230,NotificationCompat.Builder(this,"car-navigation")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle("GPS3D en Android Auto")
            .setContentText("Ubicación y ruta sincronizadas con el teléfono").setContentIntent(open).setOngoing(true).build())
        LocationServices.getFusedLocationProviderClient(this).requestLocationUpdates(
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY,300).setMinUpdateIntervalMillis(100)
                .setMaxUpdateDelayMillis(0).build(),callback,Looper.getMainLooper())
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        CarNavigation.remove(observer);speech?.stop();speech?.shutdown();audio.abandonAudioFocusRequest(focus)
        LocationServices.getFusedLocationProviderClient(this).removeLocationUpdates(callback)
        super.onDestroy()
    }
}
