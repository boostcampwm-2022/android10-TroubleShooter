package com.stop.ui.mission

import android.Manifest
import android.animation.Animator
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.skt.tmap.TMapPoint
import com.stop.MainActivity
import com.stop.R
import com.stop.databinding.FragmentMissionBinding
import com.stop.domain.model.route.tmap.custom.Place
import com.stop.domain.model.route.tmap.custom.WalkRoute
import com.stop.util.isMoreThanOreo
import com.stop.util.isMoreThanQ
import com.stop.model.alarm.AlarmStatus
import com.stop.model.map.Location
import com.stop.model.mission.MissionStatus
import com.stop.ui.alarmsetting.AlarmSettingViewModel
import com.stop.ui.mission.MissionService.Companion.MISSION_LAST_TIME
import com.stop.ui.mission.MissionService.Companion.MISSION_LOCATIONS
import com.stop.ui.mission.MissionService.Companion.MISSION_OVER
import com.stop.ui.mission.MissionService.Companion.MISSION_TIME_OVER
import com.stop.ui.util.Marker
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.launch

class MissionFragment : Fragment(), MissionHandler {

    private var _binding: FragmentMissionBinding? = null
    private val binding: FragmentMissionBinding
        get() = _binding!!

    private val missionViewModel: MissionViewModel by viewModels()
    private val alarmSettingViewModel: AlarmSettingViewModel by activityViewModels()

    private lateinit var tMap: MissionTMap
    private lateinit var missionServiceIntent: Intent
    private lateinit var backPressedCallback: OnBackPressedCallback
    private lateinit var userInfoReceiver: BroadcastReceiver
    private lateinit var timeReceiver: BroadcastReceiver

    var personCurrentLocation = Location(37.553836, 126.969652)
    var firstTime = 0

    override fun onAttach(context: Context) {
        super.onAttach(context)

        backPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                requireActivity().finish()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(this, backPressedCallback)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setMissionService()
        setUserInfoBroadcastReceiver()
        setTimeOverBroadcastReceiver()
        missionViewModel.missionStatus.value = MissionStatus.ONGOING
    }

    private fun setMissionService() {
        missionServiceIntent = Intent(requireActivity(), MissionService::class.java)
        if (isMoreThanOreo()) {
            requireActivity().startForegroundService(missionServiceIntent)
        } else {
            requireActivity().startService(missionServiceIntent)
        }
    }

