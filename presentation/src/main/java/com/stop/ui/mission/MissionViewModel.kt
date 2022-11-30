package com.stop.ui.mission

import android.util.Log
import androidx.lifecycle.*
import com.stop.data.BuildConfig
import com.stop.domain.model.nowlocation.BusInfoUseCaseItem
import com.stop.domain.model.nowlocation.NowStationLocationUseCaseItem
import com.stop.domain.usecase.nowlocation.GetBusNowLocationUseCase
import com.stop.domain.usecase.nowlocation.GetNowStationLocationUseCase
import com.stop.domain.usecase.nowlocation.GetSubwayRouteUseCase
import com.stop.domain.usecase.nowlocation.GetSubwayTrainNowStationUseCase
import com.stop.model.Location
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import javax.inject.Inject
import kotlin.random.Random

@HiltViewModel
class MissionViewModel @Inject constructor(
    private val getBusNowLocationUseCase: GetBusNowLocationUseCase,
    private val getSubwayTrainNowStationUseCase: GetSubwayTrainNowStationUseCase,
    private val getNowStationLocationUseCase: GetNowStationLocationUseCase,
    private val getSubwayRouteUseCase: GetSubwayRouteUseCase
) : ViewModel() {

    private val random = Random(System.currentTimeMillis())

    private val _destination = MutableLiveData<String>()
    val destination: LiveData<String>
        get() = _destination

    private val _timeIncreased = MutableLiveData<Int>()
    val timeIncreased: LiveData<Int>
        get() = _timeIncreased

    private val _estimatedTimeRemaining = MutableLiveData<Int>()
    val estimatedTimeRemaining: LiveData<Int>
        get() = _estimatedTimeRemaining

    val leftMinute: LiveData<String> = Transformations.switchMap(estimatedTimeRemaining) {
        MutableLiveData<String>().apply {
            value = (it / TIME_UNIT).toString().padStart(TIME_DIGIT, '0')
        }
    }

    val leftSecond: LiveData<String> = Transformations.switchMap(estimatedTimeRemaining) {
        MutableLiveData<String>().apply {
            value = (it % TIME_UNIT).toString().padStart(TIME_DIGIT, '0')
        }
    }

    private val _busNowLocationInfo = MutableLiveData<BusInfoUseCaseItem>()
    val busNowLocationInfo: LiveData<BusInfoUseCaseItem> = _busNowLocationInfo

    private val _nowStationLocationInfo = MutableLiveData<NowStationLocationUseCaseItem>()
    val nowStationLocationInfo: LiveData<NowStationLocationUseCaseItem> = _nowStationLocationInfo

    var personCurrentLocation = Location(37.553836, 126.969652)
    var busCurrentLocation = Location(37.553836, 126.969652)

    init {
        getBusNowLocation()
        getNowStationLocation()
    }

    fun setDestination(inputDestination: String) {
        _destination.value = inputDestination
    }

    fun countDownWith(startTime: Int) {
        _estimatedTimeRemaining.value = startTime
        var leftTime = startTime
        viewModelScope.launch {
            while (leftTime > TIME_ZERO) {
                delay(DELAY_TIME)
                leftTime -= ONE_SECOND
                if (leftTime <= TIME_ZERO) {
                    break
                }
                _estimatedTimeRemaining.value = leftTime
            }
        }

        viewModelScope.launch {
            while (leftTime > TIME_ZERO) {
                delay(DELAY_TIME)
                if (random.nextInt(ZERO, RANDOM_LIMIT) == ZERO) {
                    val increasedTime = random.nextInt(-RANDOM_LIMIT, RANDOM_LIMIT)
                    if (increasedTime == ZERO) {
                        continue
                    }
                    leftTime += increasedTime
                    _timeIncreased.value = increasedTime
                }
                if (leftTime < TIME_ZERO) {
                    leftTime = 0
                }
                _estimatedTimeRemaining.value = leftTime
            }
        }
    }

    private fun getBusNowLocation() {
        viewModelScope.launch {
            while (TIME_TEST < 60) {
                getBusNowLocationUseCase(TEST_BUS_540_ID).apply {
                    _busNowLocationInfo.value = this
                }
                Log.d("MissionViewModel", "busNowLocationInfo ${_busNowLocationInfo.value}")
                delay(5000)
                TIME_TEST += 1
            }

        }
    }

    private suspend fun getSubwayTrainNowLocation() = withContext(Dispatchers.Main) {
        getSubwayTrainNowStationUseCase(TEST_TRAIN_NUMBER, TEST_SUBWAY_NUMER)
    }


    suspend fun getNowStationLocation() = withContext(Dispatchers.Main) {
        val searchKeyword = getSubwayTrainNowLocation().stationName
        getNowStationLocationUseCase(
            TMAP_VERSION,
            searchKeyword,
            personCurrentLocation.longitude,
            personCurrentLocation.latitude,
            BuildConfig.T_MAP_APP_KEY
        )
    }

//    private fun getSubwayRoute() {
//        viewModelScope.launch {
//            getSubwayRouteUseCase(RouteRequest())
//        }
//    }

    companion object {
        private const val DELAY_TIME = 1000L
        private const val TIME_ZERO = 0
        private const val TIME_UNIT = 60
        private const val TIME_DIGIT = 2
        private const val ONE_SECOND = 1
        private const val RANDOM_LIMIT = 5
        private const val ZERO = 0

        private const val TEST_BUS_540_ID = "100100083"
        private var TIME_TEST = 0

        private const val TEST_SUBWAY_NUMER = 4
        private const val TEST_TRAIN_NUMBER = "4615"

        private const val TMAP_VERSION = 1
    }

}