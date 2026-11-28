package hunter.core

interface StreamSession {
    suspend fun start()
    suspend fun stop()
}
