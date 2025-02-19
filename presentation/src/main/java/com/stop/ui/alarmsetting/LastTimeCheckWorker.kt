package com.stop.ui.alarmsetting

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.stop.R
import com.stop.util.AlarmFunctions
import com.stop.domain.usecase.nearplace.GetNearPlacesUseCase
import com.stop.util.isMoreThanOreo
import com.stop.util.makeFullTime
import com.stop.ui.alarmsetting.AlarmSettingFragment.Companion.ALARM_TIME
import com.stop.ui.alarmsetting.AlarmSettingFragment.Companion.LAST_TIME
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay

@HiltWorker
class LastTimeCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParameters: WorkerParameters,
    private val getNearPlacesUseCase: GetNearPlacesUseCase,
    private val alarmFunctions: AlarmFunctions
) : CoroutineWorker(context, workerParameters) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private var lastTime = ""
    private val alarmTime by lazy {
        inputData.getInt(ALARM_TIME, 0)
    }

    override suspend fun doWork(): Result {
        initData()
        setForeground(createForegroundInfo())
        checkLastTransportTime()

        return Result.success()
    }

    private fun initData(){
        lastTime = inputData.getString(LAST_TIME) ?: ""
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val id = applicationContext.getString(R.string.notification_channel_id)
        val title = applicationContext.getString(R.string.notification_title)
        val cancel = applicationContext.getString(R.string.alarm_cancel_text)

        val intent = WorkManager.getInstance(applicationContext)
            .createCancelPendingIntent(getId())

        createChannel(id)

        val notification = NotificationCompat.Builder(applicationContext, id)
            .setContentTitle(title)
            .setTicker(title)
            .setContentText(NOTIFICATION_CONTENT)
            .setSmallIcon(R.mipmap.ic_bus)
            .setOngoing(true) // 사용자가 지우지 못하도록 막음
            .addAction(android.R.drawable.ic_delete, cancel, intent)
            .build()

        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private fun createChannel(id: String) {
        if (isMoreThanOreo()) {
            if (notificationManager.getNotificationChannel(id) == null) {
                val name = applicationContext.getString(R.string.notification_channel_name)
                NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT).apply {
                    notificationManager.createNotificationChannel(this)
                }
            }
        }
    }

    private suspend fun checkLastTransportTime() {
        while (isStopped.not()) {
           //TODO 막차시간 가져오는 API로 변경 해야합니다.
            getNearPlacesUseCase(
                "아남타워",
                126.969652,
                37.553836
            )

            val resultLastTime = "21:04:00"

            if(lastTime != resultLastTime && resultLastTime != null){
                lastTime = resultLastTime
                alarmFunctions.cancelAlarm()
                alarmFunctions.callAlarm(resultLastTime, alarmTime)
            }

            val delayTime = getDelayTime()
            if (delayTime == 0L) {
                this.onStopped()
            } else {
                delay(delayTime)
            }
        }
    }

    private fun getDelayTime(): Long {
        val fullLastTimeMillis = makeFullTime(lastTime).timeInMillis
        val currentTimeMillis = System.currentTimeMillis()

        return when (val diffTimeMillis =
            if (fullLastTimeMillis > currentTimeMillis) fullLastTimeMillis - currentTimeMillis
            else 0L
        ) {
            in THIRTY_MINUTES until Long.MAX_VALUE -> diffTimeMillis - THIRTY_MINUTES
            in TWENTY_MINUTES until THIRTY_MINUTES -> FIVE_MINUTES
            in TEN_MINUTES until TWENTY_MINUTES -> TWO_MINUTES
            in 0 until TEN_MINUTES -> ONE_MINUTES
            else -> 0
        }
    }

    companion object {
        const val NOTIFICATION_ID = 12
        private const val NOTIFICATION_CONTENT = "막차 시간이 변경되는지 계속 추적중입니다."
        private const val THIRTY_MINUTES = 1_800_000L
        private const val TWENTY_MINUTES = 1_200_000L
        private const val TEN_MINUTES = 600_000L
        private const val FIVE_MINUTES = 300_000L
        private const val TWO_MINUTES = 120_000L
        private const val ONE_MINUTES = 60_000L
    }

}