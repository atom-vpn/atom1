package com.atomvpn.mobile.engine.atom

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AtomPhase {
    IDLE,
    STARTING,
    OUTER,
    INNER,
    BRIDGING,
    CONNECTED,
    FAILED,
}

enum class AtomStepState {
    RUNNING,
    DONE,
    FAILED,
}

data class AtomStep(
    val text: String,
    val state: AtomStepState,
)

data class AtomUiStatus(
    val phase: AtomPhase = AtomPhase.IDLE,
    val progressPercent: Int = 0,
    val detail: String = "",
    val outerLabel: String = "",
    val exitRegion: String = "",
    val steps: List<AtomStep> = emptyList(),
) {
    companion object {
        val Idle = AtomUiStatus()
    }
}

object AtomStatusStore {
    private val mutableStatus = MutableStateFlow(AtomUiStatus.Idle)
    val status: StateFlow<AtomUiStatus> = mutableStatus.asStateFlow()

    fun update(
        phase: AtomPhase,
        progressPercent: Int = 0,
        detail: String = "",
        outerLabel: String = mutableStatus.value.outerLabel,
        exitRegion: String = mutableStatus.value.exitRegion,
    ) {
        val current = mutableStatus.value
        mutableStatus.value = AtomUiStatus(
            phase = phase,
            progressPercent = progressPercent.coerceIn(0, 100),
            detail = detail,
            outerLabel = outerLabel,
            exitRegion = exitRegion,
            steps = current.steps,
        )
        if (detail.isNotBlank()) note(detail)
        if (phase == AtomPhase.CONNECTED) finish(AtomStepState.DONE)
        if (phase == AtomPhase.FAILED) finish(AtomStepState.FAILED)
    }

    fun pulse(text: String) {
        val clean = text.trim().take(180)
        if (clean.isEmpty()) return
        val current = mutableStatus.value
        val steps = current.steps.toMutableList()
        val last = steps.lastOrNull()
        if (last != null && last.state == AtomStepState.RUNNING && last.text.startsWith("Scan ")) {
            if (last.text == clean) return
            steps[steps.lastIndex] = last.copy(text = clean)
            mutableStatus.value = current.copy(steps = steps)
            return
        }
        note(clean)
    }

    fun scan(text: String, outcome: String) {
        val clean = text.trim().take(180)
        if (clean.isEmpty()) return
        val state = when (outcome) {
            "trying" -> AtomStepState.RUNNING
            "accepted" -> AtomStepState.DONE
            else -> AtomStepState.FAILED
        }
        val current = mutableStatus.value
        val steps = current.steps.toMutableList()
        val last = steps.lastOrNull()
        if (last != null && last.state == AtomStepState.RUNNING && last.text.startsWith("Scan ")) {
            steps[steps.lastIndex] = AtomStep(clean, state)
            mutableStatus.value = current.copy(steps = steps)
            return
        }
        if (last != null && last.state == AtomStepState.RUNNING) {
            steps[steps.lastIndex] = last.copy(state = AtomStepState.DONE)
        }
        steps += AtomStep(clean, state)
        mutableStatus.value = current.copy(steps = steps.takeLast(24))
    }

    fun note(text: String, failed: Boolean = false) {
        val clean = text.trim().take(180)
        if (clean.isEmpty()) return
        val current = mutableStatus.value
        val steps = current.steps.toMutableList()
        val last = steps.lastOrNull()
        if (last != null && last.text == clean) return
        if (last != null && last.state == AtomStepState.RUNNING) {
            steps[steps.lastIndex] = last.copy(state = AtomStepState.DONE)
        }
        steps += AtomStep(clean, if (failed) AtomStepState.FAILED else AtomStepState.RUNNING)
        mutableStatus.value = current.copy(steps = steps.takeLast(24))
    }

    private fun finish(state: AtomStepState) {
        val current = mutableStatus.value
        val last = current.steps.lastOrNull() ?: return
        if (last.state != AtomStepState.RUNNING) return
        val steps = current.steps.toMutableList()
        steps[steps.lastIndex] = last.copy(state = state)
        mutableStatus.value = current.copy(steps = steps)
    }

    fun reset() {
        mutableStatus.value = AtomUiStatus.Idle
    }
}
