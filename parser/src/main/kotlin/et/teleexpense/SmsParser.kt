package et.teleexpense

import et.teleexpense.AmountExtractor.ETB
import et.teleexpense.AmountExtractor.NUM
import et.teleexpense.AmountExtractor.parse
import et.teleexpense.Classification.COUNT
import et.teleexpense.Classification.IGNORE
import et.teleexpense.Classification.REFERENCE
import java.time.LocalDateTime

/** Everything a rule needs to build a Candidate. */
class ParseContext(val provider: String, val txDate: LocalDateTime?, val received: LocalDateTime, val text: String) {
    fun make(
        cl: Classification, category: String, reason: String,
        amount: Double? = null, direction: String = "outgoing", status: String = "successful",
        pkg: String? = null, recipient: String? = null, txId: String? = null, transferId: String? = null,
        confidence: Double = 0.9,
    ) = Candidate(
        cl, provider, category, amount, "ETB", direction, status, pkg, recipient, txId, transferId,
        txDate ?: received, received, txDate != null, reason, confidence
    )
}

/**
 * A rule inspects normalised text and returns a Candidate or null.
 * New SMS formats are added by appending a Rule + a fixture; the engine never changes.
 */
fun interface Rule { fun apply(c: ParseContext): Candidate? }

object SmsParser {
    private val transferIdRe = Regex("""Transfer ID:\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val txnNoRe = Regex("""transaction (?:number|id) is\s+([A-Z0-9]{6,})""", RegexOption.IGNORE_CASE)
    private val failedRe = Regex("""\b(failed|reversed|unsuccessful|declined|cancell?ed|insufficient)\b""", RegexOption.IGNORE_CASE)
    private val freeRe = Regex("""\b(free gift|free|complimentary|promotional|promotion|bonus package)\b""", RegexOption.IGNORE_CASE)

    private fun transferId(t: String) = transferIdRe.find(t)?.groupValues?.get(1)
    private fun txnNo(t: String) = txnNoRe.find(t)?.groupValues?.get(1)

    private val rules: List<Rule> = listOf(
        // 1. person-to-person: never telecom. Also swallows the fee and VAT amounts.
        Rule { c ->
            if (Regex("""you have transferred ETB""", RegexOption.IGNORE_CASE).containsMatchIn(c.text))
                c.make(IGNORE, "person_to_person_transfer", "person-to-person transfer; fee and VAT are not telecom") else null
        },
        // 2. incoming airtime
        Rule { c ->
            Regex("""received airtime top-up of $ETB""", RegexOption.IGNORE_CASE).find(c.text)?.let {
                c.make(IGNORE, "airtime", "incoming airtime", parse(it.groupValues[1]), "incoming", transferId = transferId(c.text))
            }
        },
        // 3. outgoing airtime (authoritative expense)
        Rule { c ->
            Regex("""sent airtime top-up of $ETB to (\d+)""", RegexOption.IGNORE_CASE).find(c.text)?.let {
                c.make(COUNT, "airtime", "outgoing airtime top-up", parse(it.groupValues[1]),
                    recipient = it.groupValues[2], transferId = transferId(c.text))
            }
        },
        // 4. recharge confirmation: supporting only. The balance is never read.
        Rule { c ->
            Regex("""you have recharged $ETB to (\d+)""", RegexOption.IGNORE_CASE).find(c.text)?.let {
                c.make(REFERENCE, "airtime_recharge", "recharge confirmation; balance ignored", parse(it.groupValues[1]),
                    recipient = it.groupValues[2])
            }
        },
        // 5. prepaid recharge: amount comes only from "Recharged balance", never "Your balance"
        Rule { c ->
            Regex("""Recharged balance is $NUM\s?Birr""", RegexOption.IGNORE_CASE).find(c.text)?.let {
                c.make(COUNT, "airtime_recharge", "prepaid recharge; 'Your balance' ignored", parse(it.groupValues[1]))
            }
        },
        // 6. paid package, strict form (telebirr). Runs before the free rule: "night bonus" can sit inside a paid pack.
        Rule { c ->
            Regex("""You have paid $ETB for (?:package )?(.+?) (?:purchase made for (\d+)|to (\d{9,12}))""", RegexOption.IGNORE_CASE).find(c.text)?.let {
                val name = it.groupValues[2].trim()
                c.make(COUNT, CategoryClassifier.ofPackage(name), "paid package", parse(it.groupValues[1]), pkg = name,
                    recipient = (it.groupValues[3] + it.groupValues[4]).ifEmpty { null }, txId = txnNo(c.text))
            }
        },
        // 7. paid something, looser form: count only if it looks like a telecom product, else reference
        Rule { c ->
            Regex("""You have paid $ETB for (?:package )?(.+?)(?: on \d|\.|$)""", RegexOption.IGNORE_CASE).find(c.text)?.let {
                val name = it.groupValues[2].trim()
                if (CategoryClassifier.looksLikePackage(name))
                    c.make(COUNT, CategoryClassifier.ofPackage(name), "paid telecom product", parse(it.groupValues[1]),
                        pkg = name, txId = txnNo(c.text), confidence = 0.7)
                else
                    c.make(REFERENCE, "other", "payment, not clearly telecom", parse(it.groupValues[1]), confidence = 0.3)
            }
        },
        // 8. free / bonus / promo
        Rule { c ->
            if (freeRe.containsMatchIn(c.text)) c.make(IGNORE, "free_package", "free or promotional package", 0.0) else null
        },
        // 9. package sent to a number, price unknown
        Rule { c ->
            Regex("""successfully sent (.+?) to (\d{9,12})""", RegexOption.IGNORE_CASE).find(c.text)?.let {
                val name = it.groupValues[1].trim()
                c.make(REFERENCE, CategoryClassifier.ofPackage(name), "package sent; price unknown", pkg = name, recipient = it.groupValues[2])
            }
        },
        // 10. activation notice
        Rule { c ->
            Regex("""service offer (.+?) from \w+ to expire.*?service number (\d+)""", RegexOption.IGNORE_CASE).find(c.text)?.let {
                c.make(REFERENCE, "package_activation", "activation notice; no price",
                    pkg = it.groupValues[1].trim(), recipient = it.groupValues[2])
            }
        },
        // 11. bank: only telecom-looking messages are kept, as references for future funding-link logic
        Rule { c ->
            if (c.provider != "Bank") null
            else if (Regex("""ethio ?telecom|telebirr|airtime|data package|mobile package""", RegexOption.IGNORE_CASE).containsMatchIn(c.text))
                c.make(REFERENCE, "bank_telecom_funding", "bank message mentioning telecom; never counted alone", confidence = 0.4)
            else c.make(IGNORE, "bank_other", "ordinary bank activity")
        },
    )

    fun normalise(s: String) = s.replace('\u00A0', ' ').replace('\u202F', ' ').replace(Regex("\\s+"), " ").trim()

    fun parse(sender: String?, body: String, received: LocalDateTime): Candidate {
        val text = normalise(body)
        val provider = ProviderDetector.detect(sender, text)
        val ctx = ParseContext(provider, DateExtractor.extract(text), received, text)
        if (failedRe.containsMatchIn(text))
            return ctx.make(IGNORE, "other", "failed, reversed or declined", status = "failed")
        for (rule in rules) rule.apply(ctx)?.let { return it }
        // Conservative default: never invent an expense.
        return ctx.make(REFERENCE, "other", "unrecognised format", confidence = 0.2)
    }
}
