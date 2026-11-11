package com.jarvis.phone

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** 화면이 닫혀도 계속 돌아야 하는 작업(모델 복사 등)용 앱 전역 코루틴 스코프. */
object AppScope {
    private val job = SupervisorJob()
    val io = CoroutineScope(job + Dispatchers.IO)
    val main = CoroutineScope(job + Dispatchers.Main)
}
