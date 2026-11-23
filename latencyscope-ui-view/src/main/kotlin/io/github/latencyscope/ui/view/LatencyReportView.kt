package io.github.latencyscope.ui.view

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.latencyscope.core.LatencyReport
import io.github.latencyscope.export.LatencyBand
import io.github.latencyscope.ui.view.databinding.LatencyscopeItemRowBinding
import io.github.latencyscope.ui.view.databinding.LatencyscopeItemTilesBinding
import io.github.latencyscope.ui.view.databinding.LatencyscopeViewReportBinding

/**
 * The latency report as a plain Android view.
 *
 * Drop it into any layout, or use [LatencyReportActivity] /
 * [LatencyReportFragment] for a ready-made screen:
 *
 * ```kotlin
 * reportView.setReport(report)
 * reportView.onShare = { LatencyExport.share(context, report) }
 * ```
 */
public class LatencyReportView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val binding = LatencyscopeViewReportBinding.inflate(LayoutInflater.from(context), this)
    private val adapter = RowAdapter()

    private var report: LatencyReport? = null
    private var tab: ReportTab = ReportTab.ROUTES
    private var query: String = ""

    /** Invoked by the Share button; the button is hidden when this is null. */
    public var onShare: (() -> Unit)? = null
        set(value) {
            field = value
            binding.latencyscopeShare.visibility = if (value == null) GONE else VISIBLE
        }

    init {
        orientation = VERTICAL
        setBackgroundColor(ContextCompat.getColor(context, R.color.latencyscope_surface_alt))
        binding.latencyscopeList.layoutManager = LinearLayoutManager(context)
        binding.latencyscopeList.adapter = adapter

        binding.latencyscopeTabRoutes.setOnClickListener { select(ReportTab.ROUTES) }
        binding.latencyscopeTabCalls.setOnClickListener { select(ReportTab.CALLS) }
        binding.latencyscopeTabFailures.setOnClickListener { select(ReportTab.FAILURES) }
        binding.latencyscopeShare.setOnClickListener { onShare?.invoke() }
        binding.latencyscopeShare.visibility = GONE

        binding.latencyscopeFilter.addTextChangedListener(
            afterTextChanged = { text ->
                query = text
                refresh()
            },
        )
    }

    /** Shows [report], or an explanatory message when it is null. */
    public fun setReport(report: LatencyReport?) {
        this.report = report
        if (report == null) {
            binding.latencyscopeSessionName.text = context.getString(R.string.latencyscope_title)
            binding.latencyscopeSessionMeta.text = ""
        } else {
            binding.latencyscopeSessionName.text = report.session.name
            binding.latencyscopeSessionMeta.text = ReportRows.summary(report).headline
        }
        refresh()
    }

    public fun setTab(tab: ReportTab) {
        select(tab)
    }

    private fun select(tab: ReportTab) {
        this.tab = tab
        updateTabLabels()
        refresh()
    }

    private fun updateTabLabels() {
        val current = report
        binding.latencyscopeTabRoutes.text = tabLabel(
            R.string.latencyscope_tab_routes,
            current?.routes?.size,
            tab == ReportTab.ROUTES,
        )
        binding.latencyscopeTabCalls.text = tabLabel(
            R.string.latencyscope_tab_calls,
            current?.session?.records?.size,
            tab == ReportTab.CALLS,
        )
        binding.latencyscopeTabFailures.text = tabLabel(
            R.string.latencyscope_tab_failures,
            current?.errors?.sumOf { it.count },
            tab == ReportTab.FAILURES,
        )
    }

    /**
     * The selected tab is marked with a bullet as well as the button state —
     * a pressed-looking button is easy to miss, and impossible to see at all
     * in a screenshot pasted into a bug report.
     */
    private fun tabLabel(resId: Int, count: Int?, selected: Boolean): String {
        val base = context.getString(resId) + (count?.let { " $it" } ?: "")
        return if (selected) "• $base" else base
    }

    private fun refresh() {
        updateTabLabels()
        val current = report
        if (current == null) {
            adapter.submitList(emptyList())
            binding.latencyscopeEmpty.visibility = VISIBLE
            binding.latencyscopeEmpty.setText(R.string.latencyscope_empty_no_session)
            binding.latencyscopeFilter.visibility = GONE
            return
        }

        binding.latencyscopeFilter.visibility = VISIBLE
        val rows = ReportRows.build(current, tab, query)
        val hasEntries = rows.any { it is ReportRow.Entry }
        binding.latencyscopeEmpty.visibility = if (hasEntries) GONE else VISIBLE
        binding.latencyscopeEmpty.setText(R.string.latencyscope_empty_filter)
        adapter.submitList(rows)
    }

    /** Visible for tests: the rows currently bound to the list. */
    internal fun currentRows(): List<ReportRow> = adapter.currentList
}

// ----------------------------------------------------------------- adapter

