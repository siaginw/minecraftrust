import java.lang.reflect.*;
import net.minecraft.network.play.server.SPacketChunkData;

public class InspectSPacketChunkData {
    public static void main(String[] args) {
        System.out.println("Class: " + SPacketChunkData.class.getName());
        System.out.println("--- Constructors ---");
        for (Constructor<?> c : SPacketChunkData.class.getDeclaredConstructors()) {
            System.out.println("  " + c);
        }
        System.out.println("--- Methods ---");
        for (Method m : SPacketChunkData.class.getDeclaredMethods()) {
            System.out.println("  " + m);
        }
        System.out.println("--- Fields ---");
        for (Field f : SPacketChunkData.class.getDeclaredFields()) {
            System.out.println("  " + f);
        }
    }
}
