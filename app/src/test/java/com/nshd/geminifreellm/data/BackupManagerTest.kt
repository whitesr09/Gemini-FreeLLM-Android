package com.nshd.geminifreellm.data

import org.junit.Assert.assertThrows
import org.junit.Test

class BackupManagerTest {
    @Test fun exportAcceptsEntriesWithinImportLimits() {
        BackupManager.validateExportLimits(listOf(
            "manifest.json" to 512L,
            "conversations.json" to 1024L,
            "providers.enc" to 256L,
            "attachments/file.txt" to 4096L
        ))
    }

    @Test fun exportRejectsAnEntryTooLargeForImport() {
        assertThrows(BackupException::class.java) {
            BackupManager.validateExportLimits(listOf("conversations.json" to (32L * 1024 * 1024 + 1)))
        }
    }

    @Test fun exportRejectsTotalExpandedSizeAboveImportLimit() {
        assertThrows(BackupException::class.java) {
            BackupManager.validateExportLimits(listOf(
                "conversations.json" to (32L * 1024 * 1024),
                "attachments/a.bin" to (32L * 1024 * 1024 + 1)
            ))
        }
    }

    @Test fun exportRejectsTooManyEntries() {
        assertThrows(BackupException::class.java) {
            BackupManager.validateExportLimits((0..256).map { "attachments/$it.bin" to 1L })
        }
    }
    @Test fun exportRejectsUnreadableOrIncompleteHistory() {
        assertThrows(BackupException::class.java) { BackupManager.validateExportHistory("not-json".toByteArray()) }
        assertThrows(BackupException::class.java) { BackupManager.validateExportHistory("""{"active":"x"}""".toByteArray()) }
        BackupManager.validateExportHistory("""{"chats":[],"active":""}""".toByteArray())
    }

    @Test fun exportRejectsAttachmentNamesTheImporterWouldReject() {
        BackupManager.validateExportAttachmentNames(listOf("notes.txt", "image.png"))
        assertThrows(BackupException::class.java) { BackupManager.validateExportAttachmentNames(listOf("nested/file.txt")) }
        assertThrows(BackupException::class.java) { BackupManager.validateExportAttachmentNames(listOf("bad\\name.txt")) }
    }

}
