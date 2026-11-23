package com.example.obdapp.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanResult
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.obdapp.R
import com.example.obdapp.ble.BleScanner
import com.example.obdapp.databinding.ActivityMainBinding
import com.example.obdapp.service.ObdBleService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val vm: ObdViewModel by viewModels()
    private lateinit var scanner: BleScanner
    private val handler = Handler(Looper.getMainLooper())

    private val liveDataFragment = LiveDataFragment()
    private val dtcFragment = DtcFragment()
    private val vehicleInfoFragment = VehicleInfoFragment()
    private var activeFragment: Fragment = liveDataFragment

    private var scanDialog: AlertDialog? = null
    private var scanAdapter: ScanAdapter? = null
    private val scanItems = ArrayList<String>()
    private val scanAddresses = LinkedHashMap<String, String>() // display -> address
    private var discoverStart = 0 // index where the "Nearby devices" entries begin

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as? ObdBleService.LocalBinder ?: return
            vm.attach(local.service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            vm.detach()
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.all { it }) {
                showScanDialog()
            } else {
                toast(getString(R.string.permissions_required))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // API 35 enforces edge-to-edge: pad toolbar and bottom nav by the system insets.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.updatePadding(top = bars.top)
            binding.bottomNav.updatePadding(bottom = bars.bottom)
            insets
        }

        scanner = BleScanner(this)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.fragmentContainer, vehicleInfoFragment, "info").hide(vehicleInfoFragment)
                .add(R.id.fragmentContainer, dtcFragment, "dtc").hide(dtcFragment)
                .add(R.id.fragmentContainer, liveDataFragment, "live")
                .commit()
        } else {
            val restored = listOf("live", "dtc", "info")
                .mapNotNull { supportFragmentManager.findFragmentByTag(it) }
            activeFragment = restored.firstOrNull { !it.isHidden }
                ?: restored.firstOrNull()
                ?: liveDataFragment
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            val target = when (item.itemId) {
                R.id.nav_live -> supportFragmentManager.findFragmentByTag("live") ?: liveDataFragment
                R.id.nav_dtc -> supportFragmentManager.findFragmentByTag("dtc") ?: dtcFragment
                else -> supportFragmentManager.findFragmentByTag("info") ?: vehicleInfoFragment
            }
            if (target !== activeFragment) {
                supportFragmentManager.beginTransaction()
                    .hide(activeFragment).show(target).commit()
                activeFragment = target
            }
            true
        }

        binding.btnConnect.setOnClickListener {
            if (vm.isConnected) vm.disconnect() else ensurePermissionsAndScan()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { renderState(it) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, ObdBleService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        stopScan()
        scanDialog?.dismiss()
        scanDialog = null
        unbindService(serviceConnection)
        vm.detach()
        super.onStop()
    }

    private fun renderState(state: ObdBleService.ConnState) {
        val (text, connected) = when (state) {
            is ObdBleService.ConnState.Disconnected -> getString(R.string.status_disconnected) to false
            is ObdBleService.ConnState.Connecting ->
                getString(R.string.status_connecting, state.deviceName) to false
            is ObdBleService.ConnState.Initializing -> getString(R.string.status_initializing) to false
            is ObdBleService.ConnState.Connected ->
                getString(R.string.status_connected, state.deviceName, state.protocol) to true
            is ObdBleService.ConnState.Failed ->
                getString(R.string.status_failed, state.message) to false
        }
        binding.statusText.text = text
        binding.btnConnect.setText(if (connected) R.string.action_disconnect else R.string.action_connect)
    }

    // ---- Permissions & scanning ----

    private fun ensurePermissionsAndScan() {
        if (!scanner.isBluetoothEnabled) {
            toast(getString(R.string.bluetooth_off))
            return
        }
        permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
    }

    @SuppressLint("MissingPermission")
    private fun showScanDialog() {
        scanItems.clear()
        scanAddresses.clear()
        // Sectioned list: "Paired devices" first (a known dongle is easiest to spot
        // here, and bonded devices often stop advertising), then "Nearby devices".
        val paired = bondedDisplays()
        if (paired.isNotEmpty()) {
            scanItems.add(getString(R.string.scan_section_paired))
            scanItems.addAll(paired)
        }
        scanItems.add(getString(R.string.scan_section_nearby))
        discoverStart = scanItems.size
        val listView = ListView(this)
        scanAdapter = ScanAdapter(this, scanItems)
        listView.adapter = scanAdapter

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.scan_title)
            .setView(listView)
            .setNegativeButton(R.string.action_cancel) { d, _ -> d.dismiss() }
            .setNeutralButton(R.string.action_manual_address, null)
            .setOnDismissListener { stopScan() }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                dialog.dismiss()
                showManualAddressDialog()
            }
        }
        listView.setOnItemClickListener { _, _, position, _ ->
            val display = scanItems[position]
            val address = scanAddresses[display] ?: return@setOnItemClickListener
            dialog.dismiss()
            vm.connect(address, display.substringBeforeLast(" ("))
        }

        scanDialog = dialog
        dialog.show()

        val ok = scanner.start(
            onResult = { result -> handler.post { addScanResult(result) } },
            onError = { msg -> handler.post { toast(msg) } }
        )
        if (!ok) dialog.dismiss()
        handler.postDelayed({ stopScan() }, SCAN_TIMEOUT_MS)
    }

    /**
     * Paired (bonded) devices as display strings, alphabetically. Their addresses
     * are registered in [scanAddresses] so discovered duplicates are skipped.
     */
    @SuppressLint("MissingPermission")
    private fun bondedDisplays(): List<String> {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return adapter.bondedDevices.orEmpty()
            .sortedBy { it.name?.lowercase() ?: "\uFFFF" }
            .mapNotNull { d ->
                val address = d.address ?: return@mapNotNull null
                if (scanAddresses.containsValue(address)) return@mapNotNull null
                val display = "${d.name ?: d.alias ?: "Unknown"} ($address)"
                scanAddresses[display] = address
                display
            }
    }

    @SuppressLint("MissingPermission")
    private fun addScanResult(result: ScanResult) {
        val address = result.device.address
        if (scanAddresses.containsValue(address)) return
        // device.name is the friendly (possibly user-renamed) name; fall back to the
        // advertisement name and the local alias before giving up.
        val name = result.device.name ?: result.scanRecord?.deviceName
            ?: result.device.alias ?: "Unknown"
        val display = "$name ($address)"
        scanAddresses[display] = address
        // Likely OBD adapters sorted up, but never above the "Nearby" section start.
        val looksLikeObd = OBD_NAME_HINTS.any { name.contains(it, ignoreCase = true) }
        if (looksLikeObd) {
            var insertAt = discoverStart
            while (insertAt < scanItems.size &&
                OBD_NAME_HINTS.any { scanItems[insertAt].contains(it, ignoreCase = true) }
            ) insertAt++
            scanItems.add(insertAt, display)
        } else {
            scanItems.add(display)
        }
        scanAdapter?.notifyDataSetChanged()
    }

    private fun showManualAddressDialog() {
        val input = EditText(this).apply {
            hint = "AA:BB:CC:DD:EE:FF"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_manual_address)
            .setView(input)
            .setPositiveButton(R.string.action_connect) { _, _ ->
                val address = input.text.toString().trim().uppercase()
                if (address.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}"))) {
                    vm.connect(address, "Adapter")
                } else {
                    toast(getString(R.string.invalid_address))
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun stopScan() {
        handler.removeCallbacksAndMessages(null)
        scanner.stop()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /**
     * Two row types: section headers (strings absent from [scanAddresses]) and
     * device rows. Headers are non-clickable and styled by item_scan_header.
     */
    private inner class ScanAdapter(context: Context, items: MutableList<String>) :
        ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, items) {

        override fun getViewTypeCount(): Int = 2

        override fun getItemViewType(position: Int): Int =
            if (getItem(position) in scanAddresses) VIEW_DEVICE else VIEW_HEADER

        override fun isEnabled(position: Int): Boolean =
            getItemViewType(position) == VIEW_DEVICE

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            if (getItemViewType(position) != VIEW_HEADER) {
                return super.getView(position, convertView, parent)
            }
            val view = convertView ?: LayoutInflater.from(context)
                .inflate(R.layout.item_scan_header, parent, false)
            (view as TextView).text = getItem(position)
            return view
        }
    }

    companion object {
        private const val SCAN_TIMEOUT_MS = 12_000L
        private const val VIEW_DEVICE = 0
        private const val VIEW_HEADER = 1
        private val OBD_NAME_HINTS = listOf(
            "obd", "elm", "vlink", "veepeak", "konnwei", "vgate", "icar", "lelink", "v-link", "obdlink"
        )
    }
}
