/* (C) 2026 Bengkel Scan contributors. GPL-2.0-or-later. */
package com.fr3ts0n.ecu.gui.androbd.diagnosis.data

import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisScorer
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.ExpertKnowledge
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.TriageResult
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.TriageStatus
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.VehicleContext

/** Uses Phase 1's explainable scoring contract, never a made-up probability mixture. */
object AudioExpertFusion {
    fun merge(
        metadata: AudioModelMetadata,
        knowledge: ExpertKnowledge,
        vehicle: VehicleContext,
        symptoms: Set<String>,
        probabilities: Map<String, Double>,
        rejected: Boolean,
    ): TriageResult? {
        // A synthetic fixture can NEVER produce a vehicle diagnosis, even through an API caller.
        if (metadata.synthetic) return null
        val weighted = knowledge.copy(scoring = knowledge.scoring.copy(
            audioWeight = knowledge.scoring.audioWeight * metadata.fusionAudioWeightMultiplier))
        val result = DiagnosisScorer.rank(weighted, vehicle, symptoms, if (rejected) emptyMap() else probabilities)
        // Retain every safety result while abstaining on unsupported propulsion systems.
        if (vehicle.fuelType in setOf("hybrid", "electric")) {
            return result.copy(causes = emptyList(), status = TriageStatus.UNCERTAIN)
        }
        return result
    }
}
