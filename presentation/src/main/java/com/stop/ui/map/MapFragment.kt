package com.stop.ui.map

import android.Manifest.permission
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.asLiveData
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.skt.tmap.TMapPoint
import com.stop.R
import com.stop.RouteNavGraphDirections
import com.stop.alarm.SoundService
import com.stop.databinding.FragmentMapBinding
import com.stop.model.AlarmStatus
import com.stop.model.Location
import com.stop.model.MissionStatus
import com.stop.ui.alarmsetting.AlarmSettingFragment.Companion.ALARM_MAP_CODE
import com.stop.ui.alarmsetting.AlarmSettingViewModel
import com.stop.ui.mission.MissionService
import com.stop.ui.mission.MissionViewModel
import com.stop.ui.placesearch.PlaceSearchViewModel
import com.stop.ui.util.Marker
import com.stop.util.getScreenSize
import kotlinx.coroutines.launch

class MapFragment : Fragment(), MapHandler {
    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!

    private val alarmViewModel: AlarmSettingViewModel by activityViewModels()
    private val placeSearchViewModel: PlaceSearchViewModel by activityViewModels()
    private val missionViewModel: MissionViewModel by activityViewModels()

    private lateinit var tMap: MapTMap
    private var mapUIVisibility = View.GONE

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMapBinding.inflate(inflater, container, false)
        initBinding()

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initTMap()
        initBottomSheetBehavior()
        initBottomSheetView()
        setBroadcastReceiver()
    }

    override fun alertTMapReady() {
        requestPermissionsLauncher.launch(PERMISSIONS)

        tMap.initListener()
        initAfterTMapReady()
    }

    private fun initAfterTMapReady() {
        initView()
        initNavigateAction()
        observeClickPlace()
        observeClickCurrentLocation()
    }

    private fun initBinding() {
        alarmViewModel.getAlarm()

        binding.lifecycleOwner = viewLifecycleOwner
        binding.alarmViewModel = alarmViewModel
        binding.placeSearchViewModel = placeSearchViewModel
        binding.missionViewModel = missionViewModel
        binding.fragment = this@MapFragment
    }

    private fun initTMap() {
        placeSearchViewModel.tMap?.let {
            tMap = it
            tMap.setHandler(this)
            initAfterTMapReady()
        } ?: run {
            tMap = MapTMap(requireActivity(), this)
            tMap.init()
            placeSearchViewModel.tMap = tMap
        }

        binding.layoutContainer.addView(tMap.tMapView)
    }

    private fun initView() {
        binding.layoutCompass.setOnClickListener {
            tMap.tMapView.isCompassMode = tMap.tMapView.isCompassMode.not()
        }

        binding.layoutCurrent.setOnClickListener {
            requestPermissionsLauncher.launch(PERMISSIONS)

            tMap.isTracking = true
            tMap.tMapView.setCenterPoint(
                placeSearchViewModel.currentLocation.latitude,
                placeSearchViewModel.currentLocation.longitude,
                true
            )
            tMap.addMarker(
                Marker.PERSON_MARKER,
                Marker.PERSON_MARKER_IMG,
                TMapPoint(
                    placeSearchViewModel.currentLocation.latitude,
                    placeSearchViewModel.currentLocation.longitude
                )
            )
        }

    }

    private fun initNavigateAction() {
        binding.textViewSearch.setOnClickListener {
            findNavController().navigate(R.id.action_mapFragment_to_placeSearchFragment)
        }

        binding.homePanel.viewPanelStart.setOnClickListener {
            findNavController().apply {
                setGraph(R.navigation.route_nav_graph)
                navigate(
                    RouteNavGraphDirections.actionGlobalRouteFragment()
                        .setStart(placeSearchViewModel.panelInfo)
                )
            }
        }

        binding.homePanel.viewPanelEnd.setOnClickListener {
            findNavController().apply {
                setGraph(R.navigation.route_nav_graph)
                navigate(
                    RouteNavGraphDirections.actionGlobalRouteFragment()
                        .setEnd(placeSearchViewModel.panelInfo)
                )
            }
        }
    }

    private fun initBottomSheetBehavior() {
        val displaySize = requireContext().getScreenSize()
        val displayHeight = displaySize.height

        binding.layoutHomeBottomSheet.maxHeight = (displayHeight * 0.8).toInt()

        val behavior = BottomSheetBehavior.from(binding.layoutHomeBottomSheet)

        alarmViewModel.alarmStatus.asLiveData().observe(viewLifecycleOwner) { alarmStatus ->
            when (alarmStatus) {
                AlarmStatus.NON_EXIST -> {
                    behavior.isDraggable = false
                }
                AlarmStatus.EXIST -> {
                    behavior.isDraggable = true
                }
                AlarmStatus.MISSION -> {
                    behavior.isDraggable = true
                }
            }
        }

        behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                when (newState) {
                    BottomSheetBehavior.STATE_EXPANDED -> {
                        binding.homeBottomSheet.layoutStateExpanded.root.visibility = View.VISIBLE
                        binding.homeBottomSheet.textViewAlarmState.visibility = View.GONE
                        binding.homeBottomSheet.homeBottomSheetDragHandle.visibility = View.GONE
                    }
                    BottomSheetBehavior.STATE_COLLAPSED -> {
                        binding.homeBottomSheet.layoutStateExpanded.root.visibility = View.GONE
                        binding.homeBottomSheet.textViewAlarmState.visibility = View.VISIBLE
                        binding.homeBottomSheet.homeBottomSheetDragHandle.visibility = View.VISIBLE
                    }
                    BottomSheetBehavior.STATE_HALF_EXPANDED -> Unit
                    BottomSheetBehavior.STATE_DRAGGING -> Unit
                    BottomSheetBehavior.STATE_SETTLING -> Unit
                    BottomSheetBehavior.STATE_HIDDEN -> Unit
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) = Unit
        })
    }

    private fun initBottomSheetView() {
        binding.homeBottomSheet.layoutStateExpanded.buttonAlarmTurnOff.setOnClickListener {
            val behavior = BottomSheetBehavior.from(binding.layoutHomeBottomSheet)

            behavior.state = BottomSheetBehavior.STATE_COLLAPSED
            alarmViewModel.deleteAlarm()
        }
    }

    private fun observeClickPlace() {
        placeSearchViewModel.clickPlaceUseCaseItem.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let { clickPlace ->
                val clickTMapPoint = TMapPoint(clickPlace.centerLat, clickPlace.centerLon)

                tMap.isTracking = false
                tMap.tMapView.setCenterPoint(
                    clickTMapPoint.latitude,
                    clickTMapPoint.longitude,
                    true
                )
                tMap.addMarker(Marker.PLACE_MARKER, Marker.PLACE_MARKER_IMG, clickTMapPoint)
                setPanel(clickTMapPoint, true)
            }
        }
    }

    private fun observeClickCurrentLocation() {
        lifecycleScope.launch {
            placeSearchViewModel.clickCurrentLocation
                .flowWithLifecycle(viewLifecycleOwner.lifecycle)
                .collect {
                    val currentLocation = placeSearchViewModel.currentLocation
                    val currentTMapPoint =
                        TMapPoint(currentLocation.latitude, currentLocation.longitude)

                    tMap.isTracking = false
                    tMap.tMapView.setCenterPoint(
                        currentTMapPoint.latitude,
                        currentTMapPoint.longitude
                    )
                    tMap.addMarker(Marker.PLACE_MARKER, Marker.PLACE_MARKER_IMG, currentTMapPoint)
                    setPanel(currentTMapPoint, false)
                }
        }
    }

    override fun setPanel(tMapPoint: TMapPoint, isClickedFromPlaceSearch: Boolean) {
        placeSearchViewModel.getGeoLocationInfo(
            tMapPoint.latitude,
            tMapPoint.longitude,
            isClickedFromPlaceSearch
        )
    }

    override fun setOnLocationChangeListener(location: android.location.Location) {
        placeSearchViewModel.currentLocation = Location(location.latitude, location.longitude)
    }

    override fun setOnDisableScrollWIthZoomLevelListener() {
        if (binding.homePanel.layoutPanel.visibility == View.VISIBLE) {
            binding.homePanel.layoutPanel.visibility = View.GONE
            tMap.tMapView.removeTMapMarkerItem(Marker.PLACE_MARKER)
        } else {
            setViewVisibility()
            mapUIVisibility = mapUIVisibility.xor(View.GONE)
        }
    }

    private fun setViewVisibility() {
        with(binding) {
            textViewSearch.visibility = mapUIVisibility
            layoutCompass.visibility = mapUIVisibility
            layoutCurrent.visibility = mapUIVisibility
        }
    }

    private fun listenButtonClick() {
        binding.homeBottomSheet.layoutStateExpanded.buttonAlarmTurnOff.setOnClickListener {
            alarmViewModel.deleteAlarm()
            turnOffSoundService()
            val behavior = BottomSheetBehavior.from(binding.layoutHomeBottomSheet)
            behavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }
    }

    private fun turnOffSoundService() {
        val intent = Intent(context, SoundService::class.java)
        requireContext().stopService(intent)
    }

    override fun onResume() {
        super.onResume()

        requireActivity().intent.extras?.getInt("ALARM_MAP_CODE")?.let {
            if (it == ALARM_MAP_CODE) {
                showBottomSheet()
            }
        }
    }

    private fun showBottomSheet() {
        val behavior = BottomSheetBehavior.from(binding.layoutHomeBottomSheet)
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    override fun onDestroyView() {
        placeSearchViewModel.setPanelVisibility(binding.homePanel.layoutPanel.visibility)
        binding.layoutContainer.removeView(tMap.tMapView)
        _binding = null

        super.onDestroyView()
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.entries.any { it.value }) {
            tMap.setTrackingMode()
        }
    }

    fun setMissionStart() {
        setNonDozeMode()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (PackageManager.PERMISSION_GRANTED != ContextCompat.checkSelfPermission(
                    requireActivity(),
                    permission.ACCESS_BACKGROUND_LOCATION
                )
            ) {
                setPermissionDialog(requireActivity())
            }
        }
        findNavController().navigate(R.id.action_mapFragment_to_missionFragment)
    }

    @SuppressLint("BatteryLife")
    fun setNonDozeMode() {
        val pm = requireActivity().getSystemService(Context.POWER_SERVICE) as PowerManager
        val packageName = requireActivity().packageName

        if (pm.isIgnoringBatteryOptimizations(packageName).not()) {
            val intent = Intent()
            intent.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun setPermissionDialog(context: Context) {
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

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun setBackgroundPermission() {
        ActivityCompat.requestPermissions(
            requireActivity(),
            arrayOf(
                permission.ACCESS_BACKGROUND_LOCATION,
            ), 2
        )
    }

    private fun setBroadcastReceiver() {
        val intentFilter = IntentFilter().apply {
            addAction(MissionService.MISSION_STATUS)
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if(intent?.getBooleanExtra(MissionService.MISSION_STATUS, false) == true) {
                    missionViewModel.missionStatus.value = MissionStatus.ONGOING
                    alarmViewModel.alarmStatus.value = AlarmStatus.MISSION
                }
            }
        }

        requireActivity().registerReceiver(receiver, intentFilter)
    }

    companion object {
        private val PERMISSIONS =
            arrayOf(permission.ACCESS_FINE_LOCATION, permission.ACCESS_COARSE_LOCATION)
    }
}