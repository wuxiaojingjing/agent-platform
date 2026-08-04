package com.huawei.finance.registry.asset;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 改写归一词表（v0.7 §2.5.4：改写仅服务检索）。
 *
 * <p>替换按 key 长度降序进行。若按字典序或插入序替换，「卡里还有多少钱」会先被
 * 「还有多少钱」命中，剩下「卡里余额」这种半成品，反而比不改写更糟。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SynonymTable {

    private Map<String, String> replacements = Map.of();
    private Map<String, String> corrections = Map.of();
    private List<String> stopwords = List.of();

    /** 按长度降序排好的替换项，加载后固定，避免每次改写重排。 */
    private List<Map.Entry<String, String>> orderedReplacements = List.of();

    public Map<String, String> getReplacements() {
        return replacements;
    }

    public void setReplacements(Map<String, String> replacements) {
        this.replacements = replacements == null ? Map.of() : new LinkedHashMap<>(replacements);
        List<Map.Entry<String, String>> ordered = new ArrayList<>(this.replacements.entrySet());
        ordered.sort(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().length()).reversed());
        this.orderedReplacements = List.copyOf(ordered);
    }

    public List<Map.Entry<String, String>> orderedReplacements() {
        return orderedReplacements;
    }

    public Map<String, String> getCorrections() {
        return corrections;
    }

    public void setCorrections(Map<String, String> corrections) {
        this.corrections = corrections == null ? Map.of() : Map.copyOf(corrections);
    }

    public List<String> getStopwords() {
        return stopwords;
    }

    public void setStopwords(List<String> stopwords) {
        this.stopwords = stopwords == null ? List.of() : List.copyOf(stopwords);
    }
}
