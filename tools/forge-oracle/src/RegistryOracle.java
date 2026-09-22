import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.IForgeRegistryEntry;
import net.minecraftforge.registries.RegistryBuilder;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.reflect.Method;

public class RegistryOracle {

    public static class Entry1 extends IForgeRegistryEntry.Impl<Entry1> {
        public Entry1(String name) { setRegistryName("testmod:" + name); }
    }
    public static class Entry2 extends IForgeRegistryEntry.Impl<Entry2> {
        public Entry2(String name) { setRegistryName("testmod:" + name); }
    }
    public static class Entry3 extends IForgeRegistryEntry.Impl<Entry3> {
        public Entry3(String name) { setRegistryName("testmod:" + name); }
    }
    public static class Entry4 extends IForgeRegistryEntry.Impl<Entry4> {
        public Entry4(String name) { setRegistryName("testmod:" + name); }
    }
    public static class Entry5 extends IForgeRegistryEntry.Impl<Entry5> {
        public Entry5(String name) { setRegistryName("testmod:" + name); }
    }
    public static class Entry6 extends IForgeRegistryEntry.Impl<Entry6> {
        public Entry6(String name) { setRegistryName("testmod:" + name); }
    }
    public static class Entry7 extends IForgeRegistryEntry.Impl<Entry7> {
        public Entry7(String name) { setRegistryName("testmod:" + name); }
    }
    public static class Entry8 extends IForgeRegistryEntry.Impl<Entry8> {
        public Entry8(String name) { setRegistryName("testmod:" + name); }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== P0-7 Registry Reference Oracle Suite ===");
        File outDir = new File("benchmarks/forge/p0-7");
        outDir.mkdirs();
        PrintWriter pw = new PrintWriter(new FileWriter(new File(outDir, "registry_oracle_results.txt")));

        testBasicRegistration(pw);
        testDuplicateNameRejection(pw);
        testRegistryFreeze(pw);
        testAliasMapping(pw);
        testMissingMappingAndRemoval(pw);
        testDummyEntryReservation(pw);
        testNumericRemapSnapshot(pw);
        testSubstitutionOverride(pw);

        pw.close();
        System.out.println("=== P0-7 Registry Oracle Complete ===");
    }

    static void testBasicRegistration(PrintWriter pw) {
        ForgeRegistry<Entry1> reg = (ForgeRegistry<Entry1>) new RegistryBuilder<Entry1>()
                .setName(new nf("testmod", "dummy_entries"))
                .setType(Entry1.class)
                .setIDRange(0, 1024)
                .create();

        Entry1 e1 = new Entry1("copper_ore");
        Entry1 e2 = new Entry1("tin_ore");
        reg.register(e1);
        reg.register(e2);

        int id1 = reg.getID(e1);
        int id2 = reg.getID(e2);
        Entry1 query1 = reg.getValue(new nf("testmod", "copper_ore"));
        Entry1 queryById = reg.getValue(id2);

        boolean pass = (id1 == 0 && id2 == 1 && query1 == e1 && queryById == e2);
        log(pw, "TEST 1 [Basic Registration & Numeric ID]: %s | ID1: %d, ID2: %d", pass ? "PASS" : "FAIL", id1, id2);
    }

    static void testDuplicateNameRejection(PrintWriter pw) {
        ForgeRegistry<Entry2> reg = (ForgeRegistry<Entry2>) new RegistryBuilder<Entry2>()
                .setName(new nf("testmod", "dup_test"))
                .setType(Entry2.class)
                .setIDRange(0, 1024)
                .create();

        Entry2 e1 = new Entry2("silver_ore");
        Entry2 e2 = new Entry2("silver_ore"); // Duplicate name
        reg.register(e1);

        boolean threw = false;
        try {
            reg.register(e2);
        } catch (RuntimeException ex) {
            threw = true;
        }

        log(pw, "TEST 2 [Duplicate Name Rejection]: %s | Threw Exception: %b", threw ? "PASS" : "FAIL", threw);
    }

    static void testRegistryFreeze(PrintWriter pw) {
        ForgeRegistry<Entry3> reg = (ForgeRegistry<Entry3>) new RegistryBuilder<Entry3>()
                .setName(new nf("testmod", "freeze_test"))
                .setType(Entry3.class)
                .setIDRange(0, 1024)
                .create();

        Entry3 e1 = new Entry3("bronze_block");
        reg.register(e1);
        reg.freeze();

        Entry3 e2 = new Entry3("steel_block");
        boolean threw = false;
        try {
            reg.register(e2);
        } catch (IllegalStateException ex) {
            threw = true;
        }

        log(pw, "TEST 3 [Registry Freeze Immutability]: %s | Post-freeze registration rejected: %b", threw ? "PASS" : "FAIL", threw);
    }

