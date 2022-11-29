package com.stop.data.remote.source.route

import com.stop.domain.model.geoLocation.AddressType
import com.stop.domain.model.route.gyeonggi.*
import com.stop.domain.model.route.seoul.bus.BusLastTimeResponse
import com.stop.domain.model.route.seoul.bus.BusRouteResponse
import com.stop.domain.model.route.seoul.bus.BusStationArsIdResponse
import com.stop.domain.model.route.seoul.subway.Station
import com.stop.domain.model.route.seoul.subway.StationLastTime
import com.stop.domain.model.route.seoul.subway.SubwayCircleType
import com.stop.domain.model.route.seoul.subway.WeekType
import com.stop.domain.model.route.tmap.RouteRequest
import com.stop.domain.model.route.tmap.custom.Coordinate
import com.stop.domain.model.route.tmap.origin.ReverseGeocodingResponse
import com.stop.domain.model.route.tmap.origin.RouteResponse

internal interface RouteRemoteDataSource {

    suspend fun getRoute(routeRequest: RouteRequest): RouteResponse
    suspend fun reverseGeocoding(coordinate: Coordinate, addressType: AddressType): ReverseGeocodingResponse

    suspend fun getSubwayStationCd(stationId: String, stationName: String): String
    suspend fun getSubwayStations(lineName: String): List<Station>
    suspend fun getSubwayStationLastTime(
        stationId: String,
        subwayCircleType: SubwayCircleType,
        weekType: WeekType,
    ): List<StationLastTime>

    suspend fun getSeoulBusStationArsId(stationName: String): BusStationArsIdResponse
    suspend fun getSeoulBusRoute(stationId: String): BusRouteResponse
    suspend fun getSeoulBusLastTime(stationId: String, lineId: String): BusLastTimeResponse

    suspend fun getGyeonggiBusStationId(stationName: String): List<GyeonggiBusStation>
    suspend fun getGyeonggiBusRoute(stationId: String): List<GyeonggiBusRoute>
    suspend fun getGyeonggiBusLastTime(lineId: String): List<GyeonggiBusLastTime>
    suspend fun getGyeonggiBusRouteStations(lineId: String): List<GyeonggiBusStation>
}