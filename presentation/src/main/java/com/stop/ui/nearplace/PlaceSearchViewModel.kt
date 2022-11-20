package com.stop.ui.nearplace

import android.text.Editable
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stop.BuildConfig
import com.stop.domain.model.nearplace.Place
import com.stop.domain.usecase.nearplace.GetNearPlacesUseCase
import com.stop.model.Location
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaceSearchViewModel @Inject constructor(
    private val getNearPlacesUseCase: GetNearPlacesUseCase
) : ViewModel() {

    private val _nearPlaceList = MutableLiveData<List<Place>>()
    val nearPlaceList: LiveData<List<Place>> = _nearPlaceList

    private val eventChannel = Channel<String>()
    val errorMessage = eventChannel.receiveAsFlow()

    private val _clickPlace = MutableLiveData<Place>()
    val clickPlace : LiveData<Place> = _clickPlace

    var currentLocation = Location(0.0,0.0)

    fun afterTextChanged(s: Editable?) {
        if(s.toString().isBlank()){
            _nearPlaceList.postValue(emptyList())
        }

        getNearPlaces(
            s.toString(),
            126.96965F,
            37.55383F
        )
    }

    private fun getNearPlaces(
        searchKeyword: String,
        centerLon: Float,
        centerLat: Float
    ) {
        viewModelScope.launch {
            try {
                _nearPlaceList.postValue(
                    getNearPlacesUseCase.getNearPlaces(
                        TMAP_VERSION,
                        searchKeyword,
                        centerLon,
                        centerLat,
                        BuildConfig.TMAP_APP_KEY
                    )
                )
            } catch (e: Exception) {
                _nearPlaceList.postValue(emptyList())
                eventChannel.send(e.message ?: "something is wrong")
            }
        }
    }

    fun setClickPlace(place : Place){
        _clickPlace.value = place
    }

    companion object {
        private const val TMAP_VERSION = 1
    }

}