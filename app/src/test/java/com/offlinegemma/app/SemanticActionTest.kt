package com.offlinegemma.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What tapping "Semantic study search" did, and must no longer do.
 *
 * Reported from a fresh install: the 485 MB embedder finished downloading and "nothing happened" —
 * no indexing progress, and tapping the row again started the download over. Both come from the same
 * place: the row had no idea whether a job owned the file, and the tap simply re-queued the work.
 */
class SemanticActionTest {

  @Test fun tapAfterIndexIsBuiltDoesNothing() {
    assertEquals(
      SemanticAction.NOTHING,
      SemanticAction.forTap(ready = true, running = false, downloaded = true),
    )
  }

  @Test fun tapDuringADownloadNeverRestartsIt() {
    // The bug: this used to enqueue a REPLACE and reset the progress bar of a healthy download.
    assertEquals(
      SemanticAction.NOTHING,
      SemanticAction.forTap(ready = false, running = true, downloaded = false),
    )
    // Even with every byte already on disk, a live job must be left alone to verify and rename.
    assertEquals(
      SemanticAction.NOTHING,
      SemanticAction.forTap(ready = false, running = true, downloaded = true),
    )
  }

  @Test fun tapAfterTheDownloadIndexesInsteadOfDownloading() {
    // The other half of the report: the file was complete, so the only missing step was indexing.
    assertEquals(
      SemanticAction.INDEX,
      SemanticAction.forTap(ready = false, running = false, downloaded = true),
    )
  }

  @Test fun freshInstallStartsTheDownload() {
    assertEquals(
      SemanticAction.DOWNLOAD,
      SemanticAction.forTap(ready = false, running = false, downloaded = false),
    )
  }

  @Test fun everyTapEndsInAStateThatCanFinish() {
    // No combination may leave the row unable to make progress: each answer either finishes the job,
    // starts the missing half, or deliberately defers to work already in flight.
    for (ready in listOf(false, true)) {
      for (running in listOf(false, true)) {
        for (downloaded in listOf(false, true)) {
          val a = SemanticAction.forTap(ready, running, downloaded)
          val stalled = a == SemanticAction.NOTHING && !ready && !running
          assertEquals(
            "tap(ready=$ready, running=$running, downloaded=$downloaded) = $a would do nothing" +
              " while nothing is running",
            false,
            stalled,
          )
        }
      }
    }
  }
}