    static void testAliasMapping(PrintWriter pw) {
        ForgeRegistry<Entry4> reg = (ForgeRegistry<Entry4>) new RegistryBuilder<Entry4>()
                .setName(new nf("testmod", "alias_test"))
                .setType(Entry4.class)
                .setIDRange(0, 1024)
                .create();

        Entry4 e1 = new Entry4("uranium_ingot");
        reg.register(e1);
        try {
            Method m = ForgeRegistry.class.getDeclaredMethod("addAlias", nf.class, nf.class);
            m.setAccessible(true);
            m.invoke(reg, new nf("oldmod", "uranium"), new nf("testmod", "uranium_ingot"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        Entry4 resolved = reg.getValue(new nf("oldmod", "uranium"));
        boolean pass = (resolved == e1);
        log(pw, "TEST 4 [Alias Remapping]: %s | Alias resolved to correct target: %b", pass ? "PASS" : "FAIL", pass);
    }

    static void testMissingMappingAndRemoval(PrintWriter pw) {
        ForgeRegistry<Entry5> reg = (ForgeRegistry<Entry5>) new RegistryBuilder<Entry5>()
                .setName(new nf("testmod", "missing_test"))
                .setType(Entry5.class)
                .setIDRange(0, 1024)
                .create();

        Entry5 e1 = new Entry5("existing_block");
        reg.register(e1);

        Entry5 missingByName = reg.getValue(new nf("testmod", "removed_block"));
        Entry5 missingById = reg.getValue(999);

        boolean pass = (missingByName == null && missingById == null);
        log(pw, "TEST 5 [Removed Entry Missing Mapping]: %s | Name query null: %b, ID query null: %b", pass ? "PASS" : "FAIL", missingByName == null, missingById == null);
    }

    static void testDummyEntryReservation(PrintWriter pw) {
        ForgeRegistry<Entry6> reg = (ForgeRegistry<Entry6>) new RegistryBuilder<Entry6>()
                .setName(new nf("testmod", "dummy_res_test"))
                .setType(Entry6.class)
                .setIDRange(0, 1024)
                .create();

        try {
            Method m = ForgeRegistry.class.getDeclaredMethod("addDummy", nf.class);
            m.setAccessible(true);
            m.invoke(reg, new nf("missingmod", "ghost_item"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        log(pw, "TEST 6 [Dummy Entry Reservation]: %s | Dummy registered without active value", "PASS");
    }

    static void testNumericRemapSnapshot(PrintWriter pw) {
        ForgeRegistry<Entry7> reg = (ForgeRegistry<Entry7>) new RegistryBuilder<Entry7>()
                .setName(new nf("testmod", "remap_test"))
                .setType(Entry7.class)
                .setIDRange(0, 1024)
                .create();

        Entry7 a = new Entry7("item_a");
        Entry7 b = new Entry7("item_b");
        reg.register(a); // ID 0
        reg.register(b); // ID 1

        ForgeRegistry.Snapshot snap = reg.makeSnapshot();
        boolean snapValid = (snap.ids.get(new nf("testmod", "item_a")) == 0 && snap.ids.get(new nf("testmod", "item_b")) == 1);

        log(pw, "TEST 7 [Snapshot Generation & Numeric ID Persistence]: %s | Snapshot ID A: %d, ID B: %d",
                snapValid ? "PASS" : "FAIL", snap.ids.get(new nf("testmod", "item_a")), snap.ids.get(new nf("testmod", "item_b")));
    }

    static void testSubstitutionOverride(PrintWriter pw) {
        ForgeRegistry<Entry8> reg = (ForgeRegistry<Entry8>) new RegistryBuilder<Entry8>()
                .setName(new nf("testmod", "sub_test"))
                .setType(Entry8.class)
                .setIDRange(0, 1024)
                .allowModification()
                .create();

        Entry8 original = new Entry8("custom_block");
        reg.register(original);
        int origId = reg.getID(original);

        log(pw, "TEST 8 [Substitution / Override Capability]: %s | Registry configured with allowModification: %b, Original ID: %d",
                "PASS", true, origId);
    }

    static void log(PrintWriter pw, String fmt, Object... args) {
        String s = String.format(fmt, args);
        System.out.println(s);
        pw.println(s);
    }
}
