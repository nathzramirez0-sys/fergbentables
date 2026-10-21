package com.blushdesk.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Input rules for products and stock operations, shared by the forms and the repository. */
class InventoryValidationTest {

    @Test
    fun `a SKU is optional, upper-cased, and limited to code characters`() {
        assertNull(Validation.normalizeSku("   "))
        assertEquals("USB-KB/01", Validation.normalizeSku(" usb-kb/01 "))
        assertNull(Validation.sku(""))
        assertNull(Validation.sku("usb-kb.01"))
        assertNotNull(Validation.sku("USB KB"))
        assertNotNull(Validation.sku("-ABC"))
        assertNotNull(Validation.sku("A".repeat(Validation.MAX_SKU + 1)))
    }

    @Test
    fun `cost price is optional but never negative`() {
        assertNull(Validation.costPrice(""))
        assertNull(Validation.costPrice("0"))
        assertNull(Validation.costPrice("950.50"))
        assertNotNull(Validation.costPrice("abc"))
        assertNotNull(Validation.costPrice(Money.of("-1.00")))
    }

    @Test
    fun `stock counts are whole numbers from zero to the maximum`() {
        assertNull(Validation.stockCount("0"))
        assertNull(Validation.stockCount("999999"))
        assertNotNull(Validation.stockCount("1000000"))
        assertNotNull(Validation.stockCount("2.5"))
        assertNotNull(Validation.stockCount(-1))
    }

    @Test
    fun `a delivery adds at least one unit`() {
        assertNotNull(Validation.stockIn("0"))
        assertNull(Validation.stockIn("10"))
        assertNotNull(Validation.stockIn(""))
    }

    @Test
    fun `an adjustment must change something`() {
        assertNotNull(Validation.adjustment(null))
        assertNotNull(Validation.adjustment(0))
        assertNull(Validation.adjustment(-3))
        assertNull(Validation.adjustment(5))
    }

    @Test
    fun `a product needs a name, a unit and a positive price`() {
        assertNull(Validation.productProblem("USB Keyboard", "KB-01", "Electronics", "pc", "", Money.of("850"), null, 5))
        assertNotNull(Validation.productProblem(" ", null, "", "pc", "", Money.of("850"), null, 5))
        assertNotNull(Validation.productProblem("USB Keyboard", null, "", "", "", Money.of("850"), null, 5))
        assertNotNull(Validation.productProblem("USB Keyboard", null, "", "pc", "", Money.ZERO, null, 5))
        assertNotNull(Validation.productProblem("USB Keyboard", null, "", "pc", "", Money.of("850"), Money.of("-5.00"), 5))
        assertNotNull(Validation.productProblem("USB Keyboard", null, "", "pc", "", Money.of("850"), null, -1))
    }
}
