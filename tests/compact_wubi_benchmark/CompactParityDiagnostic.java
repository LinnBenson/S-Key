import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import io.secure.keyboard.engine.*;

/** Exhaustive exact-bucket validation plus full candidate, prefix and fuzzy comparisons. */
public class CompactParityDiagnostic {
    static Reader reader(String s) throws IOException { return Files.newBufferedReader(Path.of(s), StandardCharsets.UTF_8); }
    static void equal(Object expected, Object actual, String detail) {
        if (!expected.equals(actual)) throw new AssertionError(detail+" expected="+expected+" actual="+actual);
    }
    public static void main(String[] args) throws Exception {
        DictionaryEngine oldEngine, compactEngine;
        try (Reader w=reader(args[0]); Reader p=reader(args[2])) { oldEngine=new DictionaryEngine(w,p,null); }
        try (FileChannel ch=FileChannel.open(Path.of(args[1]),StandardOpenOption.READ); Reader p=reader(args[2])) {
            compactEngine=new DictionaryEngine(new CompactWubiTable(ch.map(FileChannel.MapMode.READ_ONLY,0,ch.size())),p,null);
        }
        ExactPriorityReference reference = new ExactPriorityReference(oldEngine, args[3]);
        TreeSet<String> exactKeys=new TreeSet<>(), prefixes=new TreeSet<>();
        boolean started=false;
        try (BufferedReader br=new BufferedReader(reader(args[0]))) {
            String line;
            while ((line=br.readLine())!=null) {
                if (line.trim().equals("...")) {started=true;continue;}
                if (!started||line.isEmpty()||line.startsWith("#"))continue;
                String code=line.split("\t")[1];exactKeys.add(code);
                for (int i=1;i<code.length();i++)prefixes.add(code.substring(0,i));
            }
        }
        long rows=0;int n=0;long start=System.nanoTime();
        for(String key:exactKeys) {
            List<Candidate> expected=reference.exact(key,Integer.MAX_VALUE);
            equal(expected,compactEngine.exactWubi(key,Integer.MAX_VALUE),"exact "+key);rows+=expected.size();
            equal(reference.exact(key,1),compactEngine.exactWubi(key,1),"auto "+key);
            if(n++%83==0) for(boolean fuzzy:new boolean[]{false,true}) {
                equal(reference.candidates(key,fuzzy,40,DictionaryEngine.Companion.getDEFAULT_FUZZY_PAIRS(),false),
                      compactEngine.candidates(key,fuzzy,40,DictionaryEngine.Companion.getDEFAULT_FUZZY_PAIRS(),false),"candidate "+key);
            }
        }
        for(String key:prefixes) equal(reference.candidates(key,false,40,Collections.emptySet(),false),
                compactEngine.candidates(key,false,40,Collections.emptySet(),false),"prefix "+key);
        for(String key:new String[]{"ni","nihao","zhongguo","zongguo","xi'an","xian","luanjian","re","le","el","nihaowoshizhongguoren"}) {
            for(Set<String> pairs:Arrays.asList(DictionaryEngine.Companion.getDEFAULT_FUZZY_PAIRS(),Collections.<String>emptySet(),Collections.singleton("r_l")))
                equal(reference.candidates(key,true,40,pairs,false),compactEngine.candidates(key,true,40,pairs,false),"pinyin "+key+" "+pairs);
        }
        for(String key:reference.codes()) for(int limit:new int[]{1,40}) for(boolean all:new boolean[]{false,true})
            equal(reference.candidates(key,false,limit,Collections.emptySet(),all),
                  compactEngine.candidates(key,false,limit,Collections.emptySet(),all),"priority candidates "+key);
        equal(reference.candidates("khkh",false,40,Collections.emptySet(),true),compactEngine.candidates("khkh",false,40,Collections.emptySet(),true),"expanded khkh");
        System.out.println("PASS exactKeys="+exactKeys.size()+" prefixKeys="+prefixes.size()+" rows="+rows+" priorityCodes="+reference.codes().size()+" sampledFullQueries="+((n+82)/83*2)+" elapsed_ms="+(System.nanoTime()-start)/1000000);
    }
}
