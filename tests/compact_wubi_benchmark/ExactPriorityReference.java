import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import io.secure.keyboard.engine.*;

/** Test-only oracle. Never changes Reader weights, prefix indexes, or production code. */
final class ExactPriorityReference {
    private final DictionaryEngine reader;
    private final Map<String, List<String>> priorities = new LinkedHashMap<>();

    ExactPriorityReference(DictionaryEngine reader, String file) throws IOException {
        this.reader = reader;
        for (String line : Files.readAllLines(Path.of(file), StandardCharsets.UTF_8)) {
            String[] fields = line.split("\t", -1);
            if (fields.length != 2 || fields[0].isEmpty() || !fields[1].matches("[a-z]{1,4}"))
                throw new IllegalArgumentException("Invalid priority oracle row");
            List<String> words = priorities.computeIfAbsent(fields[1], key -> new ArrayList<>());
            if (words.contains(fields[0])) throw new IllegalArgumentException("Duplicate priority oracle row");
            words.add(fields[0]);
        }
    }

    Set<String> codes() { return priorities.keySet(); }

    List<Candidate> exact(String code, int limit) {
        if (limit <= 0) return Collections.emptyList();
        List<String> words = priorities.get(code);
        if (words == null) return reader.exactWubi(code, limit);
        LinkedHashMap<String, Candidate> original = new LinkedHashMap<>();
        for (Candidate candidate : reader.exactWubi(code, Integer.MAX_VALUE))
            original.put(candidate.getText(), candidate);
        List<Candidate> ordered = new ArrayList<>();
        for (String word : words) {
            Candidate candidate = original.remove(word);
            if (candidate == null) throw new AssertionError("Priority oracle pair absent: " + word + " " + code);
            ordered.add(candidate);
        }
        ordered.addAll(original.values());
        return new ArrayList<>(ordered.subList(0, Math.min(limit, ordered.size())));
    }

    List<Candidate> candidates(String raw, boolean fuzzy, int limit, Set<String> pairs, boolean allExact) {
        String key = raw.toLowerCase(Locale.ROOT).replace('ü', 'v').replace(" ", "").replace("'", "");
        if (limit <= 0 || !priorities.containsKey(key))
            return reader.candidates(raw, fuzzy, limit, pairs, allExact);
        List<Candidate> exact = exact(key, Integer.MAX_VALUE);
        int resultLimit = allExact && key.length() == 4 ? Math.max(limit, exact.size()) : limit;
        int expandedLimit = (int) Math.min(Integer.MAX_VALUE, (long) exact.size() + resultLimit);
        // Fetch the unmodified Reader's suffix generously enough that promoting an
        // exact word from outside a UI page cannot hide a pinyin/prefix candidate.
        LinkedHashMap<String, Candidate> combined = new LinkedHashMap<>();
        for (Candidate candidate : exact) combined.put(candidate.getText(), candidate);
        for (Candidate candidate : reader.candidates(raw, fuzzy, expandedLimit, pairs, allExact))
            combined.putIfAbsent(candidate.getText(), candidate);
        List<Candidate> ordered = new ArrayList<>(combined.values());
        return new ArrayList<>(ordered.subList(0, Math.min(resultLimit, ordered.size())));
    }
}
