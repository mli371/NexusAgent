package com.nexusagent.chunking.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nexus.chunking")
public class ChunkingProperties {

    private int parentMaxChars = 1200;
    private int childMaxChars = 400;
    private int childOverlapChars = 80;

    public int getParentMaxChars() {
        return parentMaxChars;
    }

    public void setParentMaxChars(int parentMaxChars) {
        this.parentMaxChars = parentMaxChars;
    }

    public int getChildMaxChars() {
        return childMaxChars;
    }

    public void setChildMaxChars(int childMaxChars) {
        this.childMaxChars = childMaxChars;
    }

    public int getChildOverlapChars() {
        return childOverlapChars;
    }

    public void setChildOverlapChars(int childOverlapChars) {
        this.childOverlapChars = childOverlapChars;
    }
}
