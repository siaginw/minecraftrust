#!/usr/bin/env python3
"""Generate machine/protocol-340-packets.yaml from the reference SRG jar via Java reflection.

Enumerates EnumConnectionState direction BiMaps through reflection (they are private),
so the registry is derived from the actual runtime, not hand-transcribed.
"""
import subprocess, json, os, tempfile

JAVA = r"C:\Program Files\Eclipse Adoptium\jdk-8.0.504.1-hotspot\bin\java.exe"
CP = os.pathsep.join([
    r"third_party_reference\minecraft\minecraft_server.1.12.2.srg.jar",
    r"third_party_reference\forge\server\libraries\org\apache\logging\log4j\log4j-api\2.15.0\log4j-api-2.15.0.jar",
    r"third_party_reference\forge\server\libraries\org\apache\logging\log4j\log4j-core\2.15.0\log4j-core-2.15.0.jar",
    r"third_party_reference\forge\server\libraries\com\google\guava\guava\21.0\guava-21.0.jar",
])

JAVA_SRC = r'''
import java.lang.reflect.Field;
import java.util.Map;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.EnumPacketDirection;

public class DumpPacketRegistry {
    public static void main(String[] args) throws Exception {
        Field f = EnumConnectionState.class.getDeclaredField("field_179247_h"); // directionMaps
        f.setAccessible(true);
        StringBuilder sb = new StringBuilder();
        for (EnumConnectionState st : EnumConnectionState.values()) {
            Map<EnumPacketDirection, Object> maps = (Map) f.get(st);
            for (Map.Entry<EnumPacketDirection, Object> e : maps.entrySet()) {
                Map<?, ?> directMap = (Map<?, ?>) e.getValue();
                for (Map.Entry<?, ?> entry : directMap.entrySet()) {
                    Object id = entry.getKey();
                    Class<?> cls = (Class<?>) entry.getValue();
                    sb.append(st.name()).append('|').append(e.getKey().name()).append('|')
                      .append(id).append('|').append(cls.getName()).append('\n');
                }
            }
        }
        System.out.print(sb);
    }
}
'''

def main():
    tmp = tempfile.mkdtemp(prefix="pktreg_")
    src = os.path.join(tmp, "DumpPacketRegistry.java")
    with open(src, "w") as f:
        f.write(JAVA_SRC)
    javac = r"C:\Program Files\Eclipse Adoptium\jdk-8.0.504.1-hotspot\bin\javac.exe"
    subprocess.check_call([javac, "-cp", CP, "-d", tmp, src], cwd=".")
    out = subprocess.check_output([JAVA, "-cp", os.pathsep.join([tmp, CP]), "DumpPacketRegistry"], cwd=".").decode()

    NBT_PACKETS = {"SPacketChunkData", "SPacketUpdateTileEntity", "SPacketJoinGame", "SPacketExplosion",
                   "SPacketWindowItems", "SPacketSetSlot", "SPacketOpenWindow", "SPacketEntityMetaData",
                   "SPacketEntityEquipment", "SPacketPlayerListItem", "SPacketAdvancementInfo", "SPacketMaps"}
    VARIABLE = {"SPacketChunkData", "SPacketCustomPayload", "SPacketMaps", "SPacketCustomSound",
                "SPacketChat", "SPacketPlayerListItem", "SPacketTeams", "SPacketExplosion", "SPacketDestroyEntities",
                "CPacketCustomPayload", "SPacketAdvancementInfo", "SPacketWindowItems", "SPacketEntityProperties",
                "SPacketMultiBlockChange", "SPacketUpdateLight", "SPacketEntityMetadata"}

    lines = ["# Minecraft 1.12.2 (protocol 340) server packet registry.",
             "# Generated from reference runtime reflection over EnumConnectionState (forge-2860-compatible vanilla server).",
             "# Fields: state | direction | id | class | thread_expectation | contains_nbt | variable_size | forge_relevance",
             "", "registry:", f"  protocol_version: 340", "  packets:"]
    for ln in sorted(out.strip().splitlines()):
        state, direction, pid, cls = ln.split("|")
        short = cls.split(".")[-1]
        if direction == "SERVERBOUND":
            thread = "SERVER_THREAD (via PacketThreadUtil.checkThreadAndEnqueue)"
        else:
            thread = "NETTY_IO (encode); construction on SERVER_THREAD"
        nbt = short in NBT_PACKETS or "NBT" in short
        var = short in VARIABLE
        forge_rel = "HIGH" if short in ("SPacketCustomPayload", "CPacketCustomPayload", "SPacketJoinGame", "SPacketChunkData") else ("MEDIUM" if nbt else "LOW")
        lines.append(f"    - state: {state}")
        lines.append(f"      direction: {direction}")
        lines.append(f"      id: {pid}")
        lines.append(f"      class: {cls}")
        lines.append(f"      thread_expectation: \"{thread}\"")
        lines.append(f"      contains_nbt: {str(nbt).lower()}")
        lines.append(f"      variable_size: {str(var).lower()}")
        lines.append(f"      forge_relevance: {forge_rel}")
    with open("machine/protocol-340-packets.yaml", "w", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    n = len([l for l in out.strip().splitlines() if l])
    print(f"Wrote machine/protocol-340-packets.yaml with {n} packet entries")

if __name__ == "__main__":
    main()
