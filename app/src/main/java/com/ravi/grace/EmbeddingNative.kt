package com.ravi.grace

/** CPU-only JNI bridge for the locally installed public EmbeddingGemma GGUF. */
object EmbeddingNative {
    init { System.loadLibrary("grace_embedding") }
    external fun open(modelPath: String): Boolean
    external fun embed(text: String): FloatArray?
    external fun close()
}
