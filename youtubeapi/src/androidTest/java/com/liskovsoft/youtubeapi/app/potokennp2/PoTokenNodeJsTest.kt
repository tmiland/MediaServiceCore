package com.liskovsoft.youtubeapi.app.potokennp2

import androidx.test.platform.app.InstrumentationRegistry
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import com.liskovsoft.youtubeapi.app.potokennp2.generators.PoTokenNodeJs
import org.junit.Assert
import org.junit.Before
import org.junit.Test

private const val VIDEO_ID = "K04WmBtVsOs"

class PoTokenNodeJsTest {
    @Before
    fun setUp() {
        GlobalPreferences.instance(InstrumentationRegistry.getInstrumentation().context)
        PoTokenProviderImpl.resetCache()
        PoTokenProviderImpl.poTokenFactory = PoTokenNodeJs
    }

    @Test
    fun testWebPoTokenIsNotEmptyNodeJs() {
        val webClientPoToken = PoTokenProviderImpl.getWebClientPoToken(VIDEO_ID)

        Assert.assertNotNull("PoToken not empty", webClientPoToken)
        Assert.assertTrue("PoToken not empty", webClientPoToken?.playerRequestPoToken?.length ?: 0 > 10)
    }

    @Test
    fun testNodeJsMinterDirect() {
        val generator = PoTokenNodeJs.newPoTokenGenerator(InstrumentationRegistry.getInstrumentation().context)

        try {
            val pot = generator.generatePoToken(VIDEO_ID)
            Assert.assertTrue("PoToken not empty", pot.length > 10)
        } finally {
            generator.close()
        }
    }

    @Test
    fun testWebPoTokenOnEmptyVideoIdNodeJs() {
        val webClientPoToken = PoTokenProviderImpl.getWebClientPoToken("")

        Assert.assertNotNull("PoToken not empty", webClientPoToken)
        Assert.assertTrue("Streaming poToken not empty", webClientPoToken?.streamingDataPoToken?.length ?: 0 > 10)
    }
}