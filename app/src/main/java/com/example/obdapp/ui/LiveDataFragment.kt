package com.example.obdapp.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.example.obdapp.R
import com.example.obdapp.databinding.FragmentLiveDataBinding
import com.example.obdapp.obd.PidCatalog
import com.example.obdapp.obd.PidDef
import com.example.obdapp.obd.PidSample
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import kotlinx.coroutines.launch

/**
 * Live data: one charted PID (dropdown) + a grid of all polled values.
 * History is kept per PID in memory so switching the charted PID redraws
 * instantly; recorded samples are persisted by the service.
 */
class LiveDataFragment : Fragment() {

    private var _binding: FragmentLiveDataBinding? = null
    private val binding get() = _binding!!
    private val vm: ObdViewModel by activityViewModels()

    private val valueAdapter = PidValueAdapter()
    private val history = HashMap<String, ArrayDeque<PidSample>>()
    private val firstTs = HashMap<String, Long>()
    private var chartPid: String? = null
    private var chartablePids: List<PidDef> = emptyList()
    private var spinnerReady = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLiveDataBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupChart()
        binding.recyclerValues.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.recyclerValues.adapter = valueAdapter

        binding.switchRecord.isChecked = vm.isRecording()
        binding.switchRecord.setOnCheckedChangeListener { _, checked -> vm.setRecording(checked) }

        binding.pidSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!spinnerReady) return
                val def = chartablePids.getOrNull(position) ?: return
                if (def.key != chartPid) {
                    chartPid = def.key
                    rebuildChart()
                    pushSelection()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.supportedPids.collect { pids -> onSupportedPids(pids) }
                }
                launch {
                    vm.samples.collect { onSample(it) }
                }
                launch {
                    vm.state.collect { state ->
                        val connected = state is com.example.obdapp.service.ObdBleService.ConnState.Connected
                        binding.txtEmpty.visibility =
                            if (connected || history.isNotEmpty()) View.GONE else View.VISIBLE
                        if (!connected) valueAdapter.clear()
                    }
                }
            }
        }
    }

    private fun onSupportedPids(pids: List<PidDef>) {
        chartablePids = pids.filter { it.chartable }
        spinnerReady = false
        binding.pidSpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            chartablePids.map { "${it.label} (${it.unit})" }
        )
        if (chartablePids.isNotEmpty()) {
            val preferred = chartablePids.indexOfFirst { it.key == (chartPid ?: "0C") }.coerceAtLeast(0)
            binding.pidSpinner.setSelection(preferred)
            chartPid = chartablePids[preferred].key
            rebuildChart()
        }
        spinnerReady = true
        pushSelection()
    }

    /** Poll the default set plus whatever is charted. */
    private fun pushSelection() {
        val supported = vm.supportedPids.value.map { it.key }.toSet()
        val keys = (PidCatalog.defaultSelection.filter { it in supported } + listOfNotNull(chartPid))
            .distinct()
        if (keys.isNotEmpty()) vm.setSelectedPids(keys)
    }

    private fun onSample(sample: PidSample) {
        binding.txtEmpty.visibility = View.GONE
        valueAdapter.update(sample)
        firstTs.putIfAbsent(sample.pidKey, sample.timestamp)
        val deque = history.getOrPut(sample.pidKey) { ArrayDeque() }
        deque.addLast(sample)
        while (deque.size > MAX_HISTORY) deque.removeFirst()

        if (sample.pidKey == chartPid) {
            binding.txtBigValue.text = sample.display
            binding.txtBigUnit.text = sample.unit
            val v = sample.value ?: return
            val data = binding.chart.data ?: return
            val set = data.getDataSetByIndex(0) ?: return
            val x = (sample.timestamp - (firstTs[sample.pidKey] ?: sample.timestamp)) / 1000f
            set.addEntryOrdered(Entry(x, v))
            while (set.entryCount > MAX_HISTORY) set.removeFirst()
            data.notifyDataChanged()
            binding.chart.notifyDataSetChanged()
            binding.chart.setVisibleXRangeMaximum(WINDOW_SECONDS)
            binding.chart.moveViewToX(x)
        }
    }

    private fun setupChart() {
        val chart = binding.chart
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.setNoDataText(getString(R.string.chart_no_data))
        chart.setTouchEnabled(true)
        chart.isDragEnabled = true
        chart.setScaleEnabled(true)
        chart.axisRight.isEnabled = false
        chart.xAxis.position = XAxis.XAxisPosition.BOTTOM
        chart.xAxis.granularity = 1f
        chart.setExtraOffsets(8f, 8f, 8f, 8f)
    }

    private fun rebuildChart() {
        val def = chartPid?.let { PidCatalog.get(it) } ?: return
        binding.txtBigUnit.text = def.unit
        val entries = ArrayList<Entry>()
        val t0 = firstTs[def.key]
        history[def.key]?.forEach { s ->
            val v = s.value ?: return@forEach
            val x = if (t0 != null) (s.timestamp - t0) / 1000f else 0f
            entries.add(Entry(x, v))
        }
        val latest = history[def.key]?.lastOrNull()
        binding.txtBigValue.text = latest?.display ?: "—"

        val set = LineDataSet(entries, def.label).apply {
            color = ContextCompat.getColor(requireContext(), R.color.soul_red)
            lineWidth = 2f
            setDrawCircles(false)
            setDrawValues(false)
            mode = LineDataSet.Mode.LINEAR
        }
        binding.chart.data = LineData(set)
        binding.chart.fitScreen()
        binding.chart.setVisibleXRangeMaximum(WINDOW_SECONDS)
        if (entries.isNotEmpty()) binding.chart.moveViewToX(entries.last().x)
        binding.chart.invalidate()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val MAX_HISTORY = 600
        private const val WINDOW_SECONDS = 60f
    }
}
