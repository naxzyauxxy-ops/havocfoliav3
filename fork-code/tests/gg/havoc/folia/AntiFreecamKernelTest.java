package gg.havoc.folia;

import gg.havoc.folia.antifreecam.DepthObfuscator;
import gg.havoc.folia.antifreecam.DepthObfuscator.Scratch;
import java.util.*;

/**
 * Standalone checks for the AntiFreecam kernel. No NMS, no JUnit, no Gradle — run it directly:
 *
 * <pre>
 *   javac -d /tmp/hf $(find havocfolia-server/src/main/java/gg/havoc/folia/antifreecam -name DepthObfuscator.java) \
 *         havocfolia-server/src/test/java/gg/havoc/folia/AntiFreecamKernelTest.java
 *   java -cp /tmp/hf gg.havoc.folia.AntiFreecamKernelTest
 * </pre>
 *
 * <p>Kept dependency-free on purpose: the kernel is the one piece of this fork where a subtle bug
 * is invisible in play (you cannot see blocks that were wrongly hidden) and catastrophic when
 * inverted (you leak the whole world). It should be runnable in ten seconds with no build system.
 *
 * <p>Case 1 exists because it caught a real bug: an earlier version left the padded shell's edge
 * and corner cells open, so the dilation leaked diagonally inward and a fully enclosed solid
 * section reported 2200 hidden blocks instead of 4096.
 */
public class AntiFreecamKernelTest {
    static final int AIR=0, STONE=1, DIAMOND=2;
    static int idx(int x,int y,int z){ return (y<<8)|(z<<4)|x; }
    static long[] occ; static int pass=0, fail=0;
    static void check(String n, boolean ok){ if(ok){pass++;System.out.println("  PASS  "+n);} else {fail++;System.out.println("  FAIL  "+n);} }
    static boolean hid(Scratch s,int x,int y,int z){ return DepthObfuscator.isHidden(s.hidden, idx(x,y,z)); }

