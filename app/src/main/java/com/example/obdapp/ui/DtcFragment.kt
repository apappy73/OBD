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
import com.example.obdapp.R
import com.example.obdapp.databinding.FragmentDtcBinding
import com.example.obdapp.obd.DtcKind
import com.example.obdapp.service.ObdBleService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class DtcFragment : Fragment() {

    private var _binding: FragmentDtcBinding? = null
    private val binding get() = _binding!!
    private val vm: ObdViewModel by activityViewModels()
    private val adapter = DtcAdapter()
    private val kvAdapter = KeyValueAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDtcBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recyclerCodes.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerCodes.adapter = adapter

        binding.btnReadStored.setOnClickListener { vm.readDtcs(DtcKind.STORED) }
        binding.btnReadPending.setOnClickListener { vm.readDtcs(DtcKind.PENDING) }
        binding.btnFreezeFrame.setOnClickListener { vm.readFreezeFrame() }
        binding.btnMode06.setOnClickListener { vm.readMode06() }
        binding.btnClear.setOnClickListener { confirmClear() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.state.collect { state ->
                        val connected = state is ObdBleService.ConnState.Connected
                        setButtons(connected && vm.dtcState.value !is ObdViewModel.DtcUiState.Loading)
                        if (!connected) {
                            binding.txtDtcStatus.text = getString(R.string.dtc_not_connected)
                        }
                    }
                }
                launch {
                    vm.dtcState.collect { renderDtcState(it) }
                }
            }
        }
    }

    private fun renderDtcState(state: ObdViewModel.DtcUiState) {
        binding.progressDtc.visibility =
            if (state is ObdViewModel.DtcUiState.Loading) View.VISIBLE else View.GONE
        setButtons(vm.isConnected && state !is ObdViewModel.DtcUiState.Loading)
        when (state) {
            is ObdViewModel.DtcUiState.Idle ->
                binding.txtDtcStatus.text = getString(R.string.dtc_idle)
            is ObdViewModel.DtcUiState.Loading ->
                binding.txtDtcStatus.text = getString(R.string.dtc_working)
            is ObdViewModel.DtcUiState.Result -> {
                val kindLabel = when (state.kind) {
                    DtcKind.STORED -> getString(R.string.dtc_kind_stored)
                    DtcKind.PENDING -> getString(R.string.dtc_kind_pending)
                    DtcKind.PERMANENT -> getString(R.string.dtc_kind_permanent)
                }
                binding.txtDtcStatus.text = if (state.codes.isEmpty()) {
                    getString(R.string.dtc_none, kindLabel)
                } else {
                    getString(R.string.dtc_found, state.codes.size, kindLabel)
                }
                binding.recyclerCodes.adapter = adapter
                adapter.submit(state.codes)
            }
            is ObdViewModel.DtcUiState.Detail -> {
                binding.txtDtcStatus.text = state.status
                binding.recyclerCodes.adapter = kvAdapter
                kvAdapter.submitRows(state.rows)
            }
            is ObdViewModel.DtcUiState.Cleared -> {
                binding.txtDtcStatus.text = state.message
                binding.recyclerCodes.adapter = adapter
                adapter.submit(emptyList())
            }
            is ObdViewModel.DtcUiState.Error ->
                binding.txtDtcStatus.text = getString(R.string.dtc_error, state.message)
        }
    }

    private fun setButtons(enabled: Boolean) {
        binding.btnReadStored.isEnabled = enabled
        binding.btnReadPending.isEnabled = enabled
        binding.btnFreezeFrame.isEnabled = enabled
        binding.btnMode06.isEnabled = enabled
        binding.btnClear.isEnabled = enabled
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dtc_clear_title)
            .setMessage(R.string.dtc_clear_message)
            .setPositiveButton(R.string.dtc_clear_confirm) { _, _ -> vm.clearDtcs() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
