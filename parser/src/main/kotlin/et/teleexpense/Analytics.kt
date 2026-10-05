package et.teleexpense

import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class Summary(val total: Double, val count: Int, val avgPerDay: Double, val avgPerTx: Double?)

object Analytics {
    fun active(all: List<ExpenseTx>) = all.filter { it.status == TxStatus.ACTIVE }

    fun inRange(all: List<ExpenseTx>, r: DateRange) =
        active(all).filter { it.dateTime.toLocalDate() in r }.sortedByDescending { it.dateTime }

    fun summary(list: List<ExpenseTx>, r: DateRange, today: LocalDate = LocalDate.now()): Summary {
        val total = list.sumOf { it.amount }
        val end = if (today.isBefore(r.to)) today else r.to
        val elapsed = (ChronoUnit.DAYS.between(r.from, end) + 1).toInt().coerceIn(1, r.days)
        return Summary(total, list.size, total / elapsed, if (list.isEmpty()) null else total / list.size)
    }

    fun byGroup(list: List<ExpenseTx>): Map<String, Double> {
        val m = CategoryClassifier.groups.associateWith { 0.0 }.toMutableMap()
        for (t in list) { val g = CategoryClassifier.groupOf(t.category); m[g] = (m[g] ?: 0.0) + t.amount }
        return m
    }

    /** (Ethiopian day-of-month label, amount) for every day of the range. */
    fun daily(list: List<ExpenseTx>, r: DateRange): List<Pair<String, Double>> =
        (0 until r.days).map { i ->
            val d = r.from.plusDays(i.toLong())
            EthiopianCalendar.fromGregorian(d).day.toString() to
                list.filter { it.dateTime.toLocalDate() == d }.sumOf { it.amount }
        }

    fun weekly(list: List<ExpenseTx>, r: DateRange): List<Pair<String, Double>> =
        (0 until (r.days + 6) / 7).map { w ->
            val a = r.from.plusDays(w * 7L)
            val b = a.plusDays(6)
            "W${w + 1}" to list.filter { val d = it.dateTime.toLocalDate(); !d.isBefore(a) && !d.isAfter(b) }.sumOf { it.amount }
        }

    /** 13 values, Meskerem..Pagume, for one Ethiopian year. */
    fun monthly(all: List<ExpenseTx>, ethYear: Int): List<Pair<String, Double>> =
        (1..13).map { m ->
            EthDate.MONTHS[m - 1].take(3) to active(all).filter { it.ethDate.year == ethYear && it.ethDate.month == m }.sumOf { it.amount }
        }

    fun topPurchases(list: List<ExpenseTx>, n: Int = 5): List<Triple<String, Int, Double>> =
        list.groupBy { it.title }.map { (k, v) -> Triple(k, v.size, v.sumOf { it.amount }) }
            .sortedByDescending { it.third }.take(n)
}
