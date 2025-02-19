package com.stop.domain.usecase.route

import com.stop.domain.model.geoLocation.AddressType
import com.stop.domain.model.route.*
import com.stop.domain.model.route.gyeonggi.GyeonggiBusStation
import com.stop.domain.model.route.seoul.bus.BusStationInfo
import com.stop.domain.model.route.seoul.subway.Station
import com.stop.domain.model.route.seoul.subway.TransportDirectionType
import com.stop.domain.model.route.seoul.subway.WeekType
import com.stop.domain.model.route.tmap.custom.*
import com.stop.domain.repository.RouteRepository
import javax.inject.Inject
import kotlin.math.abs

internal class GetLastTransportTimeUseCaseImpl @Inject constructor(
    private val routeRepository: RouteRepository
) : GetLastTransportTimeUseCase {

    private val allowedSubwayLineForUse = (SUBWAY_LINE_ONE..SUBWAY_LINE_EIGHT)

    override suspend operator fun invoke(itinerary: Itinerary): List<TransportLastTime?> {
        var transportIdRequests: List<TransportIdRequest?> = createTransportIdRequests(itinerary)
        transportIdRequests = convertStationId(transportIdRequests)
        transportIdRequests = convertRouteId(transportIdRequests)

        return getLastTransportTime(transportIdRequests)
    }

    private suspend fun getLastTransportTime(transportIdRequests: List<TransportIdRequest?>): List<TransportLastTime?> {
        return transportIdRequests.map { transportIdRequest ->
            if (transportIdRequest == null) {
                return@map null
            }
            try {
                when (transportIdRequest.transportMoveType) {
                    TransportMoveType.BUS -> getBusLastTransportTime(transportIdRequest)
                    TransportMoveType.SUBWAY -> getSubwayLastTransportTime(transportIdRequest)
                }
            } catch (exception: ApiServerDataException) {
                null
            } catch (exception: NoAppropriateDataException) {
                null
            } catch (exception: NoServiceAreaException) {
                null
            } catch (exception: IllegalArgumentException) {
                null
            }
        }
    }

    // 공공데이터 포털에서 사용하는 버스 노선 번호를 계산하는 작업
    // 지하철은 별도의 작업이 필요하지 않습니다.
    private suspend fun convertRouteId(
        transportIdRequests: List<TransportIdRequest?>
    ): List<TransportIdRequest?> {
        return transportIdRequests.map { transportIdRequest ->
            if (transportIdRequest == null) {
                return@map null
            }
            try {
                when (transportIdRequest.transportMoveType) {
                    TransportMoveType.BUS -> {
                        when (transportIdRequest.area) {
                            Area.SEOUL -> convertSeoulBusRouteId(transportIdRequest)
                            Area.GYEONGGI -> convertGyeonggiBusRouteId(transportIdRequest)
                            Area.UN_SUPPORT_AREA -> throw NoServiceAreaException()
                        }
                    }
                    TransportMoveType.SUBWAY -> transportIdRequest
                }
            } catch (exception: NoAppropriateDataException) {
                null
            } catch (exception: NoServiceAreaException) {
                null
            }
        }
    }

    // 공공데이터 포털에서 사용하는 버스 정류소, 지하철 역의 고유번호로 변환하는 작업
    private suspend fun convertStationId(
        transportIdRequests: List<TransportIdRequest?>
    ): List<TransportIdRequest?> {
        return transportIdRequests.map { transportIdRequest ->
            if (transportIdRequest == null) {
                return@map null
            }
            try {
                when (transportIdRequest.transportMoveType) {
                    TransportMoveType.SUBWAY -> convertSubwayStationId(transportIdRequest)
                    TransportMoveType.BUS -> convertBusStationId(transportIdRequest)
                }
            } catch (exception: NoAppropriateDataException) {
                null
            } catch (exception: ApiServerDataException) {
                null
            } catch (exception: NoServiceAreaException) {
                null
            }
        }
    }

    private suspend fun convertSubwayStationId(
        transportIdRequest: TransportIdRequest
    ): TransportIdRequest {
        // 22년 11월 기준, 공공데이터 포털에서 1 ~ 8호선에 속한 지하철 역의 막차 시간만 제공합니다.
        if (allowedSubwayLineForUse.contains(transportIdRequest.stationType).not()) {
            throw NoAppropriateDataException("API를 지원하지 않는 전철역입니다.")
        }
        val stationCd = routeRepository.getSubwayStationCd(
            transportIdRequest.stationId,
            transportIdRequest.stationName
        )

        if (stationCd.isEmpty()) {
            throw ApiServerDataException()
        }

        return transportIdRequest.changeStartStationId(stationCd)
    }

    // 승차지, 도착지, 고유 번호를 알아내는데 필요한 정보로만 구성된 데이터 클래스로 변환하기
    private suspend fun createTransportIdRequests(itinerary: Itinerary): List<TransportIdRequest?> {
        var cumulativeSectionTime = 0

        return itinerary.routes.fold(listOf()) { transportIdRequests, route ->
            when (route) {
                is WalkRoute -> transportIdRequests + null
                is TransportRoute -> {
                    val startStation = route.stations.first()
                    val transportMoveType = TransportMoveType.getMoveTypeByName(route.mode.name)
                        ?: return@fold transportIdRequests

                    val sectionTime = route.sectionTime.toInt()
                    cumulativeSectionTime += sectionTime

                    transportIdRequests + TransportIdRequest(
                        transportMoveType = transportMoveType,
                        stationId = startStation.stationId,
                        stationName = startStation.stationName,
                        coordinate = startStation.coordinate,
                        stationType = route.routeType,
                        area = getArea(startStation.coordinate),
                        routeName = route.routeInfo,
                        routeId = UNKNOWN_ID,
                        term = NOT_YET_CALCULATED,
                        destinationStation = route.end,
                        destinationStationId = UNKNOWN_ID,
                        sectionTime = sectionTime,
                        cumulativeSectionTime = cumulativeSectionTime,
                    )
                }
                else -> transportIdRequests + null
            }
        }
    }

    private suspend fun convertGyeonggiBusRouteId(
        transportIdRequest: TransportIdRequest
    ): TransportIdRequest {
        val busName = transportIdRequest.routeName.split(":")[1]
        val routes = routeRepository.getGyeonggiBusRoute(transportIdRequest.stationId)

        if (routes.isEmpty()) {
            throw ApiServerDataException()
        }
        val route = routes.firstOrNull {
            it.busName.contains(busName)
        } ?: throw NoAppropriateDataException("버스 노선 고유 아이디가 없습니다.")

        return transportIdRequest.changeRouteId(route.routeId, null)
    }

    private suspend fun getSubwayLastTransportTime(transportIdRequest: TransportIdRequest): TransportLastTime {
        val stationsOfLine =
            routeRepository.getSubwayStations(transportIdRequest.stationType.toString())
                .sortedWith(compareBy { it.frCode })

        if (stationsOfLine.isEmpty()) {
            throw ApiServerDataException()
        }

        val startStationIndex = stationsOfLine.indexOfFirst {
            it.stationName == transportIdRequest.stationName
        }
        if (startStationIndex == -1) {
            throw NoAppropriateDataException("노선에 해당하는 지하철이 없습니다.")
        }

        val endStationIndex = stationsOfLine.indexOfFirst {
            it.stationName == transportIdRequest.destinationStation.name
        }
        if (endStationIndex == -1) {
            throw NoAppropriateDataException("노선에 해당하는 지하철이 없습니다.")
        }

        // 내선, 외선 여부 확인
        // 2호선만 FR_CODE가 감소하면 외선, 그 외 1, 3 ~ 7호선은 FR_CODE가 증가하면 외선
        val subwayCircleType = checkInnerOrOuter(
            transportIdRequest.stationType,
            startStationIndex,
            endStationIndex,
            stationsOfLine
        )

        val weekType = getDayOfWeek()

        val stationsUntilStart: List<Station>
        val enableDestinationStation: List<Station>

        if (startStationIndex < endStationIndex) {
            stationsUntilStart = stationsOfLine.subList(0, startStationIndex + 1)
            enableDestinationStation = stationsOfLine.subList(startStationIndex, endStationIndex)
        } else {
            stationsUntilStart = stationsOfLine.subList(startStationIndex, stationsOfLine.size)
            enableDestinationStation =
                stationsOfLine.subList(endStationIndex + 1, startStationIndex + 1)
        }

        val lastTrainTime = routeRepository.getSubwayStationLastTime(
            transportIdRequest.stationId,
            subwayCircleType,
            weekType
        )

        if (lastTrainTime.isEmpty()) {
            throw ApiServerDataException()
        }

        val correctionValueByStationCase = checkStationCase(
            transportIdRequest.stationType,
            subwayCircleType,
            startStationIndex,
            endStationIndex,
            stationsOfLine
        )

        /**
         * suffix: True
         * true -> true
         * false -> false
         *
         * suffix: False
         * true -> false
         * false -> true
         *
         */
        val result = lastTrainTime.firstOrNull { stationsListTime ->
            enableDestinationStation.any {
                it.stationName == stationsListTime.destinationStationName
            }.xor(correctionValueByStationCase)
                .not() || transportIdRequest.destinationStation.name == stationsListTime.destinationStationName
        }?.leftTime ?: throw IllegalArgumentException("막차 시간 로직이 잘못되었습니다.")

        return TransportLastTime(
            transportMoveType = transportIdRequest.transportMoveType,
            area = transportIdRequest.area,
            lastTime = result,
            timeToBoard = subtractSectionTimeFromLastTime(
                transportIdRequest.cumulativeSectionTime,
                result
            ),
            destinationStationName = transportIdRequest.destinationStation.name,
            stationsUntilStart = stationsUntilStart.map {
                TransportStation(
                    stationName = it.stationName,
                    stationId = it.stationCd,
                )
            },
            enableDestinationStation = enableDestinationStation.map {
                TransportStation(
                    stationName = it.stationName,
                    stationId = it.stationCd,
                )
            },
            transportDirectionType = subwayCircleType,
            routeId = transportIdRequest.routeId,
        )
    }

    private fun subtractSectionTimeFromLastTime(sectionTime: Int, lastTime: String): String {
        val (hour, minute, second) = lastTime.split(":").map { it.toInt() }
        val lastTimeSecond = hour * 60 * 60 + minute * 60 + second

        val realLastTimeSecond = lastTimeSecond - sectionTime

        val realHour = realLastTimeSecond / 60 / 60
        val realMinute = ((realLastTimeSecond / 60) % 60).toString().padStart(TIME_DIGIT, '0')
        val realSeconds = (realLastTimeSecond % 60).toString().padStart(TIME_DIGIT, '0')

        return "$realHour:$realMinute:$realSeconds"
    }

    private fun checkInnerOrOuter(
        stationType: Int,
        startStationIndex: Int,
        endStationIndex: Int,
        stationsOfLine: List<Station>,
    ): TransportDirectionType {
        return if (stationType == 2) {
            if (startStationIndex < endStationIndex) {
                if (stationsOfLine[startStationIndex].frCode.contains("211-") // 성수 ~ 신설동 예외처리
                    || stationsOfLine[endStationIndex].frCode.contains("211-")
                ) {
                    TransportDirectionType.OUTER
                } else {
                    TransportDirectionType.INNER
                }
            } else {
                if (startStationIndex - endStationIndex >= EMPIRICAL_DISTINCTION) {
                    TransportDirectionType.INNER
                } else {
                    TransportDirectionType.OUTER
                }
            }
        } else {
            if (startStationIndex < endStationIndex) {
                TransportDirectionType.TO_FIRST
            } else {
                TransportDirectionType.TO_END
            }
        }
    }

    private fun checkStationCase(
        stationType: Int,
        transportDirectionType: TransportDirectionType,
        startIndex: Int,
        endIndex: Int,
        stationsOfLine: List<Station>,
    ): Boolean {
        if (stationType != 2) {
            return false
        }

        if (transportDirectionType == TransportDirectionType.OUTER) {
            if (startIndex < endIndex) {
                if (stationsOfLine[startIndex].frCode.contains("211-") // 성수 ~ 신설동 예외처리
                    || stationsOfLine[endIndex].frCode.contains("211-")
                ) {
                    return false
                }
                return true
            }
            return false
        }
        return startIndex > endIndex
    }

    /**
     * 요일 별로 막차 시간이 다르기 때문에, 앱을 실행하는 오늘의 요일도 받아야 한다.
     */
    private fun getDayOfWeek(): WeekType {
        return WeekType.WEEK
    }

    private suspend fun getBusLastTransportTime(transportIdRequest: TransportIdRequest): TransportLastTime {
        return when (transportIdRequest.area) {
            Area.GYEONGGI -> getGyeonggiBusLastTransportTime(transportIdRequest)
            Area.SEOUL -> getSeoulBusLastTransportTime(transportIdRequest)
            Area.UN_SUPPORT_AREA -> throw NoServiceAreaException()
        }
    }

    private suspend fun getSeoulBusLastTransportTime(
        transportIdRequest: TransportIdRequest
    ): TransportLastTime {
        val lastTimes = routeRepository.getSeoulBusLastTime(
            transportIdRequest.stationId,
            transportIdRequest.routeId
        )
        if (lastTimes.isEmpty()) {
            return getRectifiedGyeonggiBusLastTransportTime(transportIdRequest)
        }

        var lastTime = lastTimes.first().lastTime?.toInt() ?: throw ApiServerDataException()

        if (lastTime < MID_NIGHT) {
            lastTime += TIME_CORRECTION_VALUE
        }

        val lastTimeString = lastTime.toString().padStart(6, '0').chunked(2).joinToString(":")

        return TransportLastTime(
            transportMoveType = TransportMoveType.BUS,
            area = Area.SEOUL,
            lastTime = lastTimeString,
            timeToBoard = subtractSectionTimeFromLastTime(
                transportIdRequest.cumulativeSectionTime,
                lastTimeString
            ),
            destinationStationName = transportIdRequest.destinationStation.name,
            stationsUntilStart = listOf(),
            enableDestinationStation = listOf(),
            transportDirectionType = TransportDirectionType.UNKNOWN, // TODO: 서울 버스의 상행, 하행 구현
            routeId = transportIdRequest.routeId,
        )
    }

    private suspend fun getRectifiedGyeonggiBusLastTransportTime(
        transportIdRequest: TransportIdRequest
    ): TransportLastTime {
        var newTransportIdRequest = convertGyeonggiBusStationId(transportIdRequest)
        newTransportIdRequest = convertGyeonggiBusRouteId(newTransportIdRequest)

        val lastTime = routeRepository.getGyeonggiBusLastTime(
            newTransportIdRequest.routeId
        ).firstOrNull() ?: throw ApiServerDataException()

        val stations = getGyeonggiBusStationsAtRoute(newTransportIdRequest)
        val (directionType, startIndex) = checkGyeonggiBusDirection(
            stations,
            newTransportIdRequest
        )

        val stationsUntilStart: List<TransportStation>
        val time: String

        if (directionType == TransportDirectionType.INNER) {
            stationsUntilStart = stations.subList(0, startIndex + 1).map {
                TransportStation(
                    stationName = it.stationName,
                    stationId = it.stationId.toString(),
                )
            }
            time = addSecondsFormat(lastTime.upLastTime)
        } else {
            stationsUntilStart = stations.subList(startIndex, stations.size).reversed().map {
                TransportStation(
                    stationName = it.stationName,
                    stationId = it.stationId.toString(),
                )
            }
            time = addSecondsFormat(lastTime.downLastTime)
        }

        return TransportLastTime(
            transportMoveType = newTransportIdRequest.transportMoveType,
            area = newTransportIdRequest.area,
            transportDirectionType = directionType,
            lastTime = time,
            timeToBoard = subtractSectionTimeFromLastTime(
                newTransportIdRequest.cumulativeSectionTime,
                time
            ),
            destinationStationName = newTransportIdRequest.destinationStation.name,
            stationsUntilStart = stationsUntilStart,
            enableDestinationStation = listOf(),
            routeId = newTransportIdRequest.routeId,
        )
    }

    private suspend fun getGyeonggiBusLastTransportTime(
        transportIdRequest: TransportIdRequest
    ): TransportLastTime {
        val lastTime = routeRepository.getGyeonggiBusLastTime(
            transportIdRequest.routeId
        ).firstOrNull() ?: return getRectifiedSeoulBusLastTransportTime(transportIdRequest)


        val stations = getGyeonggiBusStationsAtRoute(transportIdRequest)
        val (directionType, startIndex) = checkGyeonggiBusDirection(
            stations,
            transportIdRequest
        )

        val stationsUntilStart: List<TransportStation>
        val time: String

        if (directionType == TransportDirectionType.INNER) {
            stationsUntilStart = stations.subList(0, startIndex + 1).map {
                TransportStation(
                    stationName = it.stationName,
                    stationId = it.stationId.toString(),
                )
            }
            time = addSecondsFormat(lastTime.upLastTime)
        } else {
            stationsUntilStart = stations.subList(startIndex, stations.size).reversed().map {
                TransportStation(
                    stationName = it.stationName,
                    stationId = it.stationId.toString(),
                )
            }
            time = addSecondsFormat(lastTime.downLastTime)
        }

        return TransportLastTime(
            transportMoveType = transportIdRequest.transportMoveType,
            area = transportIdRequest.area,
            transportDirectionType = directionType,
            lastTime = time,
            timeToBoard = subtractSectionTimeFromLastTime(
                transportIdRequest.cumulativeSectionTime,
                time
            ),
            destinationStationName = transportIdRequest.destinationStation.name,
            stationsUntilStart = stationsUntilStart,
            enableDestinationStation = listOf(),
            routeId = transportIdRequest.routeId,
        )
    }

    private suspend fun getRectifiedSeoulBusLastTransportTime(
        transportIdRequest: TransportIdRequest
    ): TransportLastTime {
        var newTransportIdRequest = convertSeoulBusStationId(transportIdRequest)
        newTransportIdRequest = convertSeoulBusRouteId(newTransportIdRequest)

        val lastTimes = routeRepository.getSeoulBusLastTime(
            newTransportIdRequest.stationId,
            newTransportIdRequest.routeId
        )

        if (lastTimes.isEmpty()) {
            throw ApiServerDataException()
        }

        var lastTime = lastTimes.first().lastTime?.toInt() ?: throw ApiServerDataException()

        if (lastTime < MID_NIGHT) {
            lastTime += TIME_CORRECTION_VALUE
        }

        val lastTimeString = lastTime.toString().padStart(6, '0').chunked(2).joinToString(":")

        return TransportLastTime(
            transportMoveType = TransportMoveType.BUS,
            area = Area.SEOUL,
            lastTime = lastTimeString,
            timeToBoard = subtractSectionTimeFromLastTime(
                newTransportIdRequest.cumulativeSectionTime,
                lastTimeString
            ),
            destinationStationName = transportIdRequest.destinationStation.name,
            stationsUntilStart = listOf(),
            enableDestinationStation = listOf(),
            transportDirectionType = TransportDirectionType.UNKNOWN,
            routeId = transportIdRequest.routeId,
        )
    }

    private fun addSecondsFormat(time: String): String {
        return "$time:00"
    }

    private suspend fun getGyeonggiBusStationsAtRoute(
        transportIdRequest: TransportIdRequest
    ): List<GyeonggiBusStation> {
        val stations = routeRepository.getGyeonggiBusRouteStations(transportIdRequest.routeId)

        if (stations.isEmpty()) {
            throw ApiServerDataException()
        }
        return stations

    }

    // 해당 노선의 정류소 목록을 가져와서 버스의 기점행, 종점행 구분
    private fun checkGyeonggiBusDirection(
        stations: List<GyeonggiBusStation>,
        transportIdRequest: TransportIdRequest
    ): Pair<TransportDirectionType, Int> {
        var startIndex: Int = -1
        var endIndex: Int = -1

        for ((index, station) in stations.withIndex()) {
            val stationId = station.stationId.toString()

            if (stationId == transportIdRequest.stationId) {
                startIndex = index
            } else if (stationId == transportIdRequest.destinationStationId) {
                endIndex = index
            }

            if (startIndex != -1 && endIndex != -1) {
                break
            }
        }

        if (startIndex == -1 || endIndex == -1) {
            throw NoAppropriateDataException("버스 정류소 고유 아이디가 없습니다.")
        }

        if (startIndex < endIndex) {
            return Pair(TransportDirectionType.TO_END, startIndex)
        }
        return Pair(TransportDirectionType.TO_FIRST, startIndex)
    }

    private suspend fun convertSeoulBusRouteId(
        transportIdRequest: TransportIdRequest
    ): TransportIdRequest {
        val busName = transportIdRequest.routeName.split(":")[1]
        val busRouteInfo = routeRepository.getSeoulBusRoute(transportIdRequest.stationId)

        if (busRouteInfo.isEmpty()) {
            throw ApiServerDataException()
        }

        val route = busRouteInfo.firstOrNull {
            it.busRouteName.contains(busName)
        } ?: throw NoAppropriateDataException("버스 노선 고유 아이디가 없습니다.")

        return transportIdRequest.changeRouteId(route.routeId, route.term)
    }

    private suspend fun getArea(coordinate: Coordinate): Area {
        val areaName = try {
            routeRepository.reverseGeocoding(coordinate, AddressType.LOT_ADDRESS).addressInfo.cityDo
        } catch (exception: IllegalArgumentException) {
            return Area.UN_SUPPORT_AREA
        }

        return Area.getAreaByName(areaName)
    }

    /**
     * 버스의 막차 시간, 배차 시간 조회에 필요한 ID를 구합니다.
     * T MAP은 정류소의 정확한 좌표를 찍어주지만, 공공데이터는 정류소 주변부 좌표를 보내주기 때문에
     * 좌표가 가장 근접한 것을 선택해야 합니다.
     * 서울 버스 API에 경기도 정류소를 조회하면 arsId가 0으로 나옵니다.
     * arsID가 0인 경우 서울 버스를 경기도에서, 경기도 버스를 서울에서 검색한 건 아닌지 확인해주세요.
     */
    private suspend fun convertBusStationId(
        transportIdRequest: TransportIdRequest
    ): TransportIdRequest {
        return when (transportIdRequest.area) {
            Area.GYEONGGI -> convertGyeonggiBusStationId(transportIdRequest)
            Area.SEOUL -> convertSeoulBusStationId(transportIdRequest)
            Area.UN_SUPPORT_AREA -> throw NoServiceAreaException()
        }
    }

    private suspend fun convertSeoulBusStationId(
        transportIdRequest: TransportIdRequest
    ): TransportIdRequest {
        val busStations =
            routeRepository.getSeoulBusStationArsId(transportIdRequest.stationName)

        if (busStations.isEmpty()) {
            throw ApiServerDataException()
        }

        val arsId = findClosestSeoulBusStation(transportIdRequest, busStations)

        // API 서버 데이터의 문제로 버스 정류소 고유 아이디가 없는 경우가 있습니다.
        if (arsId == UNKNOWN_ID) {
            throw ApiServerDataException()
        }
        return transportIdRequest.changeStartStationId(arsId)
    }

    private suspend fun convertGyeonggiBusStationId(
        transportIdRequest: TransportIdRequest
    ): TransportIdRequest {
        if (transportIdRequest.routeName.contains(LOCAL_BUS_NAME)) {
            throw NoAppropriateDataException("경기도 마을 버스 정보는 API에서 제공하지 않습니다.")
        }

        val startStationId = getGyeonggiBusStationId(
            Place(transportIdRequest.stationName, transportIdRequest.coordinate)
        )

        // API 서버 데이터의 문제로 버스 정류소 고유 아이디가 없는 경우가 있습니다.
        if (startStationId == UNKNOWN_ID) {
            throw ApiServerDataException()
        }

        val endStationId = getGyeonggiBusStationId(
            Place(
                transportIdRequest.destinationStation.name,
                transportIdRequest.destinationStation.coordinate
            )
        )

        // API 서버 데이터의 문제로 버스 정류소 고유 아이디가 없는 경우가 있습니다.
        if (endStationId == UNKNOWN_ID) {
            throw NoAppropriateDataException("버스 정류소 고유 아이디가 없습니다.")
        }

        return transportIdRequest.changeStartStationId(startStationId)
            .changeDestinationStationId(endStationId)
    }

    private suspend fun getGyeonggiBusStationId(place: Place): String {
        val busStations = routeRepository.getGyeonggiBusStationId(place.name)

        if (busStations.isEmpty()) {
            throw ApiServerDataException()
        }
        return findClosestGyeonggiBusStation(place, busStations)
    }

    private fun findClosestSeoulBusStation(
        transportIdRequest: TransportIdRequest,
        busStations: List<BusStationInfo>,
    ): String {
        val originLongitude = correctLongitudeValue(transportIdRequest.coordinate.longitude)
        val originLatitude = correctLatitudeValue(transportIdRequest.coordinate.latitude)
        var closestStation: BusStationInfo? = null
        var closestDistance = 0.0

        busStations.filter {
            it.stationName == transportIdRequest.stationName
        }.map {
            if (closestStation == null) {
                closestStation = it
                val x = abs(originLongitude - correctLongitudeValue(it.longitude)).toDouble()
                val y = abs(originLatitude - correctLatitudeValue(it.latitude)).toDouble()
                closestDistance = x * x + y * y
                return@map
            }

            val x = abs(originLongitude - correctLongitudeValue(it.longitude)).toDouble()
            val y = abs(originLatitude - correctLatitudeValue(it.latitude)).toDouble()
            val distance = x * x + y * y

            if (distance < closestDistance) {
                closestDistance = distance
                closestStation = it
            }
        }

        return closestStation?.arsId ?: UNKNOWN_ID
    }

    private fun findClosestGyeonggiBusStation(
        place: Place,
        busStations: List<GyeonggiBusStation>,
    ): String {
        val originLongitude = correctLongitudeValue(place.coordinate.longitude)
        val originLatitude = correctLatitudeValue(place.coordinate.latitude)
        var closestStation: GyeonggiBusStation? = null
        var closestDistance = 0

        busStations.filter {
            it.stationName == place.name
        }.map {
            if (closestStation == null) {
                closestStation = it
                val x = abs(originLongitude - correctLongitudeValue(it.longitude))
                val y = abs(originLatitude - correctLatitudeValue(it.latitude))
                closestDistance = x * x + y * y
                return@map
            }

            val x = abs(originLongitude - correctLongitudeValue(it.longitude))
            val y = abs(originLatitude - correctLatitudeValue(it.latitude))
            val distance = x * x + y * y

            if (distance < closestDistance) {
                closestDistance = distance
                closestStation = it
            }
        }

        return closestStation?.stationId?.toString() ?: UNKNOWN_ID
    }

    private fun correctLongitudeValue(longitude: String): Int {
        return ((longitude.toDouble() - KOREA_LONGITUDE) * CORRECTION_VALUE).toInt()
    }

    private fun correctLatitudeValue(latitude: String): Int {
        return ((latitude.toDouble() - KOREA_LATITUDE) * CORRECTION_VALUE).toInt()
    }

    companion object {
        private const val LOCAL_BUS_NAME = "마을"
        private const val UNKNOWN_ID = "0"
        private const val NOT_YET_CALCULATED = 0
        private const val KOREA_LONGITUDE = 127
        private const val KOREA_LATITUDE = 37
        private const val CORRECTION_VALUE = 100_000
        private const val TIME_DIGIT = 2

        private const val EMPIRICAL_DISTINCTION = 20

        private const val SUBWAY_LINE_ONE = 1
        private const val SUBWAY_LINE_EIGHT = 8

        private const val MID_NIGHT = 60_000
        private const val TIME_CORRECTION_VALUE = 240_000
    }
}

class NoAppropriateDataException(override val message: String) : Exception()
class NoServiceAreaException : Exception("지원하지 않는 지역입니다.")
class ApiServerDataException : Exception("로직이 올바르지만 서버에 데이터가 없습니다.")