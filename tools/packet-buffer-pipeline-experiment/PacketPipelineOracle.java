import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;

/** Independent Java 8 framing, Deflater/Inflater and continuous AES-CFB8 oracle.
 * Synthetic packet 0x7e fields are fixture data, not Minecraft gameplay semantics. */
public final class PacketPipelineOracle {
    static int sample(int seed,int i){if(seed==0)return 0x55;int x=i+seed*0x9e3779b9;x^=x>>>13;x*=0x85ebca6b;x^=x>>>16;return x&255;}
    static byte[] body(int seed,int length,int rule)throws Exception{
        if(length<16 || length>65536 || rule<0 || rule>1)throw new IOException("fixture bound");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(length);DataOutputStream out=new DataOutputStream(bytes);
        out.writeByte(0x7e);out.writeByte(rule);out.writeInt(seed);out.writeLong(1);out.writeShort(0);
        for(int i=0;i<length-16;i++){int v=sample(seed,i);out.writeByte(rule==0?v:(v==0?0:0x33));}return bytes.toByteArray();
    }
    static void varint(OutputStream out,int v)throws Exception{do{int n=v>>>7;out.write((v&127)|(n==0?0:128));v=n;}while(v!=0);}
    static int varint(InputStream in)throws Exception{int v=0;for(int i=0;i<3;i++){int b=in.read();if(b<0)throw new IOException("truncated prefix");v|=(b&127)<<(i*7);if((b&128)==0)return v;}throw new IOException("prefix bound");}
    static byte[] frame(byte[] body,int threshold,Deflater compressor)throws Exception{
        ByteArrayOutputStream inner=new ByteArrayOutputStream();
        if(threshold<0)inner.write(body);
        else if(body.length<threshold){varint(inner,0);inner.write(body);}
        else{varint(inner,body.length);compressor.setInput(body);compressor.finish();byte[] scratch=new byte[193];int iterations=0;
            while(!compressor.finished()){int n=compressor.deflate(scratch);if(n==0 || ++iterations>1000)throw new IOException("deflater stalled");inner.write(scratch,0,n);}compressor.reset();}
        ByteArrayOutputStream result=new ByteArrayOutputStream();varint(result,inner.size());inner.writeTo(result);return result.toByteArray();
    }
    static byte[] decoded(byte[] frame,int threshold)throws Exception{
        ByteArrayInputStream source=new ByteArrayInputStream(frame);int len=varint(source);if(len!=source.available() || len>131072)throw new IOException("frame length mismatch");
        int dataLength=threshold<0?0:varint(source);byte[] payload=new byte[source.available()];if(source.read(payload)!=payload.length)throw new IOException("truncated payload");
        if(dataLength==0){if(threshold>=0 && payload.length>=threshold)throw new IOException("threshold/raw mismatch");return payload;}
        if(dataLength<threshold || dataLength>65536)throw new IOException("compressed size bound");
        Inflater inflater=new Inflater();try{
            inflater.setInput(payload);byte[] output=new byte[dataLength+1];int cursor=0,iterations=0;
            while(!inflater.finished()){int n=inflater.inflate(output,cursor,output.length-cursor);cursor+=n;if(n==0 || cursor>dataLength || ++iterations>1000)throw new IOException("inflater bound/stall");}
            if(cursor!=dataLength || inflater.getRemaining()!=0)throw new IOException("decompressed size/trailing mismatch");return Arrays.copyOf(output,cursor);
        }catch(DataFormatException error){throw new AssertionError("COMPRESSION_DIVERGENCE",error);}finally{inflater.end();}
    }
    static Cipher cipher(int mode,int value)throws Exception{byte[] key=new byte[16];Arrays.fill(key,(byte)value);Cipher c=Cipher.getInstance("AES/CFB8/NoPadding");c.init(mode,new SecretKeySpec(key,"AES"),new IvParameterSpec(key));return c;}
    static void equal(String name,byte[] expected,byte[] actual){int n=Math.min(expected.length,actual.length);for(int i=0;i<n;i++)if(expected[i]!=actual[i])throw new AssertionError("FIRST_DIVERGENCE "+name+" offset="+i+" expected="+(expected[i]&255)+" actual="+(actual[i]&255));if(expected.length!=actual.length)throw new AssertionError("FIRST_DIVERGENCE "+name+" length expected="+expected.length+" actual="+actual.length);}
    static int firstDifference(byte[] a,byte[] b){for(int i=0;i<Math.min(a.length,b.length);i++)if(a[i]!=b[i])return i;return a.length==b.length?-1:Math.min(a.length,b.length);}
    public static void main(String[] args)throws Exception{
        Path dir=Paths.get(args[0]);Map<Integer,List<int[]>> entries=new TreeMap<Integer,List<int[]>>();int packets=0,exact=0,wireBytes=0;
        for(String line:Files.readAllLines(dir.resolve("manifest.tsv"))){String[] fields=line.split("\t");if(fields.length!=7)throw new IOException("manifest");int[] values=new int[7];for(int i=0;i<7;i++)values[i]=Integer.parseInt(fields[i]);List<int[]> list=entries.get(values[0]);if(list==null){list=new ArrayList<int[]>();entries.put(values[0],list);}list.add(values);}
        if(entries.size()!=4)throw new IOException("connection inventory");
        for(Map.Entry<Integer,List<int[]>> entry:entries.entrySet()){
            int connection=entry.getKey(),keyValue=connection+1;ByteArrayOutputStream plain=new ByteArrayOutputStream(),javaPlain=new ByteArrayOutputStream();Deflater compressor=new Deflater();
            try{for(int[] f:entry.getValue()){
                if(f[5]!=(connection%2) || f[6]!=keyValue)throw new IOException("recipient identity");
                byte[] expectedBody=body(f[2],f[3],f[5]);byte[] nativeFrame=Files.readAllBytes(dir.resolve("connection-"+connection+"-packet-"+f[1]+".frame"));
                equal("decoded-body connection="+connection+" packet="+f[1],expectedBody,decoded(nativeFrame,f[4]));byte[] javaFrame=frame(expectedBody,f[4],compressor);
                int difference=firstDifference(javaFrame,nativeFrame);if(difference<0)exact++;else System.out.println("DEFLATE_VALID_DIFFERENCE connection="+connection+" packet="+f[1]+" first_offset="+difference);
                plain.write(nativeFrame);javaPlain.write(javaFrame);packets++;
            }}finally{compressor.end();}
            byte[] nativeWire=Files.readAllBytes(dir.resolve("connection-"+connection+".wire"));byte[] expectedWire=cipher(Cipher.ENCRYPT_MODE,keyValue).doFinal(plain.toByteArray());
            equal("wire connection="+connection,expectedWire,nativeWire);
            Cipher decryptor=cipher(Cipher.DECRYPT_MODE,keyValue);ByteArrayOutputStream decodedStream=new ByteArrayOutputStream();
            for(int i=0;i<nativeWire.length;i+=17){byte[] part=decryptor.update(nativeWire,i,Math.min(17,nativeWire.length-i));if(part!=null)decodedStream.write(part);}byte[] tail=decryptor.doFinal();if(tail!=null)decodedStream.write(tail);equal("continuous-decryption connection="+connection,plain.toByteArray(),decodedStream.toByteArray());
            byte[] javaWire=cipher(Cipher.ENCRYPT_MODE,keyValue).doFinal(javaPlain.toByteArray());Files.write(dir.resolve("connection-"+connection+".java-wire"),javaWire);wireBytes+=nativeWire.length;
        }
        if(packets!=27)throw new IOException("packet inventory/cancellation hole");
        System.out.println("PASS PacketPipelineOracle connections=4 packets="+packets+" encrypted_bytes="+wireBytes+" exact_jdk_frames="+exact+" native_frame_decode_and_wire_exact=true");
    }
}
