package com.phequals7.muesli.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v2.1: stored model selections must keep resolving across modelId and legacy
 * folderName so SherpaRecognizerHolder releases/reloads the right recognizer.
 */
class SherpaOnnxEngineModelTest {

    @Test
    fun `fromModelId resolves catalog entries by modelId and legacy folderName`() {
        assertEquals(
            MuesliAsrModelType.QWEN3_ASR_1_7B_INT8,
            MuesliAsrModelType.fromModelId("qwen3-asr-1.7b-int8")
        )
        assertEquals(
            MuesliAsrModelType.PARAKEET_TDT_0_6B_V3_INT8,
            MuesliAsrModelType.fromModelId("parakeet-tdt-0.6b-v3-int8")
        )
    }

    @Test
    fun `fromModelId falls back to the default model for unknown or null ids`() {
        assertEquals(MuesliAsrModelType.QWEN3_ASR_0_6B_INT8, MuesliAsrModelType.fromModelId("does-not-exist"))
        assertEquals(MuesliAsrModelType.QWEN3_ASR_0_6B_INT8, MuesliAsrModelType.fromModelId(null))
    }
}
