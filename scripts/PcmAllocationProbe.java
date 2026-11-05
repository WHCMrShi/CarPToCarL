import io.github.shaonialife.carptocar.audio.PcmPacketSealer;
import io.github.shaonialife.carptocar.airplay.AirPlayCrypto;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
public class PcmAllocationProbe {
 static final byte[] KEY = new byte[32], PCM = new byte[1408];
 static volatile int sink;
 static byte[] legacy(long n) {
  byte[] header = new byte[12]; header[0]=(byte)128; header[1]=100;
  header[2]=(byte)(n>>>8); header[3]=(byte)n;
  long ts=n*352; for(int i=0;i<4;i++)header[4+i]=(byte)(ts>>>(24-i*8));
  byte[] aad=Arrays.copyOfRange(header,4,12), pcm=PCM.clone();
  for(int i=0;i<pcm.length;i+=2){byte v=pcm[i];pcm[i]=pcm[i+1];pcm[i+1]=v;}
  byte[] encrypted=AirPlayCrypto.INSTANCE.chachaSeal(KEY,AirPlayCrypto.INSTANCE.nonce64(n),pcm,aad);
  byte[] wire=new byte[12+encrypted.length+8];
  System.arraycopy(header,0,wire,0,12);System.arraycopy(encrypted,0,wire,12,encrypted.length);
  for(int i=0;i<8;i++)wire[wire.length-8+i]=(byte)(n>>>(8*i));
  return wire;
 }
 public static void main(String[] args) {
  var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
  bean.setThreadAllocatedMemoryEnabled(true);long id=Thread.currentThread().getId();
  for(int round=0;round<3;round++) {
   var sealer=new PcmPacketSealer(KEY,100L);
   for(int i=0;i<4000;i++){sink^=legacy(i)[20];sink^=sealer.seal(PCM,0,i,i*352L,i,false)[20];}
   long before=bean.getThreadAllocatedBytes(id), start=System.nanoTime();
   for(int i=4000;i<14000;i++)sink^=legacy(i)[20];
   long legacyNs=System.nanoTime()-start, legacyBytes=bean.getThreadAllocatedBytes(id)-before;
   before=bean.getThreadAllocatedBytes(id);start=System.nanoTime();
   for(int i=4000;i<14000;i++)sink^=sealer.seal(PCM,0,i,i*352L,i,false)[20];
   long reuseNs=System.nanoTime()-start, reuseBytes=bean.getThreadAllocatedBytes(id)-before;
   System.out.printf("round=%d legacy=%.1f B/packet %.1f ns/packet reusable=%.1f B/packet %.1f ns/packet%n",round,legacyBytes/10000.,legacyNs/10000.,reuseBytes/10000.,reuseNs/10000.);
  }
 }
}
