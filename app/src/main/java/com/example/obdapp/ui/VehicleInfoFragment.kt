package com.example.obdapp.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.obdapp.databinding.FragmentVehicleInfoBinding
import com.example.obdapp.service.ObdBleService
import kotlinx.coroutines.launch

class VehicleInfoFragment : Fragment() {

    private var _binding: FragmentVehicleInfoBinding? = null
    private val binding get() = _binding!!
    private val vm: ObdViewModel by activityViewModels()
    private val adapter = KeyValueAdapter()
    private var autoRefreshed = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVehicleInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recyclerInfo.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerInfo.adapter = adapter
        binding.btnRefreshInfo.setOnClickListener { vm.refreshVehicleInfo() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.vehicleInfo.collect { adapter.submit(it) }
                }
                launch {
                    vm.vehicleInfoLoading.collect { loading ->
                        binding.progressInfo.visibility = if (loading) View.VISIBLE else View.GONE
                        binding.btnRefreshInfo.isEnabled = vm.isConnected && !loading
                    }
                }
                launch {
                    vm.state.collect { state ->
                        val connected = state is ObdBleService.ConnState.Connected
                        binding.btnRefreshInfo.isEnabled = connected
                        // Fetch once per process on first successful connection.
                        if (connected && !autoRefreshed) {
                            autoRefreshed = true
                            vm.refreshVehicleInfo()
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