private class RowAdapter : ListAdapter<ReportRow, RecyclerView.ViewHolder>(DIFFER_CONFIG) {

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is ReportRow.Summary -> TYPE_SUMMARY
        is ReportRow.Entry -> TYPE_ENTRY
        is ReportRow.Footer -> TYPE_FOOTER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_SUMMARY -> SummaryHolder(
                LatencyscopeItemTilesBinding.inflate(inflater, parent, false),
            )

            TYPE_ENTRY -> EntryHolder(
                LatencyscopeItemRowBinding.inflate(inflater, parent, false),
            )

            else -> FooterHolder(
                inflater.inflate(R.layout.latencyscope_item_footer, parent, false),
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is ReportRow.Summary -> (holder as SummaryHolder).bind(row)
            is ReportRow.Entry -> (holder as EntryHolder).bind(row)
            is ReportRow.Footer -> (holder as FooterHolder).bind(row)
        }
    }

    private class SummaryHolder(
        private val binding: LatencyscopeItemTilesBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: ReportRow.Summary) {
            binding.latencyscopeTilesHeadline.text = row.headline
            binding.latencyscopeTilesDetail.text = row.detail
            binding.latencyscopeTilesWarning.visibility =
                if (row.warning == null) View.GONE else View.VISIBLE
            binding.latencyscopeTilesWarning.text = row.warning.orEmpty()
        }
    }

    private class EntryHolder(
        private val binding: LatencyscopeItemRowBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        private var expanded = false

        init {
            binding.root.setOnClickListener {
                expanded = !expanded
                applyExpansion()
            }
        }

        fun bind(row: ReportRow.Entry) {
            expanded = false
            binding.latencyscopeRowMethod.visibility =
                if (row.method == null) View.GONE else View.VISIBLE
            binding.latencyscopeRowMethod.text = row.method.orEmpty()
            binding.latencyscopeRowTitle.text = row.title
            binding.latencyscopeRowSubtitle.text = row.subtitle

            val latency = binding.latencyscopeRowLatency
            if (row.latency == null) {
                latency.visibility = View.GONE
            } else {
                latency.visibility = View.VISIBLE
                // The slow band is prefixed, so the warning survives greyscale
                // and colour-blind readers.
                latency.text = if (row.band == LatencyBand.SLOW) "! " + row.latency else row.latency
                latency.setTextColor(
                    ContextCompat.getColor(latency.context, colorFor(row.band)),
                )
                latency.contentDescription = row.latency + ", " + (row.band?.label ?: "")
            }

            binding.latencyscopeRowDetail.text = row.detail
            if (row.slices.isEmpty()) {
                binding.latencyscopeRowPhaseBar.visibility = View.GONE
            } else {
                binding.latencyscopeRowPhaseBar.setSlices(row.slices)
            }
            applyExpansion()
        }

        private fun applyExpansion() {
            val visibility = if (expanded) View.VISIBLE else View.GONE
            binding.latencyscopeRowDetail.visibility = visibility
            binding.latencyscopeRowPhaseBar.visibility =
                if (expanded && binding.latencyscopeRowPhaseBar.legendText().isNotEmpty()) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
        }

        private fun colorFor(band: LatencyBand?): Int = when (band) {
            LatencyBand.SLOW -> R.color.latencyscope_slow
            LatencyBand.WARN -> R.color.latencyscope_warn
            else -> R.color.latencyscope_fast
        }
    }

    private class FooterHolder(private val view: View) : RecyclerView.ViewHolder(view) {
        fun bind(row: ReportRow.Footer) {
            (view as android.widget.TextView).text = row.text
        }
    }

    companion object {
        const val TYPE_SUMMARY = 0
        const val TYPE_ENTRY = 1
        const val TYPE_FOOTER = 2

        val DIFF: DiffUtil.ItemCallback<ReportRow> = object : DiffUtil.ItemCallback<ReportRow>() {
            override fun areItemsTheSame(oldItem: ReportRow, newItem: ReportRow): Boolean =
                when {
                    oldItem is ReportRow.Entry && newItem is ReportRow.Entry ->
                        oldItem.id == newItem.id

                    else -> oldItem::class == newItem::class
                }

            override fun areContentsTheSame(oldItem: ReportRow, newItem: ReportRow): Boolean =
                oldItem == newItem
        }

        /**
         * Diffing runs inline rather than on a background thread. A report
         * holds at most a few thousand rows of already-computed strings, so
         * the diff is cheap, and an inline one keeps the list in step with the
         * filter the user is typing into instead of lagging a frame behind it.
         */
        val DIFFER_CONFIG: AsyncDifferConfig<ReportRow> =
            AsyncDifferConfig.Builder(DIFF)
                .setBackgroundThreadExecutor { runnable -> runnable.run() }
                .build()
    }
}

/** Minimal text watcher, to avoid pulling in androidx.core-ktx for one call. */
private fun android.widget.EditText.addTextChangedListener(afterTextChanged: (String) -> Unit) {
    addTextChangedListener(
        object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                afterTextChanged(s?.toString().orEmpty())
            }
        },
    )
}
