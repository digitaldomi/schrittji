package dev.digitaldomi.schrittji

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import dev.digitaldomi.schrittji.databinding.ItemStepRecordBinding
import dev.digitaldomi.schrittji.health.HealthConnectStepRecordEntry
import java.time.format.DateTimeFormatter

class StepRecordAdapter(context: Context) : BaseAdapter() {
    private val inflater = LayoutInflater.from(context)
    private val timeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d HH:mm")
    private val records = mutableListOf<HealthConnectStepRecordEntry>()

    fun submit(items: List<HealthConnectStepRecordEntry>) {
        records.clear()
        records.addAll(items)
        notifyDataSetChanged()
    }

    override fun getCount(): Int = records.size

    override fun getItem(position: Int): HealthConnectStepRecordEntry = records[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val binding = if (convertView == null) {
            ItemStepRecordBinding.inflate(inflater, parent, false)
        } else {
            ItemStepRecordBinding.bind(convertView)
        }

        val item = getItem(position)
        binding.textPrimary.text =
            "${item.count.formatThousands()} steps  |  ${item.start.format(timeFormatter)} - ${item.end.format(timeFormatter)}"
        binding.textSecondary.text = buildString {
            append("Source: ")
            when {
                item.isFromSchrittji -> append("This app")
                item.sourcePackage.isBlank() -> append("Unknown")
                else -> append(item.sourcePackage)
            }
        }
        return binding.root
    }
}

private fun Long.formatThousands(): String = "%,d".format(this)
