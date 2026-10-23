package app.freerouting;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AgentGuideTest {

  @Test
  void theBuildPacksTheGuideIntoTheResources() {
    String guide = AgentGuide.text();
    assertNotNull(guide, "docs/agent-guide.md should be copied to " + AgentGuide.RESOURCE);
    assertTrue(guide.startsWith("# Freerouting agent guide"));
  }

  @Test
  void theGuideNamesTheResultFieldsAndLogLinesAnAgentReads() {
    String guide = AgentGuide.text();
    for (String name :
        new String[] {
          "job_timeout",
          "result_json",
          "status_file",
          "stop_file",
          "open_connections",
          "placement_hints",
          "crowded_regions",
          "verdict",
          "Verdict:",
          "Crowded region:",
          "Placement hint:",
          "Pad neck used:",
          AgentGuide.FLAG
        }) {
      assertTrue(guide.contains(name), "the guide should mention " + name);
    }
  }

  @Test
  void onlyTheFlagRequestsTheGuide() {
    assertTrue(AgentGuide.requested(new String[] {"--agent-guide"}));
    assertTrue(AgentGuide.requested(new String[] {"-de", "board.dsn", "--AGENT-GUIDE"}));
    assertFalse(AgentGuide.requested(new String[] {"-de", "board.dsn"}));
    assertFalse(AgentGuide.requested(null));
  }
}
