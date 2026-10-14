package com.jayu.jaysroom

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Home screen: automatically scans the local network for ESP32 boards
 * advertising themselves (see DeviceDiscovery) and lists them - tap one
 * to open its dashboard. No IP address entry, same idea as the WLED app.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var discovery: DeviceDiscovery
    private lateinit var adapter: DeviceAdapter
    private lateinit var emptyText: TextView

    // key (host:port) -> device, LinkedHashMap keeps a stable, insertion order
    private val devices = LinkedHashMap<String, EspDevice>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        emptyText = findViewById(R.id.emptyText)
        val recyclerView = findViewById<RecyclerView>(R.id.deviceList)

        adapter = DeviceAdapter { device -> openDevice(device) }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        discovery = DeviceDiscovery(
            context = this,
            onDeviceFound = { device ->
                devices[device.key] = device
                refreshList()
            },
            onDeviceLost = { lostName ->
                devices.entries.removeAll { it.value.name == lostName }
                refreshList()
            }
        )
    }

    override fun onStart() {
        super.onStart()
        discovery.start()
    }

    override fun onStop() {
        super.onStop()
        discovery.stop()
    }

    private fun refreshList() {
        adapter.submitList(devices.values.toList())
        emptyText.visibility = if (devices.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openDevice(device: EspDevice) {
        val intent = Intent(this, WebViewActivity::class.java).apply {
            putExtra(WebViewActivity.EXTRA_URL, device.url)
            putExtra(WebViewActivity.EXTRA_TITLE, device.name)
        }
        startActivity(intent)
    }
}
