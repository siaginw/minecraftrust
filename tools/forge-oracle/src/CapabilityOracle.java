import net.minecraftforge.common.capabilities.*;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

public class CapabilityOracle {

    public interface ITestPower {
        int getEnergy();
        void addEnergy(int amount);
    }

    public static class DefaultTestPower implements ITestPower {
        int energy = 100;
        public int getEnergy() { return energy; }
        public void addEnergy(int amount) { this.energy += amount; }
    }

    public static class TestPowerStorage implements Capability.IStorage<ITestPower> {
        @Override
        public gn writeNBT(Capability<ITestPower> capability, ITestPower instance, fa side) {
            fy tag = new fy();
            tag.a("energy", instance.getEnergy());
            return tag;
        }

        @Override
        public void readNBT(Capability<ITestPower> capability, ITestPower instance, fa side, gn nbt) {
            if (nbt instanceof fy) {
                instance.addEnergy(((fy) nbt).h("energy") - instance.getEnergy());
            }
        }
    }

    public static Capability<ITestPower> POWER_CAP = null;

    public static void main(String[] args) throws Exception {
        System.out.println("=== P0-7 Capability Reference Oracle Suite ===");
        File outDir = new File("benchmarks/forge/p0-7");
        outDir.mkdirs();
        PrintWriter pw = new PrintWriter(new FileWriter(new File(outDir, "capability_oracle_results.txt")));

        // Register capability
        CapabilityManager.INSTANCE.register(ITestPower.class, new TestPowerStorage(), DefaultTestPower::new);

        // Extract registered capability
        Field fProviders = CapabilityManager.class.getDeclaredField("providers");
        fProviders.setAccessible(true);
        IdentityHashMap<String, Capability<?>> providers = (IdentityHashMap<String, Capability<?>>) fProviders.get(CapabilityManager.INSTANCE);
        POWER_CAP = (Capability<ITestPower>) providers.get(ITestPower.class.getName().intern());

        testCapabilityDispatch(pw);
        testSidedCapability(pw);
        testNbtSerialization(pw);

        pw.close();
        System.out.println("=== P0-7 Capability Oracle Complete ===");
    }

    static void testCapabilityDispatch(PrintWriter pw) {
        DefaultTestPower powerImpl = new DefaultTestPower();
        ICapabilityProvider provider = new ICapabilityProvider() {
            @Override
            public boolean hasCapability(Capability<?> capability, @Nullable fa facing) {
                return capability == POWER_CAP;
            }

            @Nullable
            @Override
            public <T> T getCapability(Capability<T> capability, @Nullable fa facing) {
                return capability == POWER_CAP ? POWER_CAP.cast(powerImpl) : null;
            }
        };

        Map<nf, ICapabilityProvider> map = new HashMap<>();
        map.put(new nf("testmod", "power"), provider);
        CapabilityDispatcher dispatcher = new CapabilityDispatcher(map);

        boolean has = dispatcher.hasCapability(POWER_CAP, null);
        ITestPower retrieved = dispatcher.getCapability(POWER_CAP, null);
        boolean pass = has && (retrieved != null) && (retrieved.getEnergy() == 100);

        log(pw, "TEST 1 [Capability Dispatcher Query]: %s | Has: %b, Energy: %d", pass ? "PASS" : "FAIL", has, retrieved != null ? retrieved.getEnergy() : -1);
    }

    static void testSidedCapability(PrintWriter pw) {
        DefaultTestPower northPower = new DefaultTestPower();
        northPower.addEnergy(50); // 150

        // fa.c is NORTH
        fa northFacing = fa.c;
        fa southFacing = fa.d;

        ICapabilityProvider sidedProvider = new ICapabilityProvider() {
            @Override
            public boolean hasCapability(Capability<?> capability, @Nullable fa facing) {
                return capability == POWER_CAP && facing == northFacing;
            }

            @Nullable
            @Override
            public <T> T getCapability(Capability<T> capability, @Nullable fa facing) {
                return (capability == POWER_CAP && facing == northFacing) ? POWER_CAP.cast(northPower) : null;
            }
        };

        Map<nf, ICapabilityProvider> map = new HashMap<>();
        map.put(new nf("testmod", "sided_power"), sidedProvider);
        CapabilityDispatcher dispatcher = new CapabilityDispatcher(map);

        boolean hasNorth = dispatcher.hasCapability(POWER_CAP, northFacing);
        boolean hasSouth = dispatcher.hasCapability(POWER_CAP, southFacing);
        ITestPower north = dispatcher.getCapability(POWER_CAP, northFacing);
        ITestPower south = dispatcher.getCapability(POWER_CAP, southFacing);

        boolean pass = hasNorth && !hasSouth && (north != null && north.getEnergy() == 150) && (south == null);
        log(pw, "TEST 2 [Sided Capability Invariant]: %s | North: %b, South: %b", pass ? "PASS" : "FAIL", hasNorth, hasSouth);
    }

    static void testNbtSerialization(PrintWriter pw) {
        DefaultTestPower power = new DefaultTestPower();
        power.addEnergy(250); // 350

        ICapabilitySerializable<fy> serProvider = new ICapabilitySerializable<fy>() {
            @Override
            public boolean hasCapability(Capability<?> capability, @Nullable fa facing) {
                return capability == POWER_CAP;
            }

            @Nullable
            @Override
            public <T> T getCapability(Capability<T> capability, @Nullable fa facing) {
                return capability == POWER_CAP ? POWER_CAP.cast(power) : null;
            }

            @Override
            public fy serializeNBT() {
                return (fy) POWER_CAP.writeNBT(power, null);
            }

            @Override
            public void deserializeNBT(fy nbt) {
                POWER_CAP.readNBT(power, null, nbt);
            }
        };

        Map<nf, ICapabilityProvider> map = new HashMap<>();
        map.put(new nf("testmod", "ser_power"), serProvider);
        CapabilityDispatcher dispatcher = new CapabilityDispatcher(map);

        fy tag = dispatcher.serializeNBT();
        fy subTag = tag.p("testmod:ser_power");
        int savedEnergy = subTag.h("energy");

        boolean pass = (savedEnergy == 350);
        log(pw, "TEST 3 [NBT Serialization Structure]: %s | SavedEnergy: %d", pass ? "PASS" : "FAIL", savedEnergy);
    }

    static void log(PrintWriter pw, String fmt, Object... args) {
        String s = String.format(fmt, args);
        System.out.println(s);
        pw.println(s);
    }
}
