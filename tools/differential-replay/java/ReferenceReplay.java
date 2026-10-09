import java.io.*;
import java.util.*;

/** Independent Java8 reference for the written REPLAY_TOY_V1 spec. */
public final class ReferenceReplay {
    private final int[] cells = new int[16];
    private int position, health;
    private long clock, serial, random;
    private boolean backwards;
    private final PriorityQueue<long[]> queue = new PriorityQueue<long[]>(11, new Comparator<long[]>() {
        public int compare(long[] a, long[] b) {
            int time = Long.compare(a[0], b[0]);
            return time != 0 ? time : Long.compare(a[1], b[1]);
        }
    });

    private static String hex(byte[] data) {
        StringBuilder out = new StringBuilder();
        for (byte b : data) out.append(String.format(Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
    private static String quoted(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return quoted((String)value);
        if (value instanceof Number) return value.toString();
        if (value instanceof long[]) {
            List<Long> list = new ArrayList<Long>();
            for (long number : (long[])value) list.add(number);
            return json(list);
        }
        if (value instanceof Iterable) {
            StringBuilder s = new StringBuilder("[");
            for (Object item : (Iterable<?>)value) { if (s.length() > 1) s.append(','); s.append(json(item)); }
            return s.append(']').toString();
        }
        if (value instanceof Map) {
            StringBuilder s = new StringBuilder("{");
            for (Map.Entry<?,?> e : ((Map<?,?>)value).entrySet()) { if (s.length() > 1) s.append(','); s.append(quoted((String)e.getKey())).append(':').append(json(e.getValue())); }
            return s.append('}').toString();
        }
        throw new IllegalArgumentException("unhandled JSON value");
    }
    private List<long[]> scheduled() {
        List<long[]> list = new ArrayList<long[]>(queue);
        Collections.sort(list, queue.comparator());
        return list;
    }
    private static void tag(DataOutputStream out, int type, String name) throws IOException {
        out.writeByte(type); out.writeUTF(name); // fixed ASCII names, also canonical UTF-8
    }
    private String save() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        tag(out, 10, "");
        tag(out, 3, "x"); out.writeInt(position);
        tag(out, 3, "health"); out.writeInt(health);
        tag(out, 4, "time"); out.writeLong(clock);
        tag(out, 4, "rng"); out.writeLong(random);
        tag(out, 4, "serial"); out.writeLong(serial);
        tag(out, 7, "blocks"); out.writeInt(16); for (int cell : cells) out.writeByte(cell);
        tag(out, 9, "scheduled"); out.writeByte(4); out.writeInt(queue.size() * 4);
        for (long[] tick : scheduled()) for (long value : tick) out.writeLong(value);
        out.writeByte(0); out.flush();
        return hex(bytes.toByteArray());
    }
    private void callback(List<String> events, String kind) {
        events.add((backwards ? "B:" : "A:") + kind);
        events.add((backwards ? "A:" : "B:") + kind);
    }
    private void set(int cell, int value, List<long[]> mutations) {
        mutations.add(new long[]{cell, cells[cell], value}); cells[cell] = value;
    }
    private static void require(boolean value, String reason) {
        if (!value) throw new IllegalArgumentException(reason);
    }

    private Map<String,Object> execute(String command) throws Exception {
        String[] words = command.split(" ", -1);
        String op = words[0];
        List<long[]> mutations = new ArrayList<long[]>();
        List<String> packets = new ArrayList<String>(), callbacks = new ArrayList<String>();
        String error = null;
        if (op.equals("SET")) {
            require(words.length == 3, "SET arguments");
            int cell = Integer.parseInt(words[1]), value = Integer.parseInt(words[2]);
            require(cell >= 0 && cell < 16 && value >= 0 && value < 256, "SET range");
            set(cell, value, mutations); packets.add(hex(new byte[]{2,(byte)cell,(byte)value}));
        } else if (op.equals("MOVE")) {
            require(words.length == 2, "MOVE arguments");
            position += Integer.parseInt(words[1]); // JVM specified two's-complement wrap
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes); out.writeByte(1);out.writeInt(position);out.flush();packets.add(hex(bytes.toByteArray()));
        } else if (op.equals("SCHEDULE")) {
            require(words.length == 4, "SCHEDULE arguments");
            int delay = Integer.parseInt(words[1]), cell = Integer.parseInt(words[2]), delta = Integer.parseInt(words[3]);
            require(delay >= 1 && delay <= 1000 && cell >= 0 && cell < 16 && delta >= -255 && delta <= 255, "SCHEDULE range");
            queue.add(new long[]{clock + delay, ++serial, cell, delta});
        } else if (op.equals("TICK")) {
            require(words.length == 2, "TICK arguments");
            int count = Integer.parseInt(words[1]);require(count >= 1 && count <= 100, "TICK range");
            long end = clock + count;
            while (!queue.isEmpty() && queue.peek()[0] <= end) {
                long[] tick = queue.remove();
                clock = tick[0]; int cell = (int)tick[2];
                int value = (cells[cell] + (int)tick[3]) % 256;
                if (value < 0) value += 256;
                set(cell, value, mutations);callback(callbacks, "DUE");
            }
            clock = end;
        } else if (op.equals("RNG")) {
            require(words.length == 2, "RNG arguments");
            int bound = Integer.parseInt(words[1]); require(bound >= 1 && bound <= 256, "RNG range");
            random = (random * 1664525L + 1013904223L) & 0xffffffffL;
            int value = (int)(random % bound);set(0, value, mutations);packets.add(hex(new byte[]{3,(byte)value}));
        } else if (op.equals("DIV")) {
            require(words.length == 2, "DIV arguments");
            int divisor = Integer.parseInt(words[1]);require(divisor >= 0, "DIV range");
            if (divisor == 0) error = "DIVIDE_BY_ZERO"; else health /= divisor;
        } else require(op.equals("SAVE") && words.length == 1, "unknown command");
        if (error == null) callback(callbacks, op);
        Map<String,Object> result = new LinkedHashMap<String,Object>();
        result.put("outcome", error == null ? "OK" : "CONTROLLED_ERROR");result.put("error", error);
        result.put("world_mutations", mutations);result.put("entity_state", new long[]{position,health});
        result.put("scheduled_ticks", scheduled());result.put("packet_outputs",packets);result.put("save_state",save());
        result.put("callbacks",callbacks);result.put("rng_state",random);
        return result;
    }
    private void run() throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
        String first = in.readLine();require(first != null,"missing INIT");String[] h = first.split(" ",-1);
        require(h.length == 10 && h[0].equals("INIT") && h[1].equals("REPLAY_TOY_V1") && (h[9].equals("AB") || h[9].equals("BA")), "invalid INIT");
        for (int i=2;i<=4;i++) require(h[i].matches("[0-9a-fA-F]+"), "invalid binding");
        random = Long.parseLong(h[5]); require(random>=0 && random<=0xffffffffL,"seed range");
        position = Integer.parseInt(h[6]);health=Integer.parseInt(h[7]);require(health>=0,"health range");
        String[] cellWords=h[8].split(",",-1);require(cellWords.length==16,"16 cells required");
        for(int i=0;i<16;i++){cells[i]=Integer.parseInt(cellWords[i]);require(cells[i]>=0&&cells[i]<=255,"cell range");}
        backwards = h[9].equals("BA");
        Map<String,Object> initial=execute("SAVE");initial.put("callbacks",Collections.emptyList());
        initial.put("schema","REPLAY_BOUNDARY_V1");initial.put("session",h[2]);initial.put("challenge",h[3]);initial.put("trace_sha256",h[4]);initial.put("step",0);initial.put("command","INIT");
        System.out.println(json(initial));System.out.flush();
        int sequence=0;String command;
        while((command=in.readLine())!=null){
            Map<String,Object> row=new LinkedHashMap<String,Object>();row.put("schema","REPLAY_BOUNDARY_V1");
            row.put("session",h[2]);row.put("challenge",h[3]);row.put("trace_sha256",h[4]);row.put("step",++sequence);row.put("command",command);
            row.putAll(execute(command));System.out.println(json(row));System.out.flush();
        }
    }
    public static void main(String[] args) {
        try { new ReferenceReplay().run(); }
        catch(Exception failure) { System.err.println(failure.toString());System.exit(2); }
    }
}
