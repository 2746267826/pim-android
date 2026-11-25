package com.pim.app.notes

import java.io.FileNotFoundException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoteA03PresentationTest {
    @Test fun saveStatusUsesFailureThenReviewThenLocalSavedPriority() {
        assertEquals(
            NoteSaveState.SaveFailed,
            noteSaveState(saved = true, saveFailed = true, needsReview = true),
        )
        assertEquals(
            NoteSaveState.NeedsReview,
            noteSaveState(saved = true, saveFailed = false, needsReview = true),
        )
        assertEquals(
            NoteSaveState.SavedLocally,
            noteSaveState(saved = true, saveFailed = false, needsReview = false),
        )
        assertEquals(
            NoteSaveState.Saving,
            noteSaveState(saved = false, saveFailed = false, needsReview = false),
        )
        assertEquals("保存失败", noteSaveStateLabel(NoteSaveState.SaveFailed))
        assertEquals("待核对正文", noteSaveStateLabel(NoteSaveState.NeedsReview))
        assertEquals("已保存本机", noteSaveStateLabel(NoteSaveState.SavedLocally))
        assertEquals("保存中", noteSaveStateLabel(NoteSaveState.Saving))
    }

    @Test fun newestUploadIsTheCurrentNoteStatusAndTiesPreferTheMoreSeriousPhase() {
        val noteId = newNoteId()
        val oldFailure = job(noteId, NoteUploadPhase.Failed, createdAt = 10, id = "old-failure")
        val newSuccess = job(noteId, NoteUploadPhase.Uploaded, createdAt = 20, id = "new-success")
        assertEquals(
            newSuccess,
            latestUploadJobForNote(listOf(oldFailure, newSuccess), noteId),
        )

        val sameTimeFailure = job(noteId, NoteUploadPhase.Failed, createdAt = 20, id = "failure")
        val sameTimeConflict = job(noteId, NoteUploadPhase.Conflict, createdAt = 20, id = "conflict")
        assertEquals(
            sameTimeConflict,
            latestUploadJobForNote(listOf(sameTimeFailure, sameTimeConflict), noteId),
        )
        assertNull(latestUploadJobForNote(listOf(oldFailure), newNoteId()))
    }

    @Test fun onlyActionableUploadStatesProduceANoteNotice() {
        val noteId = newNoteId()
        assertEquals("上传冲突 · 需核对版本", noteUploadNotice(job(noteId, NoteUploadPhase.Conflict))?.label)
        assertEquals("上传未完成 · 需重试", noteUploadNotice(job(noteId, NoteUploadPhase.Failed))?.label)
        assertEquals("上传需原账号登录", noteUploadNotice(job(noteId, NoteUploadPhase.NeedsLogin))?.label)
        assertEquals("上传已暂停", noteUploadNotice(job(noteId, NoteUploadPhase.Paused))?.label)
        assertEquals("等待网络上传", noteUploadNotice(job(noteId, NoteUploadPhase.WaitingForNetwork))?.label)
        assertEquals("正在上传材料", noteUploadNotice(job(noteId, NoteUploadPhase.Uploading))?.label)
        assertEquals("等待上传材料", noteUploadNotice(job(noteId, NoteUploadPhase.Queued))?.label)
        assertNull(noteUploadNotice(job(noteId, NoteUploadPhase.Uploaded)))
        assertNull(noteUploadNotice(null))
    }

    @Test fun saveProblemKeepsTheLocalCauseWithoutLeakingRawPaths() {
        assertEquals(
            "本机存储空间不足",
            noteSaveProblemText(IOException("ENOSPC: no space left on device, open /data/private/notes/x.json")),
        )
        assertEquals(
            "没有写入本机存储的权限",
            noteSaveProblemText(SecurityException("permission denied")),
        )
        assertEquals(
            "本机存储目录不可用",
            noteSaveProblemText(FileNotFoundException("/data/private/notes/x.json")),
        )
        assertEquals(
            "本机文件写入失败",
            noteSaveProblemText(IOException("bad checksum")),
        )
        assertEquals(
            "本机保存发生异常",
            noteSaveProblemText(IllegalStateException("unexpected")),
        )
    }

    private fun job(
        noteId: String,
        phase: NoteUploadPhase,
        createdAt: Long = 1,
        id: String = newNoteId(),
    ) = NoteUploadJob(
        id = id,
        versionId = newNoteId(),
        noteId = noteId,
        revision = 1,
        serverIdentity = "https://pim.example.test",
        ownerId = "owner",
        ownerName = "测试账号",
        inputSha256 = "a".repeat(64),
        manifestSha256 = "b".repeat(64),
        baseVersionId = null,
        createdAtMillis = createdAt,
        phase = phase,
        totalBytes = 1024,
    )
}
