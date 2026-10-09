import net.minecraft.init.Bootstrap;
import net.minecraft.world.chunk.Chunk;

public class ProbeSentinel {
    public static void main(String[] args) {
        Bootstrap.func_151354_b();
        System.out.println("field_186036_a = " + Chunk.field_186036_a);
        System.out.println("is null: " + (Chunk.field_186036_a == null));
    }
}
