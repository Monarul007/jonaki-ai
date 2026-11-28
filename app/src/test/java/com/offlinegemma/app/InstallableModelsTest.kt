package com.offlinegemma.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which models a phone offers for one-time download, from the numbers it can actually spare. */
class InstallableModelsTest {

  private val e2b = ChatModel.E2B
  private val e4b = ChatModel.E4B

  @Test fun bothModelsInstallWhenMemoryAndStorageAreFine() {
    val got = ModelInfo.installableModels(listOf(e2b, e4b), availableRamBytes = 8_000_000_000L, freeBytes = 10_000_000_000L)
    assertEquals(listOf(e2b, e4b), got)
  }

  @Test fun noModelInstallsWhenAvailableMemoryIsTooLow() {
    val got = ModelInfo.installableModels(listOf(e2b, e4b), availableRamBytes = 5_000_000_000L, freeBytes = 10_000_000_000L)
    assertTrue(got.isEmpty())
  }

  @Test fun noModelInstallsWhenFreeStorageIsTooLow() {
    val got = ModelInfo.installableModels(listOf(e2b, e4b), availableRamBytes = 8_000_000_000L, freeBytes = 2_000_000_000L)
    assertTrue(got.isEmpty())
  }
}
