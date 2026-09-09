package com.allai.automation

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

// DolaAutomationService: клики по тексту через OCR/Accessibility.
// Шаги: login_google(index) -> enablePro() -> paste(prompt) -> waitGenerate() -> download() -> lastFrame() -> logout()
class DolaAutomationService : AccessibilityService() {
    companion object { @Volatile var stopRequested = false }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (stopRequested) return
        // TODO: findNodesByText("Sign in with Google"), ("Generate"), OCR polling
    }
    override fun onInterrupt() {}
}
