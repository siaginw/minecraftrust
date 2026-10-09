package com.rustcraft.observability.fixtures;
public class Callbacks  {
    public int value(int input) {
        return input*3+1;
    }
    public int fail(RuntimeException original) {
        throw original;
    }
    public static int staticValue(int input) {
        return input+5;
    }
    public int nested(com.rustcraft.telemetry.RuntimeTelemetry.Body<Integer> child)throws Throwable {
        return child.run()+2;
    }
    public static class Inherited extends Callbacks  {
    }
    public static class Override extends Callbacks  {
        public int value(int input) {
            return -input;
        }
    }
}
