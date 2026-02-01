package com.foggy.navigator.coding.agent.git.model.v1;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SendMessagePayload {

    private String content;

    @JsonProperty("image_paths")
    private List<String> imagePaths;
}
