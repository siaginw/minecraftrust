import net.minecraft.world.chunk.Chunk;

public class InspectChunkCtor {
    public static void main(String[] args) {
        try {
            Chunk c = new Chunk(null, 10, 20);
            System.out.println("Chunk created: " + c);
            System.out.println("Biome array: " + c.func_76605_m());
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
