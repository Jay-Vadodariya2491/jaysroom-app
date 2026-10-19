package com.jayu.jaysroom

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import java.io.PrintWriter
import java.io.StringWriter

private const val CRASH_PREFS = "jaysroom_crash_prefs"
private const val CRASH_KEY = "last_crash"

/**
 * Home screen: automatically scans the local network for ESP32 boards
 * advertising themselves (see DeviceDiscovery) and lists them - tap one
 * to open its dashboard. No IP address entry, same idea as the WLED app.
 *
 * Each card also gets its own room switch (see DeviceController): it
 * mirrors whether anything in that room is currently on, turning it off
 * shuts everything off, and turning it on applies the "on" scene (fan +
 * profile light at 80%). The header has a matching Home switch next to
 * "Amrut" (the home name - each discovered board's own room name, e.g.
 * "Jay's room", is what shows on its card) that does the same thing but
 * across every discovered room at once.
 *
 * Also installs a last-resort crash logger: if something throws and
 * crashes the app, the stack trace is saved to disk, and the NEXT time
 * the app opens, it's shown in a dialog (with a Copy button) - a way to
 * get the exact exception without needing adb/USB debugging.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var discovery: DeviceDiscovery
    private lateinit var adapter: DeviceAdapter
    private lateinit var emptyState: View
    private lateinit var emptyText: TextView
    private lateinit var homeSwitch: MaterialSwitch

    // key (host:port) -> device, LinkedHashMap keeps a stable, insertion order
    private val devices = LinkedHashMap<String, EspDevice>()

    // key (host:port) -> live WebSocket connection to that device
    private val controllers = HashMap<String, DeviceController>()

    // key (host:port) -> is anything in that room currently on
    private val anyOnStates = HashMap<String, Boolean>()

    private var errorMessage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        installCrashLogger()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        emptyState = findViewById(R.id.emptyState)
        emptyText = findViewById(R.id.emptyText)
        homeSwitch = findViewById(R.id.homeSwitch)
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
            },
            onError = { message ->
                errorMessage = message
                refreshList()
            }
        )

        showLastCrashIfAny()
    }

    override fun onStart() {
        super.onStart()
        errorMessage = null
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
        emptyText.text = errorMessage ?: getString(R.string.scanning)

        // Home switch mirrors whether ANY discovered room is currently on.
        // Same guarded-rebind pattern as DeviceAdapter uses for the room
        // switches, so refreshing state doesn't itself re-fire the listener.
        val anyRoomOn = anyOnStates.values.any { it }
        homeSwitch.setOnCheckedChangeListener(null)
        homeSwitch.isChecked = anyRoomOn
        homeSwitch.setOnCheckedChangeListener { _, isChecked -> handleHomeToggle(isChecked) }
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
        refreshList()
    }

    /** Home switch toggled: apply the same on/off action to every discovered room. */
    private fun handleHomeToggle(turnOn: Boolean) {
        controllers.values.forEach { controller ->
            if (turnOn) controller.applyOnScene() else controller.turnAllOff()
        }
        // Optimistic update for every room - the real broadcasts will confirm
        // (or correct) this a moment later.
        devices.keys.forEach { key -> anyOnStates[key] = turnOn }
        refreshList()
    }

    private fun openDevice(device: EspDevice) {
        val intent = Intent(this, WebViewActivity::class.java).apply {
            putExtra(WebViewActivity.EXTRA_URL, device.url)
            putExtra(WebViewActivity.EXTRA_TITLE, device.name)
        }
        startActivity(intent)
    }

    // --- Crash logging (no adb needed) -------------------------------------

    private fun installCrashLogger() {
        val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                // commit() (not apply()) - this process is about to die, the
                // write needs to actually land before that happens.
                getSharedPreferences(CRASH_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(CRASH_KEY, sw.toString())
                    .commit()
            } catch (e: Exception) {
                // best-effort only - never let the logger itself crash harder
            }
            originalHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun showLastCrashIfAny() {
        val prefs = getSharedPreferences(CRASH_PREFS, Context.MODE_PRIVATE)
        val trace = prefs.getString(CRASH_KEY, null) ?: return
        prefs.edit().remove(CRASH_KEY).apply()

        AlertDialog.Builder(this)
            .setTitle("Last crash")
            .setMessage(trace)
            .setPositiveButton("OK", null)
            .setNeutralButton("Copy") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Crash log", trace))
            }
            .show()
    }
}
