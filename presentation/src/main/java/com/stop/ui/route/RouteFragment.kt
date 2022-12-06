package com.stop.ui.route

import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import androidx.navigation.findNavController
import com.stop.R
import com.stop.databinding.FragmentRouteBinding
import com.stop.domain.model.route.tmap.custom.Itinerary
import com.stop.domain.model.route.tmap.custom.TransportRoute
import com.stop.model.ErrorType
import com.stop.model.route.Coordinate
import com.stop.model.route.Place
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class RouteFragment : Fragment() {

    private var _binding: FragmentRouteBinding? = null
    private val binding: FragmentRouteBinding
        get() = _binding!!

    private val viewModel: RouteViewModel by activityViewModels()

    private lateinit var adapter: RouteAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRouteBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setBinding()
        setRecyclerView()
        setStartAndDestinationText()
        setObserve()
    }

    private fun setBinding() {
        binding.lifecycleOwner = viewLifecycleOwner
        binding.viewModel = viewModel
    }

    private fun setRecyclerView() {
        adapter = RouteAdapter(object: OnItineraryClickListener {
            override fun onItineraryClick(itinerary: Itinerary) {
                /**
                 * UI가 ViewModel을 직접 호출하면 안 되지만, 테스트를 위해 막차 조회 함수를 호출했습니다.
                 * 여기서 UI가 ViewModel을 직접 호출하지 않으면서 막차 조회 함수를 호출할 수 있을까요?
                 */
                itinerary.routes.forEach {
                    if (it is TransportRoute) {
                        Log.d("itinerary", it.start.name)
                        it.lines.forEach { it2 ->
                            Log.d("itinerary", it2.toString())
                        }
                    }
                }
                Log.d("itinerary", itinerary.toString())
                viewModel.tempItinerary = itinerary
                viewModel.calculateLastTransportTime(itinerary)
            }
        })
        binding.recyclerviewRoute.adapter = adapter
    }

    private fun setObserve() {
        viewModel.routeResponse.observe(viewLifecycleOwner) {
            if (it == null) {
                return@observe
            }
            adapter.submitList(it)
        }

        viewModel.errorMessage.observe(viewLifecycleOwner) {
            it.getContentIfNotHandled()?.let { errorType ->
                val message = when(errorType) {
                    ErrorType.NO_START -> getString(R.string.no_start_input)
                    ErrorType.NO_END -> getString(R.string.no_end_input)
                }

                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            }
        }

        viewModel.lastTimeResponse.observe(viewLifecycleOwner) {
            it.getContentIfNotHandled()?.let { response ->
                viewModel.tempLastTime = response.toMutableList()
                Log.d("itinerary", response.toMutableList().toString())
                binding.root.findNavController().navigate(R.id.action_routeFragment_to_routeDetailFragment)
            }
        }
    }

    private fun setStartAndDestinationText() {
        viewModel.setOrigin(Place(ORIGIN_NAME, Coordinate(ORIGIN_Y, ORIGIN_X)))
        viewModel.setDestination(Place(DESTINATION_NAME, Coordinate(DESTINATION_Y, DESTINATION_X)))
        viewModel.getRoute()
    }

    override fun onDestroyView() {
        _binding = null

        super.onDestroyView()
    }

    companion object {
        private const val ORIGIN_NAME = "이앤씨벤쳐드림타워3차"
        private const val ORIGIN_X = "126.893820"
        private const val ORIGIN_Y = "37.4865002"

        private const val DESTINATION_NAME = "Naver1784"
        private const val DESTINATION_X = "127.105037"
        private const val DESTINATION_Y = "37.3584879"
    }
}