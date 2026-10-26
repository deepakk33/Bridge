package com.bridge.notifications

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Per-app notification filter screen (iter-6).
 *
 * Lists apps that have posted a notification since Bridge gained notification
 * access (the seen-apps set), each with an on/off switch, plus a master-mute
 * switch in the header. Plain programmatic AppCompat Views + RecyclerView to
 * match the existing no-Compose app UI. Icons and labels are resolved off the
 * main thread and cached per package.
 */
class NotificationFilterActivity : AppCompatActivity() {

    private val store by lazy { NotificationFilterStore.init(applicationContext) }

    private lateinit var emptyHint: TextView
    private lateinit var recycler: RecyclerView
    private val adapter = AppAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "Notification Filter"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }

        val masterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
        }
        val masterLabel = TextView(this).apply {
            // "Mute all notifications" read as if it silenced the phone itself.
            text = "Pause mirroring to the Mac"
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        val masterSwitch = SwitchCompat(this).apply {
            isChecked = store.isMuted()
            setOnCheckedChangeListener { _, checked ->
                store.setMasterMute(checked)
                adapter.setMuted(checked)
            }
        }
        masterRow.addView(masterLabel)
        masterRow.addView(masterSwitch)

        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(1))
            setBackgroundColor(Color.parseColor("#33888888"))
        }

        emptyHint = TextView(this).apply {
            text = "No apps yet.\n\n" +
                "An app is added to this list the first time it sends you a " +
                "notification, so use your phone for a while and they will show up.\n\n" +
                "Nothing is sent to your Mac until you switch an app on here."
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(48), dp(32), dp(32))
            visibility = View.GONE
        }

        recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@NotificationFilterActivity)
            adapter = this@NotificationFilterActivity.adapter
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }

        root.addView(masterRow)
        root.addView(divider)
        root.addView(emptyHint)
        root.addView(recycler)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        loadApps()
    }

    private fun loadApps() {
        adapter.setMuted(store.isMuted())
        val seen = store.state.value.seen.toList()
        if (seen.isEmpty()) {
            emptyHint.visibility = View.VISIBLE
            recycler.visibility = View.GONE
            adapter.submit(emptyList())
            return
        }
        emptyHint.visibility = View.GONE
        recycler.visibility = View.VISIBLE

        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                val pm = packageManager
                seen.map { pkg ->
                    val label = try {
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    } catch (_: Exception) {
                        pkg
                    }
                    val icon: Drawable? = try {
                        pm.getApplicationIcon(pkg)
                    } catch (_: Exception) {
                        null
                    }
                    AppRow(pkg, label, icon, store.isAllowed(pkg))
                }.sortedBy { it.label.lowercase() }
            }
            adapter.submit(rows)
        }
    }

    private data class AppRow(
        val pkg: String,
        val label: String,
        val icon: Drawable?,
        val allowed: Boolean
    )

    private inner class AppAdapter : RecyclerView.Adapter<AppAdapter.Holder>() {
        private val items = mutableListOf<AppRow>()
        // Applied via setMuted() in loadApps(): reading store.isMuted() here would touch
        // applicationContext before the Context is attached and crash the activity.
        private var muted = false

        fun submit(rows: List<AppRow>) {
            items.clear()
            items.addAll(rows)
            notifyDataSetChanged()
        }

        /** Master mute overrides every row, so grey out and disable the list while muted. */
        fun setMuted(value: Boolean) {
            if (muted == value) return
            muted = value
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(24), dp(16), dp(24), dp(16))
                layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
            }
            val iconView = ImageView(parent.context).apply {
                id = ID_ICON
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            }
            val label = TextView(parent.context).apply {
                id = ID_LABEL
                textSize = 16f
                setPadding(dp(16), 0, dp(16), 0)
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            }
            val sw = SwitchCompat(parent.context).apply { id = ID_SWITCH }
            row.addView(iconView)
            row.addView(label)
            row.addView(sw)
            return Holder(row)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.icon.setImageDrawable(item.icon)
            holder.label.text = item.label
            holder.itemView.alpha = if (muted) 0.4f else 1f
            holder.sw.isEnabled = !muted
            // Clear the listener before mutating isChecked so recycled rows don't fire it.
            holder.sw.setOnCheckedChangeListener(null)
            holder.sw.isChecked = item.allowed
            holder.sw.setOnCheckedChangeListener { _, checked ->
                store.setAllowed(item.pkg, checked)
                items[holder.bindingAdapterPosition] = item.copy(allowed = checked)
            }
        }

        override fun getItemCount(): Int = items.size

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(ID_ICON)
            val label: TextView = view.findViewById(ID_LABEL)
            val sw: SwitchCompat = view.findViewById(ID_SWITCH)
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private val ID_ICON = View.generateViewId()
        private val ID_LABEL = View.generateViewId()
        private val ID_SWITCH = View.generateViewId()
    }
}
