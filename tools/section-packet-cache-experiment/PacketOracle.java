import java.io.*;
import java.nio.file.*;
import java.util.*;

// Independent Java 8 oracle for this bounded 340/no-block-entity fixture domain.
// Bit packing deliberately sets individual bits, unlike Rust's word-spill code.
public final class PacketOracle {
    static void varint(DataOutputStream out,int value)throws IOException {
        do {int next=value>>>7;out.writeByte((value&127)|(next==0?0:128));value=next;}while(value!=0);
    }
    static byte[] expected(Path input)throws Exception {
        DataInputStream in=new DataInputStream(Files.newInputStream(input));
        if(in.readInt()!=0x48385031 || in.readInt()!=340)throw new IOException("fixture format");
        int x=in.readInt(),z=in.readInt(),mask=in.readUnsignedShort();
        boolean sky=in.readBoolean(),full=in.readBoolean();int recipient=in.readUnsignedByte();
        int mapCount=in.readUnsignedShort();Map<Integer,Integer> mapping=new HashMap<Integer,Integer>();
        for(int i=0;i<mapCount;i++){int runtime=in.readInt(),wire=in.readUnsignedShort();if(wire>=8192 || mapping.put(runtime,wire)!=null)throw new IOException("mapping");}
        ByteArrayOutputStream payloadBytes=new ByteArrayOutputStream();DataOutputStream payload=new DataOutputStream(payloadBytes);
        int emitted=0;
        for(int section=0;section<16;section++) {
            boolean present=in.readBoolean();
            if(!present){if((mask&(1<<section))!=0)throw new IOException("MISSING_SECTION");continue;}
            int[] states=new int[4096];
            for(int i=0;i<states.length;i++){Integer wire=mapping.get(in.readInt());if(wire==null)throw new IOException("MISSING_MAPPING");states[i]=recipient==1 && wire!=0?1:wire;}
            byte[] block=new byte[2048],sun=new byte[2048];in.readFully(block);in.readFully(sun);
            if((mask&(1<<section))==0)continue;
            LinkedHashMap<Integer,Integer> palette=new LinkedHashMap<Integer,Integer>();palette.put(0,0);
            for(int state:states)if(!palette.containsKey(state))palette.put(state,palette.size());
            boolean direct=palette.size()>256;int bits=4;
            if(direct)bits=13;else while((1<<bits)<palette.size())bits++;
            payload.writeByte(bits);varint(payload,direct?0:palette.size());
            if(!direct)for(Integer state:palette.keySet())varint(payload,state);
            long[] words=new long[(4096*bits+63)/64];
            for(int i=0;i<4096;i++){int value=direct?states[i]:palette.get(states[i]);for(int b=0;b<bits;b++)if((value&(1<<b))!=0){int position=i*bits+b;words[position/64]|=1L<<(position%64);}}
            varint(payload,words.length);for(long word:words)payload.writeLong(word);
            payload.write(block);if(sky)payload.write(sun);emitted|=1<<section;
        }
        byte[] biomes=new byte[256];in.readFully(biomes);if(in.read()!=-1)throw new IOException("trailing fixture bytes");in.close();
        if(full)payload.write(biomes);payload.flush();if(emitted!=mask)throw new AssertionError("mask mismatch");
        ByteArrayOutputStream packetBytes=new ByteArrayOutputStream();DataOutputStream packet=new DataOutputStream(packetBytes);
        varint(packet,0x20);packet.writeInt(x);packet.writeInt(z);packet.writeBoolean(full);varint(packet,emitted);varint(packet,payloadBytes.size());packet.write(payloadBytes.toByteArray());varint(packet,0);packet.flush();return packetBytes.toByteArray();
    }
    static void compare(Path file,byte[] expected)throws Exception {
        byte[] actual=Files.readAllBytes(file);int count=Math.min(actual.length,expected.length);
        for(int i=0;i<count;i++)if(actual[i]!=expected[i])throw new AssertionError("FIRST_DIVERGENCE file="+file.getFileName()+" offset="+i+" expected="+(expected[i]&255)+" actual="+(actual[i]&255));
        if(actual.length!=expected.length)throw new AssertionError("FIRST_DIVERGENCE length expected="+expected.length+" actual="+actual.length);
    }
    public static void main(String[] args)throws Exception {
        Path dir=Paths.get(args[0]);int cases=0,bytes=0;
        for(String line:Files.readAllLines(dir.resolve("manifest.tsv"))){String[] f=line.split("\t");byte[] golden=expected(dir.resolve(f[0]+".input"));compare(dir.resolve(f[0]+".prototype.bin"),golden);compare(dir.resolve(f[0]+".legacy.bin"),golden);cases++;bytes+=golden.length;}
        System.out.println("PASS PacketOracle cases="+cases+" encoders=2 bytes_per_encoder="+bytes+" mask_and_full_body_exact=true");
    }
}
