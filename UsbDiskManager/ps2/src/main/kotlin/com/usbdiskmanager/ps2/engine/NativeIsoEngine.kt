package com.usbdiskmanager.ps2.engine

import com.usbdiskmanager.ps2.domain.model.ConversionProgress
import kotlinx.coroutines.flow.Flow

/**
 * Future NDK-backed [IsoEngine] implementation.
 *
 * Will delegate to native code (libcdvd / Open PS2 Loader) via JNI.
 *
 * To activate: change the Hilt binding in [com.usbdiskmanager.ps2.di.Ps2Module]:
 *   `abstract fun bindIsoEngine(impl: NativeIsoEngine): IsoEngine`
 *
 * JNI bridge stubs are defined below — implement with CMake + NDK.
 */
class NativeIsoEngine : IsoEngine {

    // JNI declarations — implemented in native/ps2_engine.cpp
    // external fun nativeExtractGameId(path: String): String
    // external fun nativeGetIsoInfo(path: String): ByteArray   // JSON-encoded IsoInfo
    // external fun nativeConvertToUl(input: String, output: String, resumeOffset: Long): Int

    override suspend fun extractGameId(path: String): String {
        throw UnsupportedOperationException(
            "NativeIsoEngine is not yet implemented. " +
            "Activate it in Ps2Module once NDK integration is complete."
        )
    }

    override suspend fun getIsoInfo(path: String): IsoInfo {
        throw UnsupportedOperationException("NativeIsoEngine not yet implemented")
    }

    override fun convertToUl(input: String, output: String, resumeOffset: Long): Flow<ConversionProgress> {
        throw UnsupportedOperationException("NativeIsoEngine not yet implemented")
    }

    override fun calculateResumeOffset(outputDir: String, gameId: String): Long {
        throw UnsupportedOperationException("NativeIsoEngine not yet implemented")
    }

    override fun deletePartFiles(outputDir: String, gameId: String) {
        throw UnsupportedOperationException("NativeIsoEngine not yet implemented")
    }

    companion object {
        // Load the native library when activated
        // init { System.loadLibrary("ps2engine") }
    }
}
