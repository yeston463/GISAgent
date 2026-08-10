package org.example.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GisMapToolsTest {
    @Test
    void aiSelectedAdcodeMustBeAnEligibleProviderCandidate() throws Exception {
        var districts = new ObjectMapper().readTree("""
                [{"name":"\u4e2d\u56fd","adcode":"100000","polyline":"1,1;2,2;3,3"},
                 {"name":"\u627f\u5fb7\u5e02","adcode":"130800","polyline":"1,1;2,2;3,3"}]
                """);

        assertEquals("\u627f\u5fb7\u5e02", GisMapTools
                .districtForAdcode(districts, "130800")
                .path("name").asText());
        assertTrue(GisMapTools.districtForAdcode(districts, "100000").isMissingNode());
    }
}
