package com.jayu.jaysroom

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch

class DeviceAdapter(
    private val onClick: (EspDevice) -> Unit,
    private val onToggle: (EspDevice, Boolean) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

    private var items: List<EspDevice> = emptyList()

    // device.key -> is anything in that room currently on (drives the switch position)
    private var anyOnStates: Map<String, Boolean> = emptyMap()

    fun submitList(newItems: List<EspDevice>, anyOnStates: Map<String, Boolean>) {
        items = newItems
        this.anyOnStates = anyOnStates
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val device = items[position]
        holder.bind(device, anyOnStates[device.key] ?: false, onClick, onToggle)
    }

    override fun getItemCount(): Int = items.size

    class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameView: TextView = itemView.findViewById(R.id.deviceName)
        private val hostView: TextView = itemView.findViewById(R.id.deviceHost)
        private val switchView: MaterialSwitch = itemView.findViewById(R.id.deviceSwitch)

        fun bind(
            device: EspDevice,
            anyOn: Boolean,
            onClick: (EspDevice) -> Unit,
            onToggle: (EspDevice, Boolean) -> Unit
        ) {
            nameView.text = device.name
            hostView.text = device.host
            itemView.setOnClickListener { onClick(device) }

            // Clear the listener before setting isChecked programmatically, or this
            // state refresh would itself fire onToggle and re-send the command.
            switchView.setOnCheckedChangeListener(null)
            switchView.isChecked = anyOn
            switchView.setOnCheckedChangeListener { _, isChecked -> onToggle(device, isChecked) }
        }
    }
}
