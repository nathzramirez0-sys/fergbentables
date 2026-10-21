package com.blushdesk.app.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.blushdesk.app.data.local.database.AppDatabase
import com.blushdesk.app.data.local.database.MIGRATION_1_2
import com.blushdesk.app.data.local.database.MIGRATION_2_3
import com.blushdesk.app.data.local.database.MIGRATION_3_4
import com.blushdesk.app.data.local.database.MIGRATION_4_5
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Builds an older database from its exported schema (1.json, 2.json, ...), fills it the way that
 * release stored data, runs the migrations and lets Room validate the result against the newer
 * schema. Then checks every renamed, moved or derived value survived.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    private val dbName = "migration-test.db"

    @Test
    fun migrate_1_to_2_keeps_and_renames_all_data() {
        helper.createDatabase(dbName, 1).apply {
            execSQL(
                "INSERT INTO operator_profile (id, fullName, storeName, email, phone, photoPath) " +
                    "VALUES (1, 'Lia Santos', 'Rosé Showroom', 'lia@rose.example', '0917 555 0100', '/data/photos/lia.jpg')",
            )
            execSQL(
                "INSERT INTO buyers (id, fullName, contact, email, dateAdded, photoPath) " +
                    "VALUES (7, 'Ana Reyes', '0917 123 4567', 'ana@example.com', 1759276800000, NULL)",
            )
            execSQL(
                "INSERT INTO orders (id, buyerId, productName, unitPriceMinor, quantity, purchasedAt, paymentMode, paymentStatus, orderStatus) " +
                    "VALUES (42, 7, 'Velvet Sofa', 99999, 3, 1759363200000, 'ONLINE', 'PENDING', 'PREPARING')",
            )
            execSQL(
                "INSERT INTO orders (id, buyerId, productName, unitPriceMinor, quantity, purchasedAt, paymentMode, paymentStatus, orderStatus) " +
                    "VALUES (43, 7, 'Lamp', 5000, 1, 1759363300000, 'CASH', 'PAID', 'DELIVERED')",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 2, true, MIGRATION_1_2)

        db.query("SELECT fullName, phoneNumber, profileImageUri, createdAt, updatedAt FROM operator_profile").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Lia Santos", c.getString(0))
            assertEquals("0917 555 0100", c.getString(1))
            assertEquals("file:///data/photos/lia.jpg", c.getString(2))
            assertTrue(c.getLong(3) > 0 && c.getLong(3) == c.getLong(4))
        }

        db.query("SELECT id, contactNumber, profileImageUri, dateAdded, createdAt, updatedAt FROM buyers").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(7L, c.getLong(0))
            assertEquals("0917 123 4567", c.getString(1))
            assertTrue(c.isNull(2))
            assertEquals(1759276800000L, c.getLong(3))
            assertEquals(1759276800000L, c.getLong(4)) // createdAt starts from dateAdded
            assertEquals(1759276800000L, c.getLong(5))
        }

        db.query(
            "SELECT id, unitPrice, quantity, totalAmount, purchaseDateTime, paymentMode, paymentStatus, fulfillmentStatus, createdAt " +
                "FROM orders ORDER BY id",
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(42L, c.getLong(0))
            assertEquals(99999L, c.getLong(1)) // centavos unchanged
            assertEquals(3, c.getInt(2))
            assertEquals(299997L, c.getLong(3)) // total = 999.99 x 3, computed during the migration
            assertEquals(1759363200000L, c.getLong(4))
            assertEquals("ONLINE_PAYMENT", c.getString(5)) // renamed enum value
            assertEquals("PENDING", c.getString(6))
            assertEquals("PREPARING", c.getString(7))
            assertEquals(1759363200000L, c.getLong(8))

            assertTrue(c.moveToNext())
            assertEquals("CASH", c.getString(5))
            assertEquals("DELIVERED", c.getString(7))
        }

        // The foreign key still cascades after the rebuild.
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("DELETE FROM buyers WHERE id = 7")
        db.query("SELECT COUNT(*) FROM orders").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
        }
        db.close()
    }

    @Test
    fun migrate_2_to_3_turns_each_order_into_an_order_with_one_item() {
        helper.createDatabase(dbName, 2).apply {
            execSQL(
                "INSERT INTO buyers (id, fullName, contactNumber, email, dateAdded, profileImageUri, createdAt, updatedAt) " +
                    "VALUES (7, 'Ana Reyes', '0917 123 4567', '', 1759276800000, NULL, 1759276800000, 1759276800000)",
            )
            execSQL(
                "INSERT INTO orders (id, buyerId, productName, unitPrice, quantity, totalAmount, purchaseDateTime, " +
                    "paymentMode, paymentStatus, fulfillmentStatus, createdAt, updatedAt) " +
                    "VALUES (42, 7, 'Velvet Sofa', 99999, 3, 299997, 1759363200000, 'CASH', 'PAID', 'DELIVERED', 1, 2)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 3, true, MIGRATION_2_3)

        db.query("SELECT id, buyerId, totalAmount, paymentStatus, fulfillmentStatus, createdAt, updatedAt FROM orders").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(42L, c.getLong(0))
            assertEquals(7L, c.getLong(1))
            assertEquals(299997L, c.getLong(2)) // the total is unchanged
            assertEquals("PAID", c.getString(3))
            assertEquals("DELIVERED", c.getString(4))
            assertEquals(1L, c.getLong(5))
            assertEquals(2L, c.getLong(6))
        }
        db.query("SELECT orderId, position, productName, unitPrice, quantity, lineTotal FROM order_items").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(42L, c.getLong(0))
            assertEquals(0, c.getInt(1))
            assertEquals("Velvet Sofa", c.getString(2))
            assertEquals(99999L, c.getLong(3))
            assertEquals(3, c.getInt(4))
            assertEquals(299997L, c.getLong(5))
        }

        // Deleting the order still removes its item through the new foreign key.
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("DELETE FROM orders WHERE id = 42")
        db.query("SELECT COUNT(*) FROM order_items").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
        }
        db.close()
    }

    @Test
    fun migrate_3_to_4_gives_existing_buyers_an_empty_facebook_name() {
        helper.createDatabase(dbName, 3).apply {
            execSQL(
                "INSERT INTO buyers (id, fullName, contactNumber, email, dateAdded, profileImageUri, createdAt, updatedAt) " +
                    "VALUES (1, 'Ana', '0917 123 4567', '', 1000, NULL, 1000, 1000)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 4, true, MIGRATION_3_4)

        db.query("SELECT contactNumber, facebookName FROM buyers WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("0917 123 4567", c.getString(0))
            assertEquals("", c.getString(1))
        }
        db.close()
    }

    @Test
    fun migrate_4_to_5_adds_the_inventory_and_keeps_every_order_line() {
        helper.createDatabase(dbName, 4).apply {
            execSQL(
                "INSERT INTO buyers (id, fullName, contactNumber, facebookName, email, dateAdded, profileImageUri, createdAt, updatedAt) " +
                    "VALUES (1, 'Ana', '0917 123 4567', '', '', 1000, NULL, 1000, 1000)",
            )
            execSQL(
                "INSERT INTO orders (id, buyerId, totalAmount, purchaseDateTime, paymentMode, paymentStatus, fulfillmentStatus, createdAt, updatedAt) " +
                    "VALUES (7, 1, 545000, 2000, 'CASH', 'PAID', 'DELIVERED', 2000, 2000)",
            )
            execSQL("INSERT INTO order_items (id, orderId, position, productName, unitPrice, quantity, lineTotal) VALUES (70, 7, 0, 'Side Table', 350000, 1, 350000)")
            execSQL("INSERT INTO order_items (id, orderId, position, productName, unitPrice, quantity, lineTotal) VALUES (71, 7, 1, 'Throw Pillow', 45000, 4, 180000)")
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 5, true, MIGRATION_4_5)

        db.query("SELECT id, orderId, position, productName, unitPrice, quantity, lineTotal, productId FROM order_items ORDER BY id").use { c ->
            assertEquals(2, c.count)
            c.moveToFirst()
            assertEquals(70L, c.getLong(0))
            assertEquals("Side Table", c.getString(3))
            assertEquals(350000L, c.getLong(4))
            assertTrue("old lines are custom items, not linked to stock", c.isNull(7))
            c.moveToNext()
            assertEquals(71L, c.getLong(0))
            assertEquals(4, c.getInt(5))
        }
        db.query("SELECT COUNT(*) FROM products").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
        }
        // The new tables work: a product with a movement, linked to the old order's new line.
        db.execSQL(
            "INSERT INTO products (id, sku, name, category, description, sellingPrice, costPrice, quantity, minimumStock, unit, " +
                "availability, archived, imageUri, reminderInterval, reminderLastSentAt, reminderNextAt, lowStockAlerted, createdAt, updatedAt) " +
                "VALUES (1, 'KB-01', 'USB Keyboard', '', '', 85000, NULL, 20, 5, 'pc', 'AVAILABLE', 0, NULL, 'NEVER', NULL, NULL, 0, 3000, 3000)",
        )
        db.execSQL(
            "INSERT INTO stock_movements (productId, type, previousQuantity, change, newQuantity, orderId, reason, supplier, reference, performedBy, occurredAt, createdAt) " +
                "VALUES (1, 'INITIAL', 0, 20, 20, NULL, '', '', '', 'Lia', 3000, 3000)",
        )
        db.execSQL("INSERT INTO order_items (orderId, position, productName, unitPrice, quantity, lineTotal, productId) VALUES (7, 2, 'USB Keyboard', 85000, 1, 85000, 1)")
        db.close()
    }

    @Test
    fun first_release_data_survives_every_migration() {
        helper.createDatabase(dbName, 1).apply {
            execSQL("INSERT INTO buyers (id, fullName, contact, email, dateAdded, photoPath) VALUES (1, 'Ben', '0918 765 4321', '', 1000, NULL)")
            execSQL(
                "INSERT INTO orders (id, buyerId, productName, unitPriceMinor, quantity, purchasedAt, paymentMode, paymentStatus, orderStatus) " +
                    "VALUES (5, 1, 'Floor Lamp', 99999, 3, 2000, 'ONLINE', 'UNPAID', 'PROCESSING')",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 5, true, MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

        db.query("SELECT contactNumber, facebookName FROM buyers").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("0918 765 4321", c.getString(0))
            assertEquals("", c.getString(1))
        }
        db.query("SELECT o.totalAmount, o.paymentMode, i.productName, i.lineTotal FROM orders o JOIN order_items i ON i.orderId = o.id").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(299997L, c.getLong(0))
            assertEquals("ONLINE_PAYMENT", c.getString(1))
            assertEquals("Floor Lamp", c.getString(2))
            assertEquals(299997L, c.getLong(3))
        }
        db.close()
    }

    @Test
    fun migrating_an_empty_database_works() {
        helper.createDatabase(dbName, 1).close()
        val db = helper.runMigrationsAndValidate(dbName, 2, true, MIGRATION_1_2)
        db.query("SELECT COUNT(*) FROM buyers").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
        }
        db.query("SELECT * FROM operator_profile").use { c -> assertEquals(0, c.count) }
        db.close()
    }
}
