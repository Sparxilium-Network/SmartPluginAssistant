package com.chiliasmstudio.smartpluginassistant.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ModrinthSearchResponse {
    @JsonProperty("hits")
    private List<ModrinthSearchResult> hits;

    @JsonProperty("offset")
    private int offset;

    @JsonProperty("limit")
    private int limit;

    @JsonProperty("total_hits")
    private int totalHits;

    public List<ModrinthSearchResult> getHits() {
        return hits;
    }

    public void setHits(List<ModrinthSearchResult> hits) {
        this.hits = hits;
    }

    public int getOffset() {
        return offset;
    }

    public void setOffset(int offset) {
        this.offset = offset;
    }

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }

    public int getTotalHits() {
        return totalHits;
    }

    public void setTotalHits(int totalHits) {
        this.totalHits = totalHits;
    }
}
