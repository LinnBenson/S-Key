import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import io.secure.keyboard.engine.DictionaryEngine;
import io.secure.keyboard.engine.CompactWubiTable;
import io.secure.keyboard.engine.EnglishLexicon;

/** Isolated diagnostic. Heap, mapped virtual bytes and mapped resident pages are separate. */
public class CompactMemoryDiagnostic {
    private static volatile DictionaryEngine retained;
    private static long settledHeap() throws Exception {
        for (int i = 0; i < 4; ++i) {
            System.gc(); System.runFinalization(); Thread.sleep(140);
        }
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }
    private static Reader reader(String p) throws IOException {
        return Files.newBufferedReader(Path.of(p), StandardCharsets.UTF_8);
    }
    private static void processMemory(String path) throws Exception {
        if (!Files.isReadable(Path.of("/proc/self/smaps")) || !Files.isReadable(Path.of("/proc/self/status"))) {
            System.out.println("mapping/process RSS unavailable: this platform does not expose Linux /proc/self");
            return;
        }
        long virtual = 0, rss = 0, pss = 0;
        boolean inMapping = false;
        for (String line : Files.readAllLines(Path.of("/proc/self/smaps"))) {
            if (line.matches("^[0-9a-f]+-[0-9a-f]+ .*")) inMapping = line.endsWith(path);
            if (inMapping) {
                if (line.startsWith("Size:")) virtual += number(line);
                if (line.startsWith("Rss:")) rss += number(line);
                if (line.startsWith("Pss:")) pss += number(line);
            }
        }
        System.out.println("mapping_kib virtual="+virtual+" rss="+rss+" pss="+pss);
        for (String line : Files.readAllLines(Path.of("/proc/self/status")))
            if (line.startsWith("VmRSS:") || line.startsWith("VmHWM:")) System.out.println(line);
    }
    private static long number(String line) { return Long.parseLong(line.replaceAll("[^0-9]", "")); }
    private static void loadEngine(String[] args) throws Exception {
        try (Reader p = reader(args[1]); Reader e = reader(args[2])) {
            if (args[3].equals("compact")) {
                try (FileChannel ch = FileChannel.open(Path.of(args[0]), StandardOpenOption.READ)) {
                    retained = new DictionaryEngine(new CompactWubiTable(ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size())), p, new EnglishLexicon(e));
                }
            } else {
                try (Reader w = reader(args[0])) { retained = new DictionaryEngine(w, p, new EnglishLexicon(e)); }
            }
        }
    }
    public static void main(String[] args) throws Exception {
        retained = new DictionaryEngine(new StringReader("...\n"), new StringReader("...\n"),
                new EnglishLexicon(new StringReader("")));
        long empty = settledHeap();
        long start = System.nanoTime();
        loadEngine(args);
        long load = (System.nanoTime() - start) / 1000000;
        long[] samples = new long[3];
        for (int i=0; i<3; i++) samples[i]=settledHeap();
        System.out.println("mode="+args[3]+" empty="+empty+" samples="+Arrays.toString(samples)+
                " wubi="+retained.getWubiEntryCount()+" pinyin="+retained.getPinyinEntryCount()+" load_ms="+load);
        processMemory(args[0]);
        long checksum=0;
        for (String code : new String[]{"a","ab","abc","abcd","wq","wqvb","wgkq","khkh","ni","nian","gfjg"})
            checksum += retained.exactWubi(code, 500).size();
        System.out.println("active_heap="+settledHeap()+" checksum="+checksum);
        processMemory(args[0]);
    }
}
