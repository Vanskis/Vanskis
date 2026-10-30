package com.vanskis.liquidpocket.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoResultsTest {
    @Test fun `shown ids come back in order`() {
        assertEquals(listOf(46620L, 46619L), PhotoResults.shownIds("""{"shown":[46620,46619],"note":"The photos follow in this order."}"""))
    }

    @Test fun `anything else has none`() {
        assertEquals(emptyList<Long>(), PhotoResults.shownIds("Declined"))
        assertEquals(emptyList<Long>(), PhotoResults.shownIds("""{"error":"no permission"}"""))
    }

    @Test fun `uri is the MediaStore image`() {
        assertEquals("content://media/external/images/media/7", PhotoResults.uri(7))
    }
}
