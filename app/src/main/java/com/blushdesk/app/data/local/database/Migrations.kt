package com.blushdesk.app.data.local.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 1 -> 2: columns renamed to the specification's names (contact -> contactNumber,
 * phone -> phoneNumber, photoPath -> profileImageUri, unitPriceMinor -> unitPrice,
 * purchasedAt -> purchaseDateTime, orderStatus -> fulfillmentStatus), a stored totalAmount,
 * createdAt / updatedAt on every table, and the payment mode ONLINE renamed ONLINE_PAYMENT.
 *
 * SQLite cannot rename or add NOT NULL columns in place on API 29, so each table is rebuilt:
 * create the new shape, copy the rows across, drop the old table, rename. Room runs migrations
 * before it switches foreign keys on, so dropping `buyers` cannot cascade into `orders`, and it
 * checks the foreign keys afterwards.
 *
 * Old photo paths become file:// URIs. createdAt / updatedAt start from the best timestamp each
 * row already had (dateAdded, purchase time); the operator row, which had none, gets the
 * migration time.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val now = System.currentTimeMillis()

        db.execSQL(
            """
            CREATE TABLE `operator_profile_new` (
                `id` INTEGER NOT NULL, `fullName` TEXT NOT NULL, `storeName` TEXT NOT NULL,
                `email` TEXT NOT NULL, `phoneNumber` TEXT NOT NULL, `profileImageUri` TEXT,
                `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO `operator_profile_new`
                (id, fullName, storeName, email, phoneNumber, profileImageUri, createdAt, updatedAt)
            SELECT id, fullName, storeName, email, phone,
                   CASE WHEN photoPath IS NULL THEN NULL ELSE 'file://' || photoPath END,
                   $now, $now
            FROM `operator_profile`
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE `operator_profile`")
        db.execSQL("ALTER TABLE `operator_profile_new` RENAME TO `operator_profile`")

        db.execSQL(
            """
            CREATE TABLE `buyers_new` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `fullName` TEXT NOT NULL,
                `contactNumber` TEXT NOT NULL, `email` TEXT NOT NULL, `dateAdded` INTEGER NOT NULL,
                `profileImageUri` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO `buyers_new`
                (id, fullName, contactNumber, email, dateAdded, profileImageUri, createdAt, updatedAt)
            SELECT id, fullName, contact, email, dateAdded,
                   CASE WHEN photoPath IS NULL THEN NULL ELSE 'file://' || photoPath END,
                   dateAdded, dateAdded
            FROM `buyers`
            """.trimIndent(),
        )

        // References `buyers` by its final name, which exists again once buyers_new is renamed.
        db.execSQL(
            """
            CREATE TABLE `orders_new` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `buyerId` INTEGER NOT NULL,
                `productName` TEXT NOT NULL, `unitPrice` INTEGER NOT NULL, `quantity` INTEGER NOT NULL,
                `totalAmount` INTEGER NOT NULL, `purchaseDateTime` INTEGER NOT NULL,
                `paymentMode` TEXT NOT NULL, `paymentStatus` TEXT NOT NULL, `fulfillmentStatus` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL,
                FOREIGN KEY(`buyerId`) REFERENCES `buyers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO `orders_new`
                (id, buyerId, productName, unitPrice, quantity, totalAmount, purchaseDateTime,
                 paymentMode, paymentStatus, fulfillmentStatus, createdAt, updatedAt)
            SELECT id, buyerId, productName, unitPriceMinor, quantity, unitPriceMinor * quantity, purchasedAt,
                   CASE paymentMode WHEN 'ONLINE' THEN 'ONLINE_PAYMENT' ELSE paymentMode END,
                   paymentStatus, orderStatus, purchasedAt, purchasedAt
            FROM `orders`
            """.trimIndent(),
        )

        db.execSQL("DROP TABLE `orders`")
        db.execSQL("DROP TABLE `buyers`")
        db.execSQL("ALTER TABLE `buyers_new` RENAME TO `buyers`")
        db.execSQL("ALTER TABLE `orders_new` RENAME TO `orders`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_buyers_fullName` ON `buyers` (`fullName`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_orders_buyerId` ON `orders` (`buyerId`)")
    }
}

/**
 * Version 2 -> 3: an order can hold several products. The product columns move out of `orders`
 * into a new `order_items` table; every existing order becomes an order with one item, so nothing
 * is lost and every total stays the same.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE `orders_new` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `buyerId` INTEGER NOT NULL,
                `totalAmount` INTEGER NOT NULL, `purchaseDateTime` INTEGER NOT NULL,
                `paymentMode` TEXT NOT NULL, `paymentStatus` TEXT NOT NULL, `fulfillmentStatus` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL,
                FOREIGN KEY(`buyerId`) REFERENCES `buyers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO `orders_new`
                (id, buyerId, totalAmount, purchaseDateTime, paymentMode, paymentStatus, fulfillmentStatus, createdAt, updatedAt)
            SELECT id, buyerId, totalAmount, purchaseDateTime, paymentMode, paymentStatus, fulfillmentStatus, createdAt, updatedAt
            FROM `orders`
            """.trimIndent(),
        )

        // References `orders` by its final name, which the renamed orders_new takes below.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `order_items` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `orderId` INTEGER NOT NULL,
                `position` INTEGER NOT NULL, `productName` TEXT NOT NULL, `unitPrice` INTEGER NOT NULL,
                `quantity` INTEGER NOT NULL, `lineTotal` INTEGER NOT NULL,
                FOREIGN KEY(`orderId`) REFERENCES `orders`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO `order_items` (orderId, position, productName, unitPrice, quantity, lineTotal)
            SELECT id, 0, productName, unitPrice, quantity, totalAmount FROM `orders`
            """.trimIndent(),
        )

        db.execSQL("DROP TABLE `orders`")
        db.execSQL("ALTER TABLE `orders_new` RENAME TO `orders`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_orders_buyerId` ON `orders` (`buyerId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_order_items_orderId` ON `order_items` (`orderId`)")
    }
}

