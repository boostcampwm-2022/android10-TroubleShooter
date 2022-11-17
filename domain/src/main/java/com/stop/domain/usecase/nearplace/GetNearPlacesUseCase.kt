package com.stop.domain.usecase.nearplace

import com.stop.domain.model.nearplace.Place
import kotlinx.coroutines.flow.Flow

interface GetNearPlacesUseCase {

    suspend fun getNearPlaces(
        version: Int,
        searchKeyword: String,
        centerLon: Float,
        centerLat: Float,
        appKey: String
    ) : Flow<List<Place>>

}