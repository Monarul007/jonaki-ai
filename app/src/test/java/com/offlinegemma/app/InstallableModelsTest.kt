package com.offlinegemma.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a phone is offered, and what it merely warns about.
 *
 * The first version of this rule blocked E4B on any phone under 11 GB of total RAM, after an 8 GB
 * phone was made unusable by it. That was wrong, and a second phone proved it: a motorola edge 50
 * fusion reports the **same 7.56 GB** and runs E4B steadily (Snapdragon 7s Gen 3, Adreno 710, 3.1 GB
 * free, 5.7 GB swap). Total RAM does not tell those two apart, so it cannot be the verdict â€” the
 * model is offered, and the phone settles it at load time.
 */
class InstallableModelsTest {

  private val e2b = ChatModel.E2B
  private val e4b = ChatModel.E4B
  private val roomy = 50_000_000_000L

  /** What ActivityManager reports on the hardware these numbers came from. */
  private val fourGbPhone = 3_700_000_000L
  private val edge50Fusion = 7_562_584_000L     // MemTotal: 7562584 kB, runs E4B fine
  private val redmiThatStruggled = 7_556_364_000L
  private val twelveGbPhone = 11_500_000_000L

  /** Neither an 8 GB phone is refused E4B or E2B: both are offered, because storage allows it. */
  @Test fun aSevenAndAHalfGbPhoneIsOfferedEveryModel() {
    assertEquals(Fit.READY, ModelInfo.fit(e2b, edge50Fusion, roomy, 0))
    assertEquals(Fit.READY, ModelInfo.fit(e4b, edge50Fusion, roomy, 0))
    assertEquals(Fit.READY, ModelInfo.fit(e4b, redmiThatStruggled, roomy, 0))
  }

  /** The two phones whose behaviour disagreed are now treated identically â€” that is the point. */
  @Test fun twoIdenticalPhonesAreNotJudgedDifferently() {
    assertEquals(
      ModelInfo.fit(e4b, edge50Fusion, roomy, 0),
      ModelInfo.fit(e4b, redmiThatStruggled, roomy, 0),
    )
  }

  /** Storage is the one hard requirement. */
  @Test fun lowStorageBlocksInstall() {
    assertEquals(Fit.NO_STORAGE, ModelInfo.fit(e2b, edge50Fusion, 2_000_000_000L, 0))
    // A paused download already holds half, so only the rest has to fit.
    val half = e2b.SIZE_BYTES / 2
    assertEquals(Fit.READY, ModelInfo.fit(e2b, edge50Fusion, 2_500_000_000L, half))
  }

  /**
   * E2B must never be called a big model for a phone that can run it â€” including the 4 GB OPPO it was
   * measured on. A warning that fires on the recommended model is noise.
   */
  @Test fun theSmallModelIsComfortableEverywhereItIsOffered() {
    assertTrue(ModelInfo.roomyEnough(e2b, fourGbPhone))
    assertTrue(ModelInfo.roomyEnough(e2b, edge50Fusion))
  }

  /** E4B on a 7.5 GB phone is a warning, and a warning is all it may be. */
  @Test fun theBigModelOnASevenAndAHalfGbPhoneIsAdviceNotAPermission() {
    assertFalse(ModelInfo.roomyEnough(e4b, edge50Fusion))
    assertEquals(Fit.READY, ModelInfo.fit(e4b, edge50Fusion, roomy, 0))
  }

  @Test fun aBigPhoneIsNotWarnedAtAll() {
    assertTrue(ModelInfo.roomyEnough(e4b, twelveGbPhone))
  }
}

