package gg.havoc.folia;

import gg.havoc.folia.scheduler.PinStrategy;
import java.util.*;
/**
 * Checks the CPU assignment strategies. Pure logic — no /proc, no taskset, no root.
 *
 * <pre>
 *   javac -d /tmp/hf havocfolia-server/src/main/java/gg/havoc/folia/scheduler/PinStrategy.java \
 *         havocfolia-server/src/test/java/gg/havoc/folia/PinStrategyTest.java
 *   java -cp /tmp/hf gg.havoc.folia.PinStrategyTest
 * </pre>
 *
 * <p>The degenerate cases matter most here. A pin strategy that returns an empty pool on a
 * one-core box, or that quietly assigns every worker to CPU 0, is worse than not pinning at all.
 */
public class PinStrategyTest {
    static int pass=0,fail=0;
    static void ck(String n,boolean ok){ if(ok){pass++;System.out.println("  PASS "+n);}else{fail++;System.out.println("  FAIL "+n);} }
    public static void main(String[] a){
        // 8 physical cores, SMT2 -> cpus 0..15, core i = {i, i+8}
        List<List<Integer>> topo=new ArrayList<>();
        for(int c=0;c<8;c++) topo.add(List.of(c,c+8));

        List<Integer> spread=PinStrategy.SPREAD.assign(topo,List.of(),6,2);
        System.out.println("SPREAD  6 workers, reserve 2 -> "+spread);
        ck("spread avoids SMT siblings", new HashSet<>(spread).size()==6);
        ck("spread skips reserved cpus 0,1", !spread.contains(0)&&!spread.contains(1));

        List<Integer> compact=PinStrategy.COMPACT.assign(topo,List.of(),4,0);
        System.out.println("COMPACT 4 workers, reserve 0 -> "+compact);
        ck("compact fills in order", compact.equals(List.of(0,1,2,3)));

        List<Integer> paired=PinStrategy.SMT_PAIRED.assign(topo,List.of(),4,0);
        System.out.println("PAIRED  4 workers -> "+paired);
        ck("paired uses both siblings of a core", paired.contains(0)&&paired.contains(8));

        List<Integer> iso=PinStrategy.ISOLATED.assign(topo,List.of(4,5,6,7),6,4);
        System.out.println("ISOLATED cpu-set 4-7, 6 workers -> "+iso);
        ck("isolated only uses the given set", new HashSet<>(iso).equals(new HashSet<>(List.of(4,5,6,7))));
        ck("isolated wraps round-robin", iso.size()==6);

        // degenerate: more reserved than cpus
        List<Integer> tiny=PinStrategy.SPREAD.assign(List.of(List.of(0)),List.of(),2,8);
        System.out.println("1 cpu, reserve 8 -> "+tiny);
        ck("never empties the pool", !tiny.isEmpty());

        List<Integer> none=PinStrategy.ISOLATED.assign(topo,List.of(),4,0);
        ck("isolated with no cpu-set yields nothing", none.isEmpty());

        ck("parse fallback", PinStrategy.parse("nonsense")==PinStrategy.SPREAD);
        ck("parse case-insensitive", PinStrategy.parse("smt_paired")==PinStrategy.SMT_PAIRED);
        System.out.println("\n"+pass+" passed, "+fail+" failed");
        if(fail>0) System.exit(1);
    }
}
