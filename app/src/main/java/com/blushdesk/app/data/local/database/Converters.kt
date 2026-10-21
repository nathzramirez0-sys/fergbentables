package com.blushdesk.app.data.local.database

import androidx.room.TypeConverter
import com.blushdesk.app.domain.model.Availability
import com.blushdesk.app.domain.model.FulfillmentStatus
import com.blushdesk.app.domain.model.MovementType
import com.blushdesk.app.domain.model.NotificationKind
import com.blushdesk.app.domain.model.PaymentMode
import com.blushdesk.app.domain.model.PaymentStatus
import com.blushdesk.app.domain.model.ReminderInterval
import com.blushdesk.app.utils.Money
import java.math.BigDecimal
import java.time.Instant

/**
 * How non-primitive values are stored:
 *  - [Instant] as epoch milliseconds.
 *  - [BigDecimal] money as whole centavos (INTEGER), so SQL SUM() stays exact. A value with more
 *    than two decimals throws instead of being rounded: that would be a bug upstream.
 *  - Enums by name, not position, so reordering a declaration cannot relabel old rows. An unknown
 *    name falls back to a safe default instead of crashing the list.
 */
class Converters {
    @TypeConverter
    fun instantToEpochMillis(value: Instant): Long = value.toEpochMilli()

    @TypeConverter
    fun epochMillisToInstant(value: Long): Instant = Instant.ofEpochMilli(value)

    @TypeConverter
    fun moneyToCentavos(value: BigDecimal): Long = Money.toCentavos(value)

    @TypeConverter
    fun centavosToMoney(value: Long): BigDecimal = Money.fromCentavos(value)

    @TypeConverter
    fun fulfillmentToName(value: FulfillmentStatus): String = value.name

    @TypeConverter
    fun nameToFulfillment(value: String): FulfillmentStatus =
        FulfillmentStatus.entries.firstOrNull { it.name == value } ?: FulfillmentStatus.PROCESSING

    @TypeConverter
    fun paymentModeToName(value: PaymentMode): String = value.name

    @TypeConverter
    fun nameToPaymentMode(value: String): PaymentMode =
        PaymentMode.entries.firstOrNull { it.name == value } ?: PaymentMode.CASH

    @TypeConverter
    fun paymentStatusToName(value: PaymentStatus): String = value.name

    @TypeConverter
    fun nameToPaymentStatus(value: String): PaymentStatus =
        PaymentStatus.entries.firstOrNull { it.name == value } ?: PaymentStatus.UNPAID

    @TypeConverter
    fun availabilityToName(value: Availability): String = value.name

    /** An unknown value hides the product from sale rather than offering it by mistake. */
    @TypeConverter
    fun nameToAvailability(value: String): Availability =
        Availability.entries.firstOrNull { it.name == value } ?: Availability.UNAVAILABLE

    @TypeConverter
    fun reminderToName(value: ReminderInterval): String = value.name

    @TypeConverter
    fun nameToReminder(value: String): ReminderInterval =
        ReminderInterval.entries.firstOrNull { it.name == value } ?: ReminderInterval.NEVER

    @TypeConverter
    fun movementTypeToName(value: MovementType): String = value.name

    @TypeConverter
    fun nameToMovementType(value: String): MovementType =
        MovementType.entries.firstOrNull { it.name == value } ?: MovementType.ADJUSTMENT

    @TypeConverter
    fun notificationKindToName(value: NotificationKind): String = value.name

    @TypeConverter
    fun nameToNotificationKind(value: String): NotificationKind =
        NotificationKind.entries.firstOrNull { it.name == value } ?: NotificationKind.REMAINING_STOCK
}
