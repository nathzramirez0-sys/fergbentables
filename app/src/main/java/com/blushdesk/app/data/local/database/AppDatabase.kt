package com.blushdesk.app.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        OperatorProfile::class, Buyer::class, Order::class, OrderItem::class,
        Product::class, StockMovement::class, InventoryNotification::class,
    ],
    version = 5,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun dao(): ShowroomDao

    abstract fun inventoryDao(): InventoryDao

    companion object {
        /** Unchanged since version 1, so an upgraded app opens (and migrates) the same file. */
        const val FILE_NAME = "showroom.db"

        /**
         * No destructive-migration fallback on purpose: this is the shop's only copy of its
         * records, so a missing migration must fail loudly in development, never wipe data.
         */
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, FILE_NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
    }
}
