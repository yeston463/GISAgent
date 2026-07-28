package org.example.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ClarificationEngineTest {

    @Test
    void recognizesChinesePlaceAndAnalysisRequest() {
        ClarificationEngine engine = new ClarificationEngine();

        assertFalse(engine.needsClarification("分析清华大学周边500m建筑情况"));
    }
}