    public static void main(String[] a){
        occ=new long[1024]; occ[0]|=1L<<STONE; occ[0]|=1L<<DIAMOND;
        Scratch s=DepthObfuscator.scratch();
        int[][] closed=new int[6][]; for(int i=0;i<6;i++){closed[i]=new int[256];Arrays.fill(closed[i],STONE);}
        int[][] closedUniform=new int[6][]; for(int i=0;i<6;i++) closedUniform[i]=new int[]{STONE};
        int[][] openAll=new int[6][];

        System.out.println("case 1: fully enclosed solid section");
        int[] solid=new int[4096]; Arrays.fill(solid,STONE);
        check("all hidden", DepthObfuscator.computeHidden(solid,closed,occ,6,s)==4096);

        System.out.println("case 2: uniform-face encoding == per-cell encoding");
        int nA=DepthObfuscator.computeHidden(solid,closed,occ,6,s);
        long[] mA=s.hidden.clone();
        int nB=DepthObfuscator.computeHidden(solid,closedUniform,occ,6,s);
        check("same count", nA==nB);
        check("same mask", Arrays.equals(mA,s.hidden));

        System.out.println("case 3: air pocket at (8,8,8), maxDepth=3");
        int[] pocket=solid.clone(); pocket[idx(8,8,8)]=AIR;
        int n=DepthObfuscator.computeHidden(pocket,closed,occ,3,s);
        check("air visible",!hid(s,8,8,8));
        check("dist1 visible",!hid(s,8,8,9));
        check("dist3 visible",!hid(s,8,8,11));
        check("dist4 hidden", hid(s,8,8,12));
        check("count == 4096-63 (got "+n+")", n==4096-63);

        System.out.println("case 4: buried ore hidden");
        int[] ore=solid.clone(); ore[idx(2,2,2)]=DIAMOND;
        DepthObfuscator.computeHidden(ore,closed,occ,6,s);
        check("ore hidden", hid(s,2,2,2));

        System.out.println("case 5: unloaded neighbours => open, maxDepth=2");
        n=DepthObfuscator.computeHidden(solid,openAll,occ,2,s);
        check("corner visible",!hid(s,0,0,0));
        check("x=2 hidden", hid(s,2,8,8));
        check("count == 12^3 (got "+n+")", n==12*12*12);

        System.out.println("case 6: no diagonal leak at big depth");
        check("all hidden", DepthObfuscator.computeHidden(solid,closed,occ,8,s)==4096);

        System.out.println("case 7: air column, maxDepth=2");
        int[] col=solid.clone(); for(int y=0;y<16;y++) col[idx(8,y,8)]=AIR;
        DepthObfuscator.computeHidden(col,closed,occ,2,s);
        check("adjacent visible",!hid(s,9,5,8));
        check("dist2 visible",  !hid(s,10,5,8));
        check("dist4 hidden",    hid(s,12,5,8));

        System.out.println("case 8: single solid block in open section, depth 0");
        int[] one=new int[4096]; Arrays.fill(one,AIR); one[idx(5,7,9)]=STONE;
        n=DepthObfuscator.computeHidden(one,closed,occ,0,s);
        check("only that block hidden", n==1 && hid(s,5,7,9));

        System.out.println("case 9: packed path agrees with fallback path");
        Random r=new Random(99);
        boolean agree=true, nonTrivial=false;
        for(int bits : new int[]{4,5,8}){
            int entriesPerWord=64/bits;
            int words=(4096+entriesPerWord-1)/entriesPerWord;
            long[] data=new long[words]; int[] states=new int[4096];
            int wi=0, sh=0;
            int paletteSize=Math.min(9,(1<<bits)-1);
            long paletteOcc=0L;
            for(int e=1;e<=paletteSize;e++) paletteOcc|=1L<<e;   // entry 0 = AIR, rest occlude
            for(int i=0;i<4096;i++){
                int pi = r.nextInt(60)==0 ? 0 : 1+r.nextInt(paletteSize);
                states[i]=(pi==0)?AIR:STONE;
                data[wi]|=((long)pi)<<sh; sh+=bits; if(sh+bits>64){sh=0;wi++;}
            }
            int nf=DepthObfuscator.computeHidden(states,closed,occ,3,s);
            long[] mf=s.hidden.clone();
            int np=DepthObfuscator.computeHiddenPacked(data,bits,paletteOcc,closed,occ,3,s);
            boolean ok = (nf==np) && Arrays.equals(mf,s.hidden);
            if(nf>50) nonTrivial=true;
            System.out.println("    bits="+bits+" fallback="+nf+" packed="+np+(ok?" ok":" MISMATCH"));
            agree &= ok;
        }
        check("packed == fallback for bits 4/5/8", agree);
        check("test was non-trivial (some blocks hidden)", nonTrivial);

        System.out.println("case 10: single-value palette (bits=0)");
        n = DepthObfuscator.computeHiddenPacked(null,0,1L,closedUniform,occ,6,s); // entry0 occludes
        check("uniform solid -> all hidden ("+n+")", n==4096);
        n = DepthObfuscator.computeHiddenPacked(null,0,0L,closedUniform,occ,6,s); // entry0 open
        check("uniform air -> none hidden ("+n+")", n==0);

        System.out.println("case 11: concurrency — 8 threads, same input, same output");
        final int[] cin = pocket;
        final int[][] cb = closed;
        int[] results=new int[8]; Thread[] ts=new Thread[8];
        for(int t=0;t<8;t++){ final int ti=t; ts[t]=new Thread(()->{
            Scratch ls=DepthObfuscator.scratch(); int acc=0;
            for(int k=0;k<2000;k++) acc=DepthObfuscator.computeHidden(cin,cb,occ,3,ls);
            results[ti]=acc; }); ts[t].start(); }
        try{ for(Thread t2:ts) t2.join(); }catch(Exception e){}
        boolean same=true; for(int v:results) same &= (v==4096-63);
        check("all threads agree", same);

        System.out.println("case 12: performance");
        int[] mixed=new int[4096]; Random r2=new Random(42);
        for(int i=0;i<4096;i++) mixed[i]= r2.nextInt(12)==0?AIR:STONE;
        for(int k=0;k<80_000;k++) DepthObfuscator.computeHidden(mixed,closed,occ,6,s);
        long t0=System.nanoTime(); int it=300_000;
        for(int k=0;k<it;k++) DepthObfuscator.computeHidden(mixed,closed,occ,6,s);
        double us=(System.nanoTime()-t0)/1e3/it;
        System.out.printf("  fallback  : %.3f us/section%n", us);
        int pb=4, epw=16; long[] pdata=new long[256]; long pocc=0b1111111110L;
        int pw=0, psh=0;
        for(int i=0;i<4096;i++){ int pi=r2.nextInt(12)==0?0:1+r2.nextInt(9);
            pdata[pw]|=((long)pi)<<psh; psh+=pb; if(psh+pb>64){psh=0;pw++;} }
        for(int k=0;k<80_000;k++) DepthObfuscator.computeHiddenPacked(pdata,pb,pocc,closed,occ,6,s);
        t0=System.nanoTime();
        for(int k=0;k<it;k++) DepthObfuscator.computeHiddenPacked(pdata,pb,pocc,closed,occ,6,s);
        double up=(System.nanoTime()-t0)/1e3/it;
        System.out.printf("  packed    : %.3f us/section%n", up);
        for(int k=0;k<80_000;k++) DepthObfuscator.computeHiddenPacked(null,0,1L,closedUniform,occ,6,s);
        t0=System.nanoTime();
        for(int k=0;k<it;k++) DepthObfuscator.computeHiddenPacked(null,0,1L,closedUniform,occ,6,s);
        double uu=(System.nanoTime()-t0)/1e3/it;
        System.out.printf("  uniform   : %.3f us/section%n", uu);
        System.out.printf("  realistic chunk (6 packed + 2 uniform): %.1f us%n", up*6+uu*2);
        check("packed under 12us/section worst case", up < 12.0);
        check("realistic chunk under 100us", up*6+uu*2 < 100.0);

        System.out.println("\n"+pass+" passed, "+fail+" failed");
        if(fail>0) System.exit(1);
    }
}
