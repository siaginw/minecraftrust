import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Independent Java8 binary fixture writer and exact raw-format edit oracle. */
public final class NbtFixtureOracle {
    static final int LIMIT = 2 * 1024 * 1024;
    static final class Oracle {
        final byte[] bytes;
        final ByteBuffer b;
        int nodes, levels, utfRejected, edit = -1, edits;
        Oracle(byte[] bytes) { this.bytes=bytes; b=ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN); }
        byte[] string() throws IOException {
            int n=b.getShort()&65535;
            byte[] raw=new byte[n]; b.get(raw);
            ByteArrayOutputStream wrapped=new ByteArrayOutputStream();
            DataOutputStream out=new DataOutputStream(wrapped); out.writeShort(n); out.write(raw);
            try { new DataInputStream(new ByteArrayInputStream(wrapped.toByteArray())).readUTF(); }
            catch (UTFDataFormatException bad) { utfRejected++; }
            return raw;
        }
        int count() throws IOException {
            int n=b.getInt(); if(n<0 || n>1000000) throw new IOException("count limit"); return n;
        }
        void skip(int n) throws IOException {
            if(n<0 || n>b.remaining()) throw new EOFException(); b.position(b.position()+n);
        }
        boolean ascii(byte[] raw,String text) throws IOException {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
            out.writeShort(raw.length);out.write(raw);
            try { return new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())).readUTF().equals(text); }
            catch(UTFDataFormatException invalid) { return false; }
        }
        void value(int kind,int depth,String path) throws IOException {
            if(depth>64 || ++nodes>200000) throw new IOException("structural limit");
            switch(kind) {
                case 1: skip(1); return;
                case 2: skip(2); return;
                case 3: case 5: skip(4); return;
                case 4: case 6: skip(8); return;
                case 7: skip(count()); return;
                case 8: string(); return;
                case 11: skip(Math.multiplyExact(count(),4)); return;
                case 12: skip(Math.multiplyExact(count(),8)); return;
                case 9:
                    int element=b.get()&255,n=count();
                    if(element>12 || (element==0 && n!=0)) throw new IOException("list type");
                    for(int i=0;i<n;i++) value(element,depth+1,"list");
                    return;
                case 10:
                    while(true) {
                        int tag=b.get()&255; if(tag==0) return;
                        if(tag>12) throw new IOException("wire tag id");
                        byte[] name=string();
                        String next="opaque";
                        if(path.equals("") && ascii(name,"Level")) {
                            levels++; if(tag!=10) throw new IOException("Level type"); next="Level";
                        }
                        if(path.equals("Level") && ascii(name,"LastUpdate")) {
                            if(tag!=4) throw new IOException("LastUpdate type"); edits++; edit=b.position();
                        }
                        value(tag,depth+1,next);
                    }
                default: throw new IOException("tag id");
            }
        }
        byte[] edit() throws IOException {
            if(bytes.length>LIMIT || (b.get()&255)!=10) throw new IOException("root/size");
            string(); value(10,0,"");
            if(b.hasRemaining() || levels!=1 || edits!=1) throw new IOException("trailing/ambiguous path");
            byte[] expected=bytes.clone();
            ByteBuffer destination=ByteBuffer.wrap(expected).order(ByteOrder.BIG_ENDIAN);
            destination.putLong(edit,Math.addExact(destination.getLong(edit),1));
            return expected;
        }
    }
    interface Payload { void write(DataOutputStream out) throws Exception; }
    static byte[] document(String root, Payload extra) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); DataOutputStream out=new DataOutputStream(bytes);
        out.writeByte(10); out.writeUTF(root); out.writeByte(10); out.writeUTF("Level");
        out.writeByte(4); out.writeUTF("LastUpdate"); out.writeLong(123456789L);
        if(extra!=null) extra.write(out);
        out.writeByte(0); out.writeByte(0); return bytes.toByteArray();
    }
    static void tag(DataOutputStream out,int kind,String name) throws Exception { out.writeByte(kind); out.writeUTF(name); }
    static void listElement(DataOutputStream out,int type) throws Exception {
        switch(type) {
            case 1: out.writeByte(-1); break;
            case 2: out.writeShort(-32768); break;
            case 3: out.writeInt(Integer.MIN_VALUE); break;
            case 4: out.writeLong(Long.MIN_VALUE); break;
            case 5: out.writeInt(0x7fa12345); break;
            case 6: out.writeLong(0xfff0000000000001L); break;
            case 7: out.writeInt(3);out.write(new byte[]{0,-1,127});break;
            case 8: out.writeUTF("\u0000\ud800");break;
            case 9: out.writeByte(5);out.writeInt(0);break;
            case 10: tag(out,3,"mod:opaque");out.writeInt(70000);out.writeByte(0);break;
            case 11: out.writeInt(2);out.writeInt(70000);out.writeInt(-1);break;
            case 12: out.writeInt(2);out.writeLong(Long.MAX_VALUE);out.writeLong(-1);break;
            default: throw new IllegalArgumentException();
        }
    }
    static byte[] rich() throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); DataOutputStream out=new DataOutputStream(bytes);
        out.writeByte(10); out.writeUTF("named\u0000root");
        tag(out,3,"DataVersion"); out.writeInt(1343);
        tag(out,10,"Level"); tag(out,4,"LastUpdate"); out.writeLong(123456789L);
        tag(out,3,"xPos");out.writeInt(-33);tag(out,3,"zPos");out.writeInt(8192);
        tag(out,11,"NEID");out.writeInt(5);for(int x:new int[]{0,65535,65536,70000,120000})out.writeInt(x);
        tag(out,10,"ForgeCaps");
        tag(out,1,"byte");out.writeByte(-128);tag(out,2,"short");out.writeShort(-30000);
        tag(out,3,"int");out.writeInt(Integer.MIN_VALUE);tag(out,4,"long");out.writeLong(Long.MIN_VALUE);
        tag(out,5,"floatNan");out.writeInt(0x7fa12345);tag(out,6,"doubleNan");out.writeLong(0xfff0000000000001L);
        tag(out,5,"negativeZero");out.writeInt(0x80000000);tag(out,6,"infinity");out.writeLong(0x7ff0000000000000L);
        tag(out,7,"bytes");out.writeInt(4);out.write(new byte[]{0,-1,1,-128});
        tag(out,12,"longs");out.writeInt(3);out.writeLong(Long.MAX_VALUE);out.writeLong(0);out.writeLong(-1);
        tag(out,8,"unknown:mod\u0000name");out.writeUTF("null\u0000pair\ud83d\ude00");
        tag(out,9,"compoundList");out.writeByte(10);out.writeInt(2);
        for(int i=0;i<2;i++){tag(out,8,"modTag");out.writeUTF("exact"+i);out.writeByte(0);}
        out.writeByte(0);out.writeByte(0);out.writeByte(0);return bytes.toByteArray();
    }
    static void fixture(Path directory,String name,byte[] bytes,PrintWriter manifest) throws Exception {
        Files.write(directory.resolve(name+".nbt"),bytes,StandardOpenOption.CREATE_NEW);
        Oracle oracle=new Oracle(bytes);
        String status;
        try {
            byte[] expected=oracle.edit();
            Files.write(directory.resolve(name+".expected.nbt"),expected,StandardOpenOption.CREATE_NEW);
            status="VALID";
        } catch (IOException|BufferUnderflowException|IllegalArgumentException|ArithmeticException failure) { status="REJECT"; }
        manifest.println(name+"\t"+status+"\t"+oracle.utfRejected);
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=2 || !args[0].equals("generate")) throw new IllegalArgumentException("generate output");
        Path directory=Paths.get(args[1]); Files.createDirectory(directory);
        PrintWriter manifest=new PrintWriter(Files.newBufferedWriter(directory.resolve("manifest.tsv"),StandardOpenOption.CREATE_NEW));
        fixture(directory,"minimal",document("",null),manifest);
        fixture(directory,"rich-mod-neid",rich(),manifest);
        fixture(directory,"duplicate-opaque-order",document("",out->{tag(out,3,"z");out.writeInt(1);tag(out,3,"a");out.writeInt(2);tag(out,3,"z");out.writeInt(3);}),manifest);
        for(int type=0;type<=12;type++){
            final int t=type;
            fixture(directory,"empty-list-"+type,document("",out->{tag(out,9,"unknownList");out.writeByte(t);out.writeInt(0);}),manifest);
        }
        for(int type=1;type<=12;type++){
            final int t=type;
            fixture(directory,"nonempty-list-"+type,document("",out->{tag(out,9,"typed");out.writeByte(t);out.writeInt(1);listElement(out,t);}),manifest);
        }
        fixture(directory,"root-lone-surrogate",document("\ud800",null),manifest);
        fixture(directory,"depth-boundary",document("",out->{for(int i=0;i<63;i++)tag(out,10,"deep");for(int i=0;i<63;i++)out.writeByte(0);}),manifest);
        byte[][] strings={ { (byte)0xc0,(byte)0x80 }, {(byte)0xed,(byte)0xa0,(byte)0xbd,(byte)0xed,(byte)0xb8,(byte)0x80},
            {(byte)0xed,(byte)0xa0,(byte)0x80}, {(byte)0xed,(byte)0xb0,(byte)0x80}, {0}, {(byte)0xc1,(byte)0x81},
            {(byte)0x80}, {(byte)0xf0,(byte)0x9f,(byte)0x98,(byte)0x80}, {(byte)0xe0,(byte)0xa0} };
        String[] names={"null","surrogate-pair","lone-high","lone-low","raw-null","overlong-A","invalid-continuation","invalid-four-byte","invalid-truncated-sequence"};
        for(int i=0;i<strings.length;i++){
            final byte[] raw=strings[i];
            fixture(directory,"mutf8-"+names[i],document("",out->{tag(out,8,"raw");out.writeShort(raw.length);out.write(raw);}),manifest);
        }
        fixture(directory,"large-opaque-array",document("",out->{tag(out,7,"largeOpaque");out.writeInt(180000);Random r=new Random(991);byte[] data=new byte[180000];r.nextBytes(data);out.write(data);}),manifest);
        fixture(directory,"duplicate-target",document("",out->{tag(out,4,"LastUpdate");out.writeLong(3);}),manifest);
        fixture(directory,"overlong-duplicate-target",document("",out->{out.writeByte(4);out.writeShort(11);out.writeByte(0xc1);out.writeByte(0x8c);out.writeBytes("astUpdate");out.writeLong(3);}),manifest);
        fixture(directory,"unknown-wire-tag",document("",out->{tag(out,13,"unknown");}),manifest);
        fixture(directory,"negative-array",document("",out->{tag(out,7,"array");out.writeInt(-1);}),manifest);
        fixture(directory,"oversized-array",document("",out->{tag(out,7,"array");out.writeInt(Integer.MAX_VALUE);}),manifest);
        fixture(directory,"negative-list",document("",out->{tag(out,9,"list");out.writeByte(1);out.writeInt(-1);}),manifest);
        fixture(directory,"nonempty-end-list",document("",out->{tag(out,9,"list");out.writeByte(0);out.writeInt(1);}),manifest);
        fixture(directory,"unknown-empty-list-type",document("",out->{tag(out,9,"list");out.writeByte(99);out.writeInt(0);}),manifest);
        fixture(directory,"depth-limit",document("",out->{for(int i=0;i<65;i++)tag(out,10,"deep");for(int i=0;i<65;i++)out.writeByte(0);}),manifest);
        fixture(directory,"node-limit",document("",out->{tag(out,9,"hugeList");out.writeByte(1);out.writeInt(200000);out.write(new byte[200000]);}),manifest);
        fixture(directory,"raw-byte-limit",document("",out->{for(int i=0;i<3;i++){tag(out,7,"large"+i);out.writeInt(800000);out.write(new byte[800000]);}}),manifest);
        byte[] normal=document("",null);
        fixture(directory,"trailing-bytes",Arrays.copyOf(normal,normal.length+1),manifest);
        fixture(directory,"truncated",Arrays.copyOf(normal,normal.length-3),manifest);
        byte[] overflow=document("",null);Oracle find=new Oracle(overflow);find.edit();ByteBuffer.wrap(overflow).putLong(find.edit,Long.MAX_VALUE);
        fixture(directory,"last-update-overflow",overflow,manifest);
        manifest.close();
        System.out.println("JAVA8_RAW_NBT_ORACLE_V1 fixtures=54");
    }
}
