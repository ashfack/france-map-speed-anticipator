package com.example.roadalert.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import com.example.roadalert.spatial.OsmSpatialEngine
import com.google.android.gms.location.*
import java.util.Locale

class RoadAlertService : Service(), TextToSpeech.OnInitListener {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var tts: TextToSpeech
    private lateinit var spatialEngine: OsmSpatialEngine

    private var isTtsReady = false
    private val announcementPolicy = SpeedAnnouncementPolicy()

    companion object {
        private const val CHANNEL_ID = "road_alert_channel"
        private const val NOTIFICATION_ID = 1001
        private const val MIN_SPEED_FOR_BEARING_METERS_PER_SECOND = 2.5f
        private const val MAX_BEARING_ACCURACY_DEGREES = 45f
    }

    override fun onCreate() {
        super.onCreate()

        spatialEngine = OsmSpatialEngine(this)
        tts = TextToSpeech(this, this)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Service d'alerte routière actif"))

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
            .setMinUpdateIntervalMillis(1500L)
            .setMinUpdateDistanceMeters(5f)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation ?: return
                onLocationUpdated(location)
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun onLocationUpdated(location: Location) {
        val bearing = location.takeIf {
            it.hasBearing() &&
                it.speed >= MIN_SPEED_FOR_BEARING_METERS_PER_SECOND &&
                (!it.hasBearingAccuracy() || it.bearingAccuracyDegrees <= MAX_BEARING_ACCURACY_DEGREES)
        }?.bearing

        val prediction = spatialEngine.getSpeedLimitPrediction(
            location.latitude,
            location.longitude,
            bearing
        )

        val announcement = announcementPolicy.onPrediction(prediction, System.currentTimeMillis())
            ?: return
        speak(announcement.speech)
        notifyLimit(announcement.notification)
    }

    private fun notifyLimit(content: String) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(content))
    }

    private fun speak(text: String) {
        if (isTtsReady) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ROAD_ALERT_TTS")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.FRANCE)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                isTtsReady = true
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Alerte Routière Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Détections panneaux & vitesse")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}