    private fun setUserInfoBroadcastReceiver() {
        val intentFilter = IntentFilter().apply {
            addAction(MissionService.MISSION_USER_INFO)
        }

        userInfoReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                missionViewModel.lastTime.value = intent?.getStringExtra(MISSION_LAST_TIME)
                missionViewModel.userLocations.value =
                    intent?.getParcelableArrayListExtra<Location>(MISSION_LOCATIONS) as ArrayList<Location>
            }
        }

        requireActivity().registerReceiver(userInfoReceiver, intentFilter)
    }

    private fun setTimeOverBroadcastReceiver() {
        val intentFilter = IntentFilter().apply {
            addAction(MISSION_TIME_OVER)
        }

        timeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.getBooleanExtra(MISSION_TIME_OVER, false) == true) {
                    Snackbar.make(
                        requireActivity().findViewById(R.id.constraint_layout_container),
                        "시간이 만료되어 미션에 실패하셨습니다.",
                        Snackbar.LENGTH_SHORT
                    ).show()
                    setFailAnimation()
                }

            }
        }
        requireActivity().registerReceiver(timeReceiver, intentFilter)
    }

    private fun setFailAnimation() {
        with(binding.lottieFail) {
            visibility = View.VISIBLE
            playAnimation()
            addAnimatorListener(object : Animator.AnimatorListener {

                override fun onAnimationEnd(animation: Animator) {
                    missionViewModel.missionStatus.value = MissionStatus.OVER
                }

                override fun onAnimationStart(animation: Animator) = Unit
                override fun onAnimationCancel(animation: Animator) = Unit
                override fun onAnimationRepeat(animation: Animator) = Unit

            })
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMissionBinding.inflate(layoutInflater)

        initBinding()

        return binding.root
    }

    private fun initBinding() {
        alarmSettingViewModel.getAlarm()

        binding.lifecycleOwner = viewLifecycleOwner
        binding.missionViewModel = missionViewModel
        binding.alarmSettingViewModel = alarmSettingViewModel
        binding.fragment = this@MissionFragment
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setTimer()
        initTMap()
        setMissionOver()
        checkLocationPermission()
    }

    private fun setTimer() {
        missionServiceIntent.putExtra(MISSION_LAST_TIME, alarmSettingViewModel.alarmItem.value?.lastTime)
        if (isMoreThanOreo()) {
            requireActivity().startForegroundService(missionServiceIntent)
        } else {
            requireActivity().startService(missionServiceIntent)
        }

        missionServiceIntent.removeExtra(MISSION_LAST_TIME)
    }

    private fun initTMap() {
        tMap = MissionTMap(requireActivity(), this)
        tMap.init()

        binding.constraintLayoutContainer.addView(tMap.tMapView)
    }

    private fun setMissionOver() {
        viewLifecycleOwner.lifecycleScope.launch {
            missionViewModel.missionStatus.collect { missionStatus ->
                if (missionStatus == MissionStatus.OVER) {
                    alarmSettingViewModel.deleteAlarm()
                    missionServiceIntent.putExtra(MISSION_OVER, true)
                    if (isMoreThanOreo()) {
                        requireActivity().startForegroundService(missionServiceIntent)
                    } else {
                        requireActivity().startService(missionServiceIntent)
                    }
                    requireActivity().stopService(missionServiceIntent)
                    missionViewModel.missionStatus.value = MissionStatus.BEFORE
                    alarmSettingViewModel.alarmStatus.value = AlarmStatus.NON_EXIST

                    Intent(requireActivity(), MainActivity::class.java).apply {
                        flags =
                            Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        startActivity(this)
                    }
                }
            }
        }
    }

    private fun checkLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (PackageManager.PERMISSION_GRANTED != ContextCompat.checkSelfPermission(
                    requireActivity(),
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                )
            ) {
                setPermissionDialog(requireActivity())
            }
        }
    }

    fun setCompassMode() {
        tMap.tMapView.isCompassMode = tMap.tMapView.isCompassMode.not()
    }

    fun setPersonCurrent() {
        tMap.tMapView.setCenterPoint(
            personCurrentLocation.latitude,
            personCurrentLocation.longitude,
            true
        )

        tMap.isTracking = true
        tMap.tMapView.zoomLevel = 16
    }

    fun setZoomOut() {
        with(tMap) {
            latitudes.clear()
            longitudes.clear()
            latitudes.add(missionViewModel.destination.value.coordinate.latitude.toDouble())
            longitudes.add(missionViewModel.destination.value.coordinate.longitude.toDouble())
            latitudes.add(personCurrentLocation.latitude)
            longitudes.add(personCurrentLocation.longitude)
            setRouteDetailFocus()
            isTracking = false
        }
    }

    fun clickMissionOver() {
        Snackbar.make(
            requireActivity().findViewById(R.id.constraint_layout_container),
            "미션을 취소했습니다",
            Snackbar.LENGTH_SHORT
        ).show()
        missionViewModel.missionStatus.value = MissionStatus.OVER
    }

    override fun alertTMapReady() {
        requestPermissionsLauncher.launch(PERMISSIONS)
        getAlarmInfo()
        alarmSettingViewModel.alarmStatus.value = AlarmStatus.MISSION
        drawPersonLine()
    }

    override fun setOnEnableScrollWithZoomLevelListener() {
        tMap.apply {
            tMapView.setOnEnableScrollWithZoomLevelListener { _, _ ->
                isTracking = false
            }
        }
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.entries.any { it.value }) {
            tMap.isTracking = false
        }
    }

    private fun drawPersonLine() {
        lateinit var beforeLocation: Location
        viewLifecycleOwner.lifecycleScope.launch {
            missionViewModel.userLocations.collectIndexed { index, userLocation ->
                if (index == 1) {
                    initMarker(userLocation)
                    beforeLocation = userLocation.last()
                } else if (index > 1) {
                    drawNowLocationLine(
                        TMapPoint(userLocation.last().latitude, userLocation.last().longitude),
                        TMapPoint(beforeLocation.latitude, beforeLocation.longitude)
                    )
                    personCurrentLocation = userLocation.last()
                    if (tMap.isTracking) {
                        tMap.tMapView.setCenterPoint(userLocation.last().latitude, userLocation.last().longitude)
                    }
                    beforeLocation = userLocation.last()
                    arriveDestination(userLocation.last().latitude, userLocation.last().longitude)
                }
            }
        }
    }

    private fun initMarker(nowLocation: ArrayList<Location>) {
        with(tMap) {
            addMarker(
                Marker.PERSON_MARKER,
                Marker.PERSON_MARKER_IMG,
                TMapPoint(nowLocation.last().latitude, nowLocation.last().longitude)
            )
            personCurrentLocation = nowLocation.last()
            latitudes.add(nowLocation.last().latitude)
            longitudes.add(nowLocation.last().longitude)
            setRouteDetailFocus()
            arriveDestination(nowLocation.last().latitude, nowLocation.last().longitude)

            drawWalkLines(
                nowLocation.map { TMapPoint(it.latitude, it.longitude) } as ArrayList<TMapPoint>,
                Marker.PERSON_LINE + PERSON_LINE_NUM.toString(),
                Marker.PERSON_LINE_COLOR
            )
            PERSON_LINE_NUM += 1

        }
    }

    private fun drawNowLocationLine(nowLocation: TMapPoint, beforeLocation: TMapPoint) {
        tMap.drawMoveLine(
            nowLocation,
            beforeLocation,
            Marker.PERSON_LINE + PERSON_LINE_NUM.toString(),
            Marker.PERSON_LINE_COLOR
        )
        PERSON_LINE_NUM += 1

        tMap.addMarker(Marker.PERSON_MARKER, Marker.PERSON_MARKER_IMG, nowLocation)
    }

    private fun getAlarmInfo() {
        alarmSettingViewModel.getAlarm(missionViewModel.missionStatus.value)
        val linePoints = arrayListOf<TMapPoint>()
        val walkInfo = alarmSettingViewModel.alarmItem.value?.routes as WalkRoute
        tMap.makeWalkRoute(walkInfo, linePoints)
        tMap.drawWalkLines(linePoints, Marker.WALK_LINE, Marker.WALK_LINE_COLOR)

        missionViewModel.destination.value = walkInfo.end
        makeDestinationMarker(walkInfo.end)
    }

    private fun makeDestinationMarker(destination: Place) {
        val latitude = destination.coordinate.latitude.toDouble()
        val longitude = destination.coordinate.longitude.toDouble()
        tMap.addMarker(
            Marker.DESTINATION_MARKER,
            Marker.DESTINATION_MARKER_IMG,
            TMapPoint(latitude, longitude)
        )
        tMap.latitudes.add(latitude)
        tMap.longitudes.add(longitude)
    }

    private fun arriveDestination(nowLatitude: Double, nowLongitude: Double) {
        if (tMap.getDistance(
                nowLatitude,
                nowLongitude,
                missionViewModel.destination.value.coordinate.latitude.toDouble(),
                missionViewModel.destination.value.coordinate.longitude.toDouble()
            ) <= 10
            && firstTime == 0
        ) {
            firstTime += 1
            Snackbar.make(
                requireActivity().findViewById(R.id.constraint_layout_container),
                "정류장에 도착했습니다!",
                Snackbar.LENGTH_SHORT
            ).show()
            setSuccessAnimation()
        }
    }

    private fun setSuccessAnimation() {
        with(binding.lottieSuccess) {
            playAnimation()
            addAnimatorListener(object : Animator.AnimatorListener {
                override fun onAnimationStart(animation: Animator) {
                }

                override fun onAnimationEnd(animation: Animator) {
                    missionViewModel.missionStatus.value = MissionStatus.OVER
                }

                override fun onAnimationCancel(animation: Animator) {
                }

                override fun onAnimationRepeat(animation: Animator) {
                }
            })
        }
    }

    private fun setPermissionDialog(context: Context) {
        if (isMoreThanQ()) {
            val builder = AlertDialog.Builder(context)
            builder.setTitle("백그라운드 위치 권한을 위해 항상 허용으로 설정해주세요.")

            val listener = DialogInterface.OnClickListener { _, p1 ->
                when (p1) {
                    DialogInterface.BUTTON_POSITIVE ->
                        setBackgroundPermission()
                }
            }
            builder.setPositiveButton("네", listener)
            builder.setNegativeButton("아니오", null)

            builder.show()
        }
    }

    private fun setBackgroundPermission() {
        if (isMoreThanQ()) {
            ActivityCompat.requestPermissions(
                requireActivity(),
                arrayOf(
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                ), 2
            )
        }
    }

    override fun onDestroyView() {
        _binding = null

        super.onDestroyView()
    }

    override fun onDestroy() {
        tMap.onDestroy()

        requireActivity().unregisterReceiver(userInfoReceiver)
        requireActivity().unregisterReceiver(timeReceiver)

        super.onDestroy()
    }

    companion object {
        private var PERSON_LINE_NUM = 0

        private val PERMISSIONS =
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

}