package io.github.pahpuhbinny.multiview

import io.github.pahpuhbinny.multiview.download.Net
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NetTest {
    private fun bytes(vararg b: Int) = ByteArray(32).also { a -> b.forEachIndexed { i, v -> a[i] = v.toByte() } }
    private fun text(s: String) = s.toByteArray().copyOf(64)

    @Test
    fun rejectsProgramsArchivesDocumentsAndWebPages() {
        val bad = listOf(
            bytes(0x50, 0x4B, 0x03, 0x04),          // zip / apk
            bytes(0x4D, 0x5A, 0x90, 0x00),          // windows exe
            bytes(0x7F, 0x45, 0x4C, 0x46),          // elf
            bytes(0x64, 0x65, 0x78, 0x0A),          // dex
            bytes(0x25, 0x50, 0x44, 0x46),          // pdf
            bytes(0x52, 0x61, 0x72, 0x21),          // rar
            text("#!/bin/sh\nrm -rf"),
            text("  <!DOCTYPE html><html>"),
            text("<html><body>"),
        )
        for (b in bad) assertNotNull(String(b.copyOf(8)), Net.rejectReason(b, b.size))
    }

    @Test
    fun acceptsRealVideoHeaders() {
        val good = listOf(
            bytes(0x00, 0x00, 0x00, 0x20, 0x66, 0x74, 0x79, 0x70),   // mp4 "ftyp"
            bytes(0x1A, 0x45, 0xDF, 0xA3),                         // webm / mkv EBML
            bytes(0x47, 0x40, 0x11, 0x10),                         // mpeg-ts sync byte
        )
        for (b in good) assertNull(Net.rejectReason(b, b.size))
    }
}
