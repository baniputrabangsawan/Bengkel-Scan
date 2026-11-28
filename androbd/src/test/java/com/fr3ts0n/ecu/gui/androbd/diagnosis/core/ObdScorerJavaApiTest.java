package com.fr3ts0n.ecu.gui.androbd.diagnosis.core;

import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.ExpertKnowledgeLoader;
import org.junit.Test;

import java.io.InputStream;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/** Keep existing Java entry points while adding the optional cause-level argument. */
public class ObdScorerJavaApiTest {
    @Test public void existingThreeAndFourArgumentCallsRemainSourceCompatible() {
        InputStream input = getClass().getClassLoader().getResourceAsStream("diagnosis/expert-knowledge.json");
        assertNotNull(input);
        ExpertKnowledge knowledge = ExpertKnowledgeLoader.load(input);
        VehicleContext context = new VehicleContext();
        TriageResult three = DiagnosisScorer.rank(knowledge, context, Collections.singleton("belt_squeal"));
        TriageResult four = DiagnosisScorer.rank(knowledge, context, Collections.singleton("belt_squeal"), Collections.emptyMap());
        TriageResult five = DiagnosisScorer.rank(knowledge, context, Collections.singleton("belt_squeal"),
                Collections.emptyMap(), Collections.emptyMap());
        assertEquals(three, four);
        assertEquals(four, five);
    }
}
