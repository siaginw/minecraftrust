import java.io.*;
import java.util.*;

/** Independent sequential specification, not a Forge/Minecraft executor. */
public final class StateDiffReference {
    private int[] values = new int[16];
    private long generation;
    private String[] header;

    private static final class Rejected extends Exception {
        Rejected(String reason) { super(reason); }
    }
    private static void checkCell(int i) throws Rejected {
        if(i<0 || i>=16)throw new Rejected("CELL");
    }
    private static int checkedValue(long value) throws Rejected {
        if(value<0 || value>65535)throw new Rejected("RANGE");
        return (int)value;
    }
    private void emit(int step,String reason,List<String> effects) {
        String status=step==0?"INITIAL":(reason==null?"COMMITTED":"REJECTED");
        System.out.println("{\"schema\":\"STATE_DIFF_BOUNDARY_V1\",\"session\":\""+header[2]+"\",\"challenge\":\""+header[3]+"\",\"trace_sha256\":\""+header[4]+"\",\"qualification\":\"OFFLINE_MODEL_ONLY\",\"production_authority\":false,\"step\":"+step+",\"status\":\""+status+"\",\"error\":"+(reason==null?"null":"\""+reason+"\"")+",\"generation\":"+generation+",\"values\":"+Arrays.toString(values)+",\"effects\":"+effects+"}");
        System.out.flush();
    }
    private void execute(String line,int sequence) throws Exception {
        String[] words=line.split(" ",-1);int[] staged=values.clone();
        List<Integer> destinations=new ArrayList<Integer>();String error=null;
        try {
            if(words[0].equals("ADD") && words.length==3) {
                int cell=Integer.parseInt(words[1]);checkCell(cell);
                staged[cell]=checkedValue((long)values[cell]+Integer.parseInt(words[2]));destinations.add(cell);
            } else if(words[0].equals("COPY") && words.length==3) {
                int source=Integer.parseInt(words[1]),target=Integer.parseInt(words[2]);checkCell(source);checkCell(target);
                staged[target]=values[source];destinations.add(target);
            } else if(words[0].equals("GRADIENT") && words.length==5) {
                int start=Integer.parseInt(words[1]),len=Integer.parseInt(words[2]),base=Integer.parseInt(words[3]),step=Integer.parseInt(words[4]);
                if(len<1 || len>16)throw new Rejected("COUNT");checkCell(start);
                if((long)start+len>16)throw new Rejected("CELL");
                checkedValue(base);if(step<-32768 || step>32767)throw new IllegalArgumentException("step range");
                for(int i=0;i<len;i++){staged[start+i]=checkedValue((long)base+(long)i*step);destinations.add(start+i);}
            } else throw new IllegalArgumentException("operation schema");
        } catch(Rejected rejected) {error=rejected.getMessage();}
        List<String> effects=new ArrayList<String>();
        if(error==null){
            for(int cell:destinations)effects.add("["+cell+","+values[cell]+","+staged[cell]+"]");
            values=staged;generation++;
        }
        emit(sequence,error,effects);
    }
    private void run() throws Exception {
        BufferedReader in=new BufferedReader(new InputStreamReader(System.in,"UTF-8"));
        String initial=in.readLine();if(initial==null)throw new IllegalArgumentException("missing INIT");header=initial.split(" ",-1);
        if(header.length!=6 || !header[0].equals("INIT") || !header[1].equals("STATE_DIFF_MODEL_V1"))throw new IllegalArgumentException("INIT schema");
        for(int i=2;i<5;i++)if(!header[i].matches("[0-9a-fA-F]+"))throw new IllegalArgumentException("binding");
        String[] cells=header[5].split(",",-1);if(cells.length!=16)throw new IllegalArgumentException("cell count");
        for(int i=0;i<16;i++)values[i]=checkedValue(Long.parseLong(cells[i]));
        emit(0,null,Collections.<String>emptyList());String line;int step=0;
        while((line=in.readLine())!=null)execute(line,++step);
    }
    public static void main(String[] args) {
        try {new StateDiffReference().run();}
        catch(Exception error){System.err.println(error.toString());System.exit(2);}
    }
}
