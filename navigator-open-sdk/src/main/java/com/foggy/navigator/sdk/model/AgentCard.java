package com.foggy.navigator.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Agent Card（A2A 协议）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentCard {
    private String id;
    private String name;
    private String description;
    private String url;
    private String version;
    private List<Skill> skills;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Skill {
        private String id;
        private String name;
        private String description;
        private List<String> tags;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public List<String> getTags() { return tags; }
        public void setTags(List<String> tags) { this.tags = tags; }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public List<Skill> getSkills() { return skills; }
    public void setSkills(List<Skill> skills) { this.skills = skills; }

    @Override
    public String toString() {
        return "AgentCard{id='" + id + "', name='" + name + "'}";
    }
}
