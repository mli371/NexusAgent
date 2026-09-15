package com.nexusagent.embeddings.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nexus.embeddings")
public class EmbeddingProperties {

    private String provider = "local";
    private int dimension = 384;
    private String model = "text-embedding-3-small";

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public int getDimension() {
        return dimension;
    }

    public void setDimension(int dimension) {
        this.dimension = dimension;
    }
}
