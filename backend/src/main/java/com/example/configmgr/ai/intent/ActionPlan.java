package com.example.configmgr.ai.intent;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public record ActionPlan(@JsonProperty("actions") List<Action> actions) {

    public ActionPlan {
        actions = actions == null ? List.of() : List.copyOf(actions);
    }

    public record Action(@JsonProperty("id") String id, @JsonProperty("tool") String tool,
                         @JsonProperty("arguments") JsonNode arguments,
                         @JsonProperty("unresolved") List<String> unresolved,
                         @JsonProperty("dependsOn") List<String> dependsOn) {

        public Action {
            unresolved = unresolved == null ? List.of() : List.copyOf(unresolved);
            dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        }
    }
}
