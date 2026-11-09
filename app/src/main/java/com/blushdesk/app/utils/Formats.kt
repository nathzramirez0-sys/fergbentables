package com.blushdesk.app.utils

import com.blushdesk.app.data.local.database.OperatorProfile
import com.blushdesk.app.data.local.database.Order
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Text formats used on screen, in the PDF and in file names. English month and AM/PM names throughout. */
object Formats {
    private val DATE_TIME = DateTimeFormatter.ofPattern("MMM d, yyyy '·' h:mm a", Locale.ENGLISH)
    private val DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)
    private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm", Locale.ENGLISH)
    private val FILE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH)

    fun dateTime(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        DATE_TIME.format(instant.atZone(zone))

    /** "Oct 2, 2026" */
    fun date(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        DATE.format(instant.atZone(zone))

    /** "3:45 PM" */
    fun time(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        TIME.format(instant.atZone(zone))

    fun date(local: LocalDateTime): String = DATE.format(local)

    fun time(local: LocalDateTime): String = TIME.format(local)

    /** "2026-10-02_1530", for export file names; sorts in time order. */
    fun fileStamp(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        STAMP.format(instant.atZone(zone))

    /** "2026-10-20", for profile and inventory export file names. */
    fun fileDate(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        FILE_DATE.format(instant.atZone(zone))

    /** A buyer's contact for one-line lists: the phone number, else "Facebook: <name>". */
    fun contactLine(phone: String, facebookName: String): String = when {
        phone.isNotBlank() -> phone
        facebookName.isNotBlank() -> "Facebook: $facebookName"
        else -> ""
    }

    /** The receipt number printed on PDFs and shown on order cards: BD-000042, or RS-000042 with another prefix. */
    fun orderNumber(orderId: Long, prefix: String = OperatorProfile.DEFAULT_ORDER_PREFIX): String =
        prefix.ifBlank { OperatorProfile.DEFAULT_ORDER_PREFIX } + "-" + orderId.toString().padStart(6, '0')

    /** The order's number with the prefix it was made with. */
    fun orderNumber(order: Order): String = orderNumber(order.id, order.numberPrefix)

    /** "Velvet Sofa", "Velvet Sofa + 1 more", "Velvet Sofa + 3 more": a one-line name for an order. */
    fun itemsSummary(productNames: List<String>): String = when (productNames.size) {
        0 -> "No items"
        1 -> productNames[0]
        else -> "${productNames[0]} + ${productNames.size - 1} more"
    }

    /** Two-letter avatar fallback: "Ana Reyes" -> "AR", "Cher" -> "C", "Throw Pillow (Blush)" -> "TB". */
    fun initials(name: String): String {
        val words = name.split(Regex("\\s+")).map { word -> word.filter(Char::isLetterOrDigit) }.filter { it.isNotEmpty() }
        return when (words.size) {
            0 -> "?"
            1 -> words[0].take(1).uppercase()
            else -> (words.first().take(1) + words.last().take(1)).uppercase()
        }
    }

    /** Strips anything unsafe in a file name and keeps it short; [fallback] when nothing is left. */
    fun fileSafe(text: String, fallback: String = "buyer"): String =
        text.trim().replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').take(40).ifEmpty { fallback }
}
