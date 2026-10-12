import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import io.secure.keyboard.engine.*;

/** Warm CPU lookup diagnostic; desktop JVM numbers are not phone performance claims. */
public class CompactLatencyDiagnostic {
    static volatile long sink;
    static Reader reader(String s) throws IOException { return Files.newBufferedReader(Path.of(s), StandardCharsets.UTF_8); }
    static List<Candidate> lookup(DictionaryEngine e,String q,String kind) {
        return kind.equals("exact1")?e.exactWubi(q,1):e.candidates(q,true,40,DictionaryEngine.Companion.getDEFAULT_FUZZY_PAIRS(),true);
    }
    static void measure(String name, DictionaryEngine engine,String[] queries,String kind) throws Exception {
        for(int i=0;i<3000;i++) sink+=lookup(engine,queries[i%queries.length],kind).size();
        System.gc();Thread.sleep(100);
        long[] nanos=new long[10000];long sum=0;
        for(int i=0;i<nanos.length;i++) {
            long start=System.nanoTime();List<Candidate> result=lookup(engine,queries[i%queries.length],kind);
            nanos[i]=System.nanoTime()-start;sum+=nanos[i];sink+=result.size();
        }
        Arrays.sort(nanos);
        System.out.println(name+" "+kind+" n="+nanos.length+" mean_us="+sum/nanos.length/1000.0+
            " p50_us="+nanos[nanos.length/2]/1000.0+" p95_us="+nanos[nanos.length*95/100]/1000.0+
            " p99_us="+nanos[nanos.length*99/100]/1000.0+" max_us="+nanos[nanos.length-1]/1000.0);
    }
    public static void main(String[] args) throws Exception {
        DictionaryEngine oldEngine, compactEngine;
        try(Reader w=reader(args[0]);Reader p=reader(args[2])) {oldEngine=new DictionaryEngine(w,p,null);}
        try(FileChannel ch=FileChannel.open(Path.of(args[1]),StandardOpenOption.READ);Reader p=reader(args[2])) {
            compactEngine=new DictionaryEngine(new CompactWubiTable(ch.map(FileChannel.MapMode.READ_ONLY,0,ch.size())),p,null);
        }
        String[] exact={"a","ab","abcd","wq","wqvb","wgkq","khkh","qtqt","pypy","sgsg","kkkk","zzjp"};
        String[] mixed={"a","ab","abc","w","wq","wqv","wqvb","kh","khk","khkh","ni","nih","niha","nihao","zhongguo","zongguo","luanjian","xi'an"};
        for(String q:mixed) if(!lookup(oldEngine,q,"mixed").equals(lookup(compactEngine,q,"mixed")))throw new AssertionError(q);
        for(int pass=0;pass<2;pass++) {
            if(pass==0) {measure("reader",oldEngine,exact,"exact1");measure("compact",compactEngine,exact,"exact1");
                measure("reader",oldEngine,mixed,"mixed");measure("compact",compactEngine,mixed,"mixed");}
            else {measure("compact",compactEngine,exact,"exact1");measure("reader",oldEngine,exact,"exact1");
                measure("compact",compactEngine,mixed,"mixed");measure("reader",oldEngine,mixed,"mixed");}
        }
        System.out.println("checksum="+sink);
    }
}
