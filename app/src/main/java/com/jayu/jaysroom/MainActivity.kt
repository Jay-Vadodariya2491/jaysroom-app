package com.jayu.jaysroom

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Home screen: automatically scans the local network for ESP32 boards
 * advertising themselves (see DeviceDiscovery) and lists them - tap one
 * to open its dashboard. No IP address entry, same idea as the WLED app.
 *
 * Each card also gets its own master switch (see DeviceController): it
 * mirrors whether anything in that room is currently on, turning it off
 * shuts everything off, and turning it on applies the "on" scene (fan +
 * profile light at 80%).
 *
 * Deliberately does NOT try to gate discovery on ConnectivityManager's idea
 * of "connected to Wi-Fi": when this phone is itself hosting the hotspot the
 * board joins, the phone's own active network is usually reported as mobile
 * data (Wi-Fi is busy being the access point, not a client), so any check
 * like that would misfire exactly when the hotspot turns on. Discovery just
 * keeps running continuously instead, which is the simpler and more correct
 * behavior for a phone that's either on a shared router or hosting its own
 * hotspot.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var discovery: DeviceDiscovery
    private lateinit var adapter: DeviceAdapter
    private lateinit var emptyState: View

    // key (host:port) -> device, LinkedHashMap keeps a stable, insertion order
    private val devices = LinkedHashMap<String, EspDevice>()

    // key (host:port) -> live WebSocket connection to that device
    private val controllers = HashMap<String, DeviceController>()

    // key (host:port) -> is anything in that room currently on
    private val anyOnStates = HashMap<String, Boolean>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        emptyState = findViewById(R.id.emptyState)
        val recyclerView = findViewById<RecyclerView>(R.id.deviceList)

        adapter = DeviceAdapter(
            onClick = { device -> openDevice(device) },
            onToggle = { device, turnOn -> handleToggle(device, turnOn) }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        discovery = DeviceDiscovery(
            context = this,
            onDeviceFound = { device ->
                devices[device.key] = device
                if (!controllers.containsKey(device.key)) {
                    val controller = DeviceController(device) { anyOn ->
                        anyOnStates[device.key] = anyOn
                        refreshList()
                    }
                    controllers[device.key] = controller
                    controller.connect()
                }
                refreshList()
            },
            onDeviceLost = { lostNsdServiceName ->
                val lostKey = devices.entries
                    .firstOrNull { it.value.nsdServiceName == lostNsdServiceName }?.key
                devices.entries.removeAll { it.value.nsdServiceName == lostNsdServiceName }
                lostKey?.let {
                    controllers.remove(it)?.disconnect()
                    anyOnStates.remove(it)
                }
                refreshList()
            }
        )
    }

    override fun onStart() {
        super.onStart()
        discovery.start()
        refreshList()
    }

    override fun onStop() {
        super.onStop()
        discovery.stop()
        controllers.values.forEach { it.disconnect() }
        controllers.clear()
    }

    private fun refreshList() {
        adapter.submitList(devices.values.toList(), anyOnStates)
        emptyState.visibility = if (devices.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun handleToggle(device: EspDevice, turnOn: Boolean) {
        val controller = controllers[device.key] ?: return
        if (turnOn) {
            controller.applyOnScene()
        } else {
            controller.turnAllOff()
        }
        // Optimistic update - the real broadcast from the board will confirm
        // (or correct) this a moment later.
        anyOnStates[device.key] = turnOn
    }

    private fun openDevice(device: EspDevice) {
        val intent = Intent(this, WebViewActivity::class.java).apply {
            putExtra(WebViewActivity.EXTRA_URL, device.url)
            putExtra(WebViewActivity.EXTRA_TITLE, device.name)
        }
        startActivity(intent)
    }
}
