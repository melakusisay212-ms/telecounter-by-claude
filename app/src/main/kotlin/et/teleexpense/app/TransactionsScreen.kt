package et.teleexpense.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import et.teleexpense.Analytics
import et.teleexpense.CategoryClassifier
import et.teleexpense.EthiopianCalendar
import et.teleexpense.ExpenseTx

@Composable
fun TransactionsScreen(txs: List<ExpenseTx>, period: PeriodState, onOpen: (String) -> Unit) {
    val range = period.range
    val filter = period.groupFilter
    val inRange = Analytics.inRange(txs, range)
    val list = if (filter == "All") inRange else inRange.filter { CategoryClassifier.groupOf(it.category) == filter }
    val days = list.groupBy { it.dateTime.toLocalDate() }.entries.sortedByDescending { it.key }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            Text("Transactions", fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Brand.Ink)
            Spacer(Modifier.height(10.dp))
            PeriodSelector(period, onGreen = false)
            Spacer(Modifier.height(8.dp))
            SegmentedTabs(listOf("All") + CategoryClassifier.groups, filter) { period.groupFilter = it }
            Spacer(Modifier.height(8.dp))
            Text(
                "${Fmt.plural(list.size, "transaction", "transactions")} · ETB ${Fmt.etb(list.sumOf { it.amount })}",
                fontSize = 13.sp,
                color = Brand.Muted,
            )
        }
        if (list.isEmpty()) {
            EmptyState("🧾", "No transactions here", "Nothing matches this period and filter yet.")
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                for (entry in days) {
                    val eth = EthiopianCalendar.fromGregorian(entry.key)
                    item(key = "header-${entry.key}") {
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(eth.toString(), modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, color = Brand.Ink)
                            Text("${Fmt.etb(entry.value.sumOf { it.amount })} ETB", fontSize = 13.sp, color = Brand.Muted)
                        }
                    }
                    items(entry.value, key = { it.key }) { t -> TxCard(t) { onOpen(t.key) } }
                }
            }
        }
    }
}
