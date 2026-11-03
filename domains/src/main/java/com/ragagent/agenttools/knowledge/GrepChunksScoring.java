package com.ragagent.agenttools.knowledge;

import com.ragagent.platform.knowledge.GrepChunkHit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;



/**
 * grep 检索结果的评分协作者：内容签名去重、正则命中评分（含标题命中）、
 * MMR 多样性重排，以及轻量分词与 Jaccard 相似度。
 */
public final class GrepChunksScoring {

    private GrepChunksScoring() {
    }

    public static List<GrepChunkHit> deduplicateChunks(List<GrepChunkHit> results) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        LinkedHashSet<String> contentSig = new LinkedHashSet<>();
        List<GrepChunkHit> uniqueResults = new ArrayList<>();

        for (GrepChunkHit r : results) {
            List<String> keys = new ArrayList<>();
            keys.add(GrepChunkHit.nz(r.id));
            if (!GrepChunkHit.nz(r.parentChunkId).isEmpty()) {
                keys.add("parent:" + GrepChunkHit.nz(r.parentChunkId));
            }
            if (!GrepChunkHit.nz(r.knowledgeId).isEmpty()) {
                keys.add("kb:" + GrepChunkHit.nz(r.knowledgeId) + "#" + r.chunkIndex);
            }

            boolean dup = false;
            for (String k : keys) {
                if (seen.contains(k)) {
                    dup = true;
                    break;
                }
            }
            if (dup) {
                continue;
            }

            String sig = buildContentSignature(GrepChunkHit.nz(r.content));
            if (!sig.isEmpty() && !contentSig.add(sig)) {
                continue;
            }

            seen.addAll(keys);
            uniqueResults.add(r);
        }

