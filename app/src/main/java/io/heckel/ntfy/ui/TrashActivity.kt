package io.heckel.ntfy.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.color.MaterialColors
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.db.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Recycle bin of the user's sole subscription. Entries are stored in Room. */
class TrashActivity : AppCompatActivity() {
    private lateinit var repository: Repository
    private lateinit var adapter: TrashAdapter
    private lateinit var retentionButton: MaterialButton
    private lateinit var countText: TextView
    private lateinit var emptyText: TextView
    private lateinit var restoreAllButton: MaterialButton
    private lateinit var emptyButton: MaterialButton
    private var subscriptionId = 0L

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = Repository.getInstance(this)
        val surface = MaterialColors.getColor(this, R.attr.colorSurface, Color.WHITE)
        val onSurface = MaterialColors.getColor(this, R.attr.colorOnSurface, Color.BLACK)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surface)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val back = MaterialButton(this).apply {
            text = "‹"
            contentDescription = getString(android.R.string.cancel)
            setOnClickListener { finish() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT))
        header.addView(TextView(this).apply {
            text = getString(R.string.trash_title)
            textSize = 20f
            setTextColor(onSurface)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        page.addView(header)

        retentionButton = MaterialButton(this).apply {
            isAllCaps = false
            setOnClickListener { showRetentionDialog() }
        }
        page.addView(retentionButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        updateRetentionLabel(retentionButton)

        countText = TextView(this).apply { textSize = 13f; setTextColor(onSurface); setPadding(dp(4), dp(8), dp(4), dp(8)) }
        page.addView(countText)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        restoreAllButton = MaterialButton(this).apply {
            isAllCaps = false
            text = getString(R.string.trash_restore_all)
            setOnClickListener {
                confirm(R.string.trash_confirm_restore_all) {
                    withContext(Dispatchers.IO) { repository.restoreAllTrash(subscriptionId) }
                }
            }
        }
        emptyButton = MaterialButton(this).apply {
            isAllCaps = false
            text = getString(R.string.trash_empty)
            setOnClickListener {
                confirm(R.string.trash_confirm_empty) {
                    withContext(Dispatchers.IO) { repository.emptyTrash(applicationContext, subscriptionId) }
                }
            }
        }
        actions.addView(restoreAllButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(emptyButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        page.addView(actions)

        emptyText = TextView(this).apply {
            text = getString(R.string.trash_empty_hint)
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(onSurface)
            visibility = View.GONE
        }
        page.addView(emptyText)

        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@TrashActivity)
            setHasFixedSize(false)
        }
        adapter = TrashAdapter(
            onRestore = { message ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { repository.undeleteNotification(message.id) }
                    refresh()
                }
            },
            onDelete = { message ->
                confirm(R.string.trash_confirm_delete) {
                    withContext(Dispatchers.IO) { repository.permanentlyDeleteTrash(applicationContext, message) }
                }
            }
        )
        list.adapter = adapter
        page.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(page)
        keepContentBelowStatusBar()
        lifecycleScope.launch {
            subscriptionId = withContext(Dispatchers.IO) { repository.getSubscriptions().firstOrNull()?.id ?: 0L }
            withContext(Dispatchers.IO) { repository.purgeExpiredTrash(applicationContext) }
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized && subscriptionId != 0L) lifecycleScope.launch { refresh() }
    }

    private fun confirm(message: Int, operation: suspend () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    operation()
                    refresh()
                }
            }
            .show()
    }

    private suspend fun refresh() {
        if (subscriptionId == 0L) return
        val messages = withContext(Dispatchers.IO) { repository.getTrashMessages(subscriptionId) }
        adapter.setMessages(messages)
        countText.text = getString(R.string.trash_count, messages.size)
        val hasMessages = messages.isNotEmpty()
        emptyText.visibility = if (hasMessages) View.GONE else View.VISIBLE
        restoreAllButton.isEnabled = hasMessages
        emptyButton.isEnabled = hasMessages
    }

    private fun retentionEntries(): Array<String> = arrayOf(
        getString(R.string.trash_retention_one_day), getString(R.string.trash_retention_one_week),
        getString(R.string.trash_retention_one_month), getString(R.string.trash_retention_three_months),
        getString(R.string.trash_retention_never)
    )
    private val retentionValues = listOf(86400L, 604800L, 2592000L, 7776000L, 0L)

    private fun updateRetentionLabel(button: MaterialButton) {
        val index = retentionValues.indexOf(repository.getTrashRetentionSeconds()).coerceAtLeast(0)
        button.text = getString(R.string.trash_retention_label) + "：" + retentionEntries()[index]
    }

    private fun showRetentionDialog() {
        val values = retentionEntries()
        val selected = retentionValues.indexOf(repository.getTrashRetentionSeconds()).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.trash_retention_label)
            .setSingleChoiceItems(values, selected) { dialog, choice ->
                repository.setTrashRetentionSeconds(retentionValues[choice])
                updateRetentionLabel(retentionButton)
                dialog.dismiss()
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { repository.purgeExpiredTrash(applicationContext) }
                    refresh()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private inner class TrashAdapter(
        private val onRestore: (Notification) -> Unit,
        private val onDelete: (Notification) -> Unit
    ) : RecyclerView.Adapter<TrashAdapter.EntryHolder>() {
        private var entries: List<Notification> = emptyList()

        inner class EntryHolder(val column: LinearLayout, val info: TextView, val restore: MaterialButton,
                                val remove: MaterialButton) : RecyclerView.ViewHolder(column)

        fun setMessages(messages: List<Notification>) {
            entries = messages
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryHolder {
            val onSurface = MaterialColors.getColor(parent.context, R.attr.colorOnSurface, Color.BLACK)
            val column = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(12), dp(8), dp(12))
            }
            val info = TextView(parent.context).apply {
                setTextColor(onSurface)
                textSize = 15f
                maxLines = 5
            }
            column.addView(info)
            val buttons = LinearLayout(parent.context)
            val restore = MaterialButton(parent.context).apply {
                isAllCaps = false
                text = getString(R.string.trash_restore)
            }
            val remove = MaterialButton(parent.context).apply {
                isAllCaps = false
                text = getString(R.string.trash_delete_forever)
            }
            buttons.addView(restore, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            buttons.addView(remove, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            column.addView(buttons)
            val divider = View(parent.context).apply { setBackgroundColor(onSurface and 0x00FFFFFF or 0x33000000) }
            column.addView(divider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            return EntryHolder(column, info, restore, remove)
        }

        override fun onBindViewHolder(holder: EntryHolder, position: Int) {
            val item = entries[position]
            val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.timestamp * 1000L))
            val body = item.message.ifBlank { item.attachment?.name.orEmpty() }
            holder.info.text = "$time\n${item.title.ifBlank { "(无标题)" }}\n${body.take(240)}"
            holder.restore.setOnClickListener { onRestore(item) }
            holder.remove.setOnClickListener { onDelete(item) }
        }
    }
}
