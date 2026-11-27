package com.sdorganizer.app

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/** One row in a tool list. */
class CheckItem(
    var title: String,
    var subtitle: String = "",
    var checked: Boolean = false,
    val checkable: Boolean = true,
    val isHeader: Boolean = false,
    val data: Any? = null,
    val onClick: (() -> Unit)? = null
)

class CheckListAdapter(
    private val ctx: Context,
    private val items: List<CheckItem>,
    private val onChange: () -> Unit
) : BaseAdapter() {
    private val dp = ctx.resources.displayMetrics.density

    override fun getCount() = items.size
    override fun getItem(position: Int) = items[position]
    override fun getItemId(position: Int) = position.toLong()

    private fun makeRow(): LinearLayout {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
        val cb = CheckBox(ctx)
        cb.isFocusable = false
        row.addView(cb)
        val texts = LinearLayout(ctx)
        texts.orientation = LinearLayout.VERTICAL
        texts.addView(TextView(ctx).apply { textSize = 15f })
        texts.addView(TextView(ctx).apply { textSize = 12.5f; alpha = 0.75f })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = (convertView as? LinearLayout) ?: makeRow()
        val item = items[position]
        val cb = row.getChildAt(0) as CheckBox
        val texts = row.getChildAt(1) as LinearLayout
        val t1 = texts.getChildAt(0) as TextView
        val t2 = texts.getChildAt(1) as TextView
        cb.setOnCheckedChangeListener(null)
        cb.visibility = if (item.checkable && !item.isHeader) View.VISIBLE else View.GONE
        cb.isChecked = item.checked
        cb.setOnCheckedChangeListener { _, c ->
            item.checked = c
            onChange()
        }
        t1.text = item.title
        t1.setTypeface(null, if (item.isHeader) Typeface.BOLD else Typeface.NORMAL)
        t1.textSize = if (item.isHeader) 16f else 15f
        t2.text = item.subtitle
        t2.visibility = if (item.subtitle.isEmpty()) View.GONE else View.VISIBLE
        val topPad = if (item.isHeader) (16 * dp).toInt() else (8 * dp).toInt()
        row.setPadding(0, topPad, 0, (8 * dp).toInt())
        return row
    }
}

/** Base screen: header text, optional top area, a list with checkboxes, and buttons at the bottom. */
abstract class ListScreen : AppCompatActivity() {
    protected lateinit var header: TextView
    protected lateinit var topArea: LinearLayout
    protected lateinit var list: ListView
    protected lateinit var buttons: LinearLayout
    protected lateinit var spinner: ProgressBar
    protected val items = ArrayList<CheckItem>()
    protected lateinit var adapter: CheckListAdapter
    protected val dp by lazy { resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * dp).toInt()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad / 2, pad, pad / 2)

        spinner = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        spinner.isIndeterminate = true
        spinner.visibility = View.GONE
        header = TextView(this).apply { textSize = 15f; setPadding(0, pad / 2, 0, pad / 2) }
        topArea = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list = ListView(this)
        buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        root.addView(spinner)
        root.addView(header)
        root.addView(topArea)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(buttons)
        setContentView(root)

        adapter = CheckListAdapter(this, items) { onSelectionChanged() }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            val it = items[pos]
            if (it.onClick != null) it.onClick.invoke()
            else if (it.checkable && !it.isHeader) {
                it.checked = !it.checked
                adapter.notifyDataSetChanged()
                onSelectionChanged()
            }
        }
    }

    protected open fun onSelectionChanged() {}

    protected fun addButton(text: String, outlined: Boolean = false, onClick: () -> Unit): MaterialButton {
        val b = if (outlined) MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        else MaterialButton(this)
        b.text = text
        b.setOnClickListener { onClick() }
        buttons.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return b
    }

    protected fun busy(msg: String) {
        spinner.visibility = View.VISIBLE
        header.text = msg
        for (i in 0 until buttons.childCount) buttons.getChildAt(i).isEnabled = false
    }

    protected fun idle() {
        spinner.visibility = View.GONE
        for (i in 0 until buttons.childCount) buttons.getChildAt(i).isEnabled = true
    }

    /** Runs work in the background, then shows the result on screen. */
    protected fun <T> bg(work: () -> T, then: (T) -> Unit) {
        Thread {
            val r = runCatching(work)
            runOnUiThread {
                idle()
                r.onSuccess(then).onFailure { header.text = "Something went wrong: ${it.message}" }
            }
        }.start()
    }

    protected fun selected(): List<CheckItem> = items.filter { it.checked && it.checkable && !it.isHeader }

    protected fun confirm(title: String, msg: String, yes: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton(yes) { _, _ -> action() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    protected fun showItems(newItems: List<CheckItem>) {
        items.clear()
        items.addAll(newItems)
        adapter.notifyDataSetChanged()
        onSelectionChanged()
    }

    protected fun openAppDetails(pkg: String) {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
    }

    protected fun openFirst(vararg intents: Intent) {
        for (i in intents) {
            try {
                startActivity(i)
                return
            } catch (_: Exception) {
            }
        }
    }
}