        LinkedHashSet<String> seenById = new LinkedHashSet<>();
        List<GrepChunkHit> deduplicated = new ArrayList<>();
        for (GrepChunkHit r : uniqueResults) {
            if (seenById.add(GrepChunkHit.nz(r.id))) {
                deduplicated.add(r);
            }
        }
        return deduplicated;
    }

    /** 内容签名：小写+trim+折叠空白后 MD5 hex。 */
    public static String buildContentSignature(String content) {
        String c = GrepChunkHit.nz(content).toLowerCase(Locale.ROOT).trim();
        if (c.isEmpty()) {
            return "";
        }
        // 折叠连续空白为单空格
        c = String.join(" ", c.trim().split("\\s+"));
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(c.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte h : hash) {
                sb.append(String.format(Locale.ROOT, "%02x", h & 0xff));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 命中评分：title 命中 +0.5 cap 1.0、patternCount 0 时置 1。 */
    public static List<GrepChunkHit> scoreChunks(List<GrepChunkHit> results, List<Pattern> compiled) {
        List<GrepChunkHit> scored = new ArrayList<>(results.size());
        for (GrepChunkHit r : results) {
            double score;
            int patternCount;
            String content = r.content == null ? "" : r.content;
            if (content.isEmpty() || compiled == null || compiled.isEmpty()) {
                score = 0.0;
                patternCount = 0;
            } else {
                int matchCount = 0;
                int earliestPos = content.length();
                for (Pattern p : compiled) {
                    if (p == null) {
                        continue;
                    }
                    java.util.regex.Matcher m = p.matcher(content);
                    if (m.find()) {
                        matchCount++;
                        if (m.start() < earliestPos) {
                            earliestPos = m.start();
                        }
                    }
                }
                if (matchCount == 0) {
                    score = 0.0;
                    patternCount = 0;
                } else {
                    double baseScore = matchCount / (double) compiled.size();
                    double positionBonus = 0.0;
                    if (earliestPos < content.length()) {
                        double positionRatio = 1.0 - earliestPos / (double) content.length();
                        positionBonus = positionRatio * 0.1;
                    }
                    score = Math.min(baseScore + positionBonus, 1.0);
                    patternCount = matchCount;
                }
            }
            r.matchScore = score;
            r.matchedPatterns = patternCount;
            scored.add(r);
        }
        return scored;
    }

    /** MMR 多样性重排：swap-remove，并列取先出现者（严格 &gt;）。 */
    public static List<GrepChunkHit> applyMMR(List<GrepChunkHit> results, int k, double lambda) {
        if (k <= 0 || results.isEmpty()) {
            return List.of();
        }

        List<GrepChunkHit> selected = new ArrayList<>(k);
        List<Map<String, Boolean>> selectedTokenSets = new ArrayList<>(k);

        List<GrepChunkHit> candidates = new ArrayList<>(results);
        List<Map<String, Boolean>> tokenSets = new ArrayList<>(candidates.size());
        for (GrepChunkHit r : candidates) {
            tokenSets.add(tokenizeSimple(GrepChunkHit.nz(r.content)));
        }

        while (selected.size() < k && !candidates.isEmpty()) {
            int bestIdx = 0;
            double bestScore = -1.0;

            for (int i = 0; i < candidates.size(); i++) {
                double relevance = candidates.get(i).matchScore;
                double redundancy = 0.0;
                for (Map<String, Boolean> selectedTS : selectedTokenSets) {
                    redundancy = Math.max(redundancy, jaccard(tokenSets.get(i), selectedTS));
                }
                double mmr = lambda * relevance - (1.0 - lambda) * redundancy;
                if (mmr > bestScore) {
                    bestScore = mmr;
                    bestIdx = i;
                }
            }

            selected.add(candidates.get(bestIdx));
            selectedTokenSets.add(tokenSets.get(bestIdx));

            int last = candidates.size() - 1;
            candidates.set(bestIdx, candidates.get(last));
            tokenSets.set(bestIdx, tokenSets.get(last));
            candidates.remove(last);
            tokenSets.remove(last);
        }

        return selected;
    }

    /**
     * 轻量空白分词。实现边界：对含中文文本无词典分词，
     * 一律走空白分词（英文/纯空白场景逐位一致）。
     */
    public static Map<String, Boolean> tokenizeSimple(String text) {
        String t = GrepChunkHit.nz(text).toLowerCase(Locale.ROOT).trim();
        if (t.isEmpty()) {
            return Map.of();
        }
        Map<String, Boolean> set = new LinkedHashMap<>();
        for (String w : t.split("\\s+")) {
            w = w.trim();
            // 保留码点数 > 1 且非全标点的词
            if (w.codePointCount(0, w.length()) > 1 && !isAllPunct(w)) {
                set.put(w, Boolean.TRUE);
            }
        }
        return set;
    }

    /** 全部字符都是标点/空白/符号。 */
    public static boolean isAllPunct(String s) {
        for (int i = 0; i < s.length();) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isWhitespace(cp) && Character.getType(cp) != Character.OTHER_PUNCTUATION
                    && Character.getType(cp) != Character.DASH_PUNCTUATION
                    && Character.getType(cp) != Character.START_PUNCTUATION
                    && Character.getType(cp) != Character.END_PUNCTUATION
                    && Character.getType(cp) != Character.CONNECTOR_PUNCTUATION
                    && Character.getType(cp) != Character.INITIAL_QUOTE_PUNCTUATION
                    && Character.getType(cp) != Character.FINAL_QUOTE_PUNCTUATION
                    && Character.getType(cp) != Character.OTHER_SYMBOL
                    && Character.getType(cp) != Character.MATH_SYMBOL
                    && Character.getType(cp) != Character.CURRENCY_SYMBOL
                    && Character.getType(cp) != Character.MODIFIER_SYMBOL) {
                return false;
            }
        }
        return true;
    }

    /** Jaccard 相似度（token 集合交并比）。 */
    public static double jaccard(Map<String, Boolean> a, Map<String, Boolean> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 0;
        }
        if (a.size() > b.size()) {
            return jaccard(b, a);
        }
        int inter = 0;
        for (String k : a.keySet()) {
            if (b.containsKey(k)) {
                inter++;
            }
        }
        int union = a.size() + b.size() - inter;
        if (union == 0) {
            return 0;
        }
        return inter / (double) union;
    }
}