/**
 * Version 3 -> 4: buyers get a Facebook name, an alternative to the contact number, which became
 * optional. Existing buyers keep their number and get an empty Facebook name.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `buyers` ADD COLUMN `facebookName` TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * Version 4 -> 5: the inventory. Three new tables (products, their stock movements, inventory
 * notifications) and an optional productId on every order line. Existing lines keep a null
 * productId: they were typed in by hand before products existed and never touch stock.
 *
 * SQLite cannot add a foreign key to an existing table, so `order_items` is rebuilt the same way
 * as in [MIGRATION_1_2]: new shape, copy the rows (ids included), drop, rename.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `products` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sku` TEXT, `name` TEXT NOT NULL,
                `category` TEXT NOT NULL, `description` TEXT NOT NULL, `sellingPrice` INTEGER NOT NULL,
                `costPrice` INTEGER, `quantity` INTEGER NOT NULL, `minimumStock` INTEGER NOT NULL,
                `unit` TEXT NOT NULL, `availability` TEXT NOT NULL, `archived` INTEGER NOT NULL,
                `imageUri` TEXT, `reminderInterval` TEXT NOT NULL, `reminderLastSentAt` INTEGER,
                `reminderNextAt` INTEGER, `lowStockAlerted` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)
            """.trimIndent(),
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_products_sku` ON `products` (`sku`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_products_name` ON `products` (`name`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_products_archived` ON `products` (`archived`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `stock_movements` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `productId` INTEGER NOT NULL,
                `type` TEXT NOT NULL, `previousQuantity` INTEGER NOT NULL, `change` INTEGER NOT NULL,
                `newQuantity` INTEGER NOT NULL, `orderId` INTEGER, `reason` TEXT NOT NULL,
                `supplier` TEXT NOT NULL, `reference` TEXT NOT NULL, `performedBy` TEXT NOT NULL,
                `occurredAt` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL,
                FOREIGN KEY(`productId`) REFERENCES `products`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_stock_movements_productId` ON `stock_movements` (`productId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_stock_movements_orderId` ON `stock_movements` (`orderId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_stock_movements_occurredAt` ON `stock_movements` (`occurredAt`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `inventory_notifications` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `productId` INTEGER NOT NULL,
                `kind` TEXT NOT NULL, `title` TEXT NOT NULL, `message` TEXT NOT NULL,
                `dedupKey` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `readAt` INTEGER, `postedAt` INTEGER,
                FOREIGN KEY(`productId`) REFERENCES `products`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_inventory_notifications_productId` ON `inventory_notifications` (`productId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_inventory_notifications_dedupKey` ON `inventory_notifications` (`dedupKey`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_inventory_notifications_createdAt` ON `inventory_notifications` (`createdAt`)")

        db.execSQL(
            """
            CREATE TABLE `order_items_new` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `orderId` INTEGER NOT NULL,
                `position` INTEGER NOT NULL, `productName` TEXT NOT NULL, `unitPrice` INTEGER NOT NULL,
                `quantity` INTEGER NOT NULL, `lineTotal` INTEGER NOT NULL, `productId` INTEGER,
                FOREIGN KEY(`orderId`) REFERENCES `orders`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`productId`) REFERENCES `products`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO `order_items_new` (id, orderId, position, productName, unitPrice, quantity, lineTotal, productId)
            SELECT id, orderId, position, productName, unitPrice, quantity, lineTotal, NULL FROM `order_items`
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE `order_items`")
        db.execSQL("ALTER TABLE `order_items_new` RENAME TO `order_items`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_order_items_orderId` ON `order_items` (`orderId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_order_items_productId` ON `order_items` (`productId`)")
    }
}
