package com.nexusagent.query.live;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Conservative lexical guards, not an intent model or an equivalence guarantee. */
final class SemanticReusePolicy {
    static final String VERSION = "lexical-cosine-v1";
    private static final Pattern COMPARISON = Pattern.compile("比较|对比|差异|区别|不同|\\b(compare|comparison|differences?|differ|versus|vs)\\b");
    private static final Pattern HIGHLIGHTS = Pattern.compile("亮点|特色|特别|独特|突出|\\b(highlights?|special|unique|notable|standout)\\b");
    private static final Pattern LOOKUP = Pattern.compile("什么|哪些|多少|何时|哪年|如何|是否|\\b(what|which|when|how|does|is|are)\\b");
    private static final Pattern NEGATION = Pattern.compile("不|未|没有|无|禁止|别|\\b(no|not|never|without|cannot|forbidden|prohibited|excluding)\\b|n't\\b");
    private static final Pattern CONSTRAINT = Pattern.compile("[0-9]+(?:\\.[0-9]+)?|[〇零一二三四五六七八九十百千万亿两]+|今年|去年|前年|明年|本月|上月|下月|最新|现在|\\b(this year|last year|next year|latest|today|yesterday)\\b");

    enum Intent { COMPARISON, HIGHLIGHTS, FACT_LOOKUP }
    record Features(Intent intent, String constraintHash, boolean negative) { }
    record Match(SemanticContextCache.Source source, double similarity) { }
    record Ranking(List<Match> matches, Double bestSimilarity, String reason) { }

    Optional<Features> features(String question) {
        String text = Normalizer.normalize(question, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        boolean compare = COMPARISON.matcher(text).find(), highlights = HIGHLIGHTS.matcher(text).find();
        if (compare && highlights) { return Optional.empty(); }
        Intent intent = compare ? Intent.COMPARISON : highlights ? Intent.HIGHLIGHTS
                : LOOKUP.matcher(text).find() ? Intent.FACT_LOOKUP : null;
        if (intent == null) { return Optional.empty(); }
        var negations = NEGATION.matcher(text);
        boolean negative = negations.find();
        if (negative && negations.find()) { return Optional.empty(); }
        var constraints = new TreeSet<String>();
        CONSTRAINT.matcher(text).results().forEach(match -> constraints.add(match.group()));
        return Optional.of(new Features(intent, LiveContextCacheKey.hash(String.join("|", constraints)), negative));
    }

    Ranking rank(Features queryFeatures, List<Float> vector, List<SemanticContextCache.Source> sources, double threshold) {
        List<Match> scored = new ArrayList<>();
        for (var source : sources) {
            if (!queryFeatures.equals(source.features())) { continue; }
            double similarity = cosine(vector, source.vector());
            if (Double.isFinite(similarity)) { scored.add(new Match(source, similarity)); }
        }
        scored.sort(Comparator.comparingDouble(Match::similarity).reversed()
                .thenComparing(match -> match.source().questionHash())
                .thenComparing(match -> match.source().createdAt(), Comparator.reverseOrder()));
        Double best = scored.isEmpty() ? null : scored.get(0).similarity();
        var seen = new HashSet<String>();
        var matches = scored.stream().filter(match -> match.similarity() >= threshold)
                .filter(match -> seen.add(match.source().questionHash())).limit(3).toList();
        return new Ranking(matches, best, best == null ? "no_compatible_source" : "below_threshold");
    }

    static boolean validVector(List<Float> vector, int dimension) {
        if (vector == null || vector.size() != dimension || dimension < 1) { return false; }
        double norm = 0;
        for (Float value : vector) {
            if (value == null || !Float.isFinite(value)) { return false; }
            norm += (double) value * value;
        }
        return norm > 0 && Double.isFinite(norm);
    }

    static double cosine(List<Float> left, List<Float> right) {
        if (left == null || !validVector(left, left.size()) || !validVector(right, left.size())) { return Double.NaN; }
        double dot = 0, a = 0, b = 0;
        for (int i = 0; i < left.size(); i++) {
            double x = left.get(i), y = right.get(i);
            dot += x * y; a += x * x; b += y * y;
        }
        return Math.max(-1, Math.min(1, dot / (Math.sqrt(a) * Math.sqrt(b))));
    }
}
