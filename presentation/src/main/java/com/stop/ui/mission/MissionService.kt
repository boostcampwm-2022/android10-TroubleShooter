package com.stop.ui.mission

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.*
import com.stop.*
import com.stop.R
import com.stop.model.map.Location
import com.stop.util.convertTimeMillisToString
import com.stop.util.isMoreThanOreo
import com.stop.util.makeFullTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MissionService : LifecycleService() {

    private val notificationManager by lazy {
        this.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }
    private val fusedLocationClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }
    private val locationRequest by lazy {
        LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_UNIT).build()
    }

    private lateinit var locationCallback: LocationCallback

    private var timer: Job = lifecycleScope.launch(Dispatchers.IO) { }
    private var userLocation = arrayListOf<Location>()
    private var lastTime = ""
    private var isMissionOver = false

    override fun onCreate() {
        super.onCreate()

        setForeground()
    }

    private fun setForeground() {
        createNotification()
        getPersonLocation()
    }

    private fun createNotification() {
        val id = applicationContext.getString(R.string.mission_notification_channel_id)
        val title = applicationContext.getString(R.string.mission_notification_title)

        createChannel(id)

        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, AlarmActivity::class.java).apply {
                putExtra("MISSION_CODE", MISSION_CODE)
                flags =
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, id)
            .setContentTitle(title)
            .setTicker(title)
            .setContentText(NOTIFICATION_CONTENT)
            .setSmallIcon(R.mipmap.ic_bus)
            .setOngoing(true) // 사용자가 지우지 못하도록 막음
            .setContentIntent(pendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createChannel(id: String) {
        if (isMoreThanOreo()) {
            if (notificationManager.getNotificationChannel(id) == null) {
                val name = this.getString(R.string.mission_notification_channel_name)
                NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT).apply {
                    notificationManager.createNotificationChannel(this)
                }
            }
        }
    }

    private fun getPersonLocation() {
        if (ActivityCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        fusedLocationClient.lastLocation
            .addOnSuccessListener { location ->
                if (location != null) {
                    userLocation.add(Location(location.latitude, location.longitude))
                }
            }
            .addOnFailureListener {
                it.printStackTrace()
            }

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    if (location != null) {
                        userLocation.add(Location(location.latitude, location.longitude))
                    }
                }
            }
        }
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        getTimer(intent)
        getStatus(intent)

        return super.onStartCommand(intent, flags, startId)
    }

    private fun getTimer(intent: Intent?) {
        val lastTimeString = intent?.getStringExtra(MISSION_LAST_TIME)
        if (lastTimeString != null) {
            val lastTimeMillis = makeFullTime(lastTimeString).timeInMillis
            val nowTimeMillis = System.currentTimeMillis()
            var diffTimeMillis = if (lastTimeMillis > nowTimeMillis) lastTimeMillis - nowTimeMillis else 0L

            timer.cancel()
            timer = lifecycleScope.launch(Dispatchers.IO) {
                while (diffTimeMillis > -1_000L) {
                    lastTime = convertTimeMillisToString(diffTimeMillis)
                    sendUserInfo()
                    diffTimeMillis -= 1_000L
                    delay(1_000L)
                }
                sendMissionOver()
            }
        }
    }

    private fun sendUserInfo() {
        val statusIntent = Intent().apply {
            action = MISSION_USER_INFO
            putExtra(MISSION_LAST_TIME, lastTime)
            putParcelableArrayListExtra(MISSION_LOCATIONS, userLocation)
        }
        sendBroadcast(statusIntent)
        sendMissionStatus()
    }

    private fun sendMissionStatus() {
        val statusIntent = Intent().apply {
            action = MISSION_STATUS
            putExtra(MISSION_STATUS, true)
        }
        sendBroadcast(statusIntent)
    }

    private fun sendMissionOver() {
        val statusIntent = Intent().apply {
            action = MISSION_TIME_OVER
            putExtra(MISSION_TIME_OVER, true)
        }
        sendBroadcast(statusIntent)
    }

    private fun getStatus(intent: Intent?) {
        if (intent?.getBooleanExtra(MISSION_OVER, false) == true) {
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        isMissionOver = true
    }

    companion object {
        const val NOTIFICATION_ID = 82
        private const val NOTIFICATION_CONTENT = "사용자의 위치를 트래킹 중입니다"
        private const val INTERVAL_UNIT = 1_000L
        const val MISSION_CODE = 88

        const val MISSION_LAST_TIME = "last_time"
        const val MISSION_LOCATIONS = "mission_locations"
        const val MISSION_USER_INFO = "mission_user_info"
        const val MISSION_OVER = "mission_over"
        const val MISSION_TIME_OVER = "mission_time_over"
        const val MISSION_STATUS = "mission_status"
    }

}