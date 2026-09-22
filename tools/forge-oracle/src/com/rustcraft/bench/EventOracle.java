package com.rustcraft.bench;

import com.google.common.collect.Lists;
import net.minecraftforge.fml.common.eventhandler.*;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

public class EventOracle {

    public static void main(String[] args) throws Exception {
        System.out.println("=== P0-7 EventBus Reference Oracle Suite ===");
        File outDir = new File("benchmarks/forge/p0-7");
        outDir.mkdirs();
        PrintWriter pw = new PrintWriter(new FileWriter(new File(outDir, "event_oracle_results.txt")));

        testPriorityOrdering(pw);
        testCancellationSemantics(pw);
        testResultSemantics(pw);
        testInheritanceOrdering(pw);
        testUnregister(pw);

        pw.close();
        System.out.println("=== P0-7 EventBus Oracle Complete ===");
    }

    // --- TEST 1: Priority & Registration Ordering ---
    public static class PriorityEvent extends Event {}

    public static class PrioritySubscriber1 {
        List<String> log;
        public PrioritySubscriber1(List<String> log) { this.log = log; }

        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void onHighest(PriorityEvent e) { log.add("HIGHEST"); }

        @SubscribeEvent(priority = EventPriority.HIGH)
        public void onHigh(PriorityEvent e) { log.add("HIGH"); }

        @SubscribeEvent(priority = EventPriority.NORMAL)
        public void onNormalA(PriorityEvent e) { log.add("NORMAL_A"); }

        @SubscribeEvent(priority = EventPriority.LOW)
        public void onLow(PriorityEvent e) { log.add("LOW"); }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void onLowest(PriorityEvent e) { log.add("LOWEST"); }
    }

    public static class PrioritySubscriber2 {
        List<String> log;
        public PrioritySubscriber2(List<String> log) { this.log = log; }

        @SubscribeEvent(priority = EventPriority.NORMAL)
        public void onNormalB(PriorityEvent e) { log.add("NORMAL_B"); }
    }

    static void testPriorityOrdering(PrintWriter pw) {
        EventBus bus = new EventBus();
        List<String> log = new ArrayList<>();
        PrioritySubscriber1 sub1 = new PrioritySubscriber1(log);
        PrioritySubscriber2 sub2 = new PrioritySubscriber2(log);
        bus.register(sub1);
        bus.register(sub2); // sub2 registered after sub1 at NORMAL priority

        PriorityEvent event = new PriorityEvent();
        bus.post(event);

        String result = String.join(" -> ", log);
        String expected = "HIGHEST -> HIGH -> NORMAL_A -> NORMAL_B -> LOW -> LOWEST";
        boolean pass = expected.equals(result);
        log(pw, "TEST 1 [Priority & FIFO Order]: %s | Actual: %s", pass ? "PASS" : "FAIL", result);
    }

    // --- TEST 2: Cancellation & receiveCanceled ---
    @Cancelable
    public static class CancelableEvent extends Event {
        @Override
        public boolean isCancelable() { return true; }
    }

    public static class CancelSubscriber {
        List<String> log = new ArrayList<>();

        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void onHighest(CancelableEvent e) {
            log.add("HIGHEST_CANCELS");
            e.setCanceled(true);
        }

        @SubscribeEvent(priority = EventPriority.HIGH)
        public void onHighIgnored(CancelableEvent e) {
            log.add("HIGH_SHOULD_BE_SKIPPED");
        }

        @SubscribeEvent(priority = EventPriority.NORMAL, receiveCanceled = true)
        public void onNormalReceiveCanceled(CancelableEvent e) {
            log.add("NORMAL_RECEIVES_CANCELED");
        }

        @SubscribeEvent(priority = EventPriority.LOW, receiveCanceled = false)
        public void onLowIgnored(CancelableEvent e) {
            log.add("LOW_SHOULD_BE_SKIPPED");
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void onLowestReceiveCanceled(CancelableEvent e) {
            log.add("LOWEST_RECEIVES_CANCELED");
        }
    }

    static void testCancellationSemantics(PrintWriter pw) {
        EventBus bus = new EventBus();
        CancelSubscriber sub = new CancelSubscriber();
        bus.register(sub);

        CancelableEvent event = new CancelableEvent();
        boolean canceled = bus.post(event);

        String result = String.join(" -> ", sub.log);
        String expected = "HIGHEST_CANCELS -> NORMAL_RECEIVES_CANCELED -> LOWEST_RECEIVES_CANCELED";
        boolean pass = expected.equals(result) && canceled && event.isCanceled();
        log(pw, "TEST 2 [Cancellation Semantics]: %s | CanceledReturn: %b | Sequence: %s", pass ? "PASS" : "FAIL", canceled, result);
    }

    // --- TEST 3: Event.Result Semantics ---
    @Event.HasResult
    public static class ResultEvent extends Event {
        @Override
        public boolean hasResult() { return true; }
    }

    public static class ResultSubscriber {
        Event.Result observed;

        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void onHighest(ResultEvent e) {
            e.setResult(Event.Result.ALLOW);
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void onLowest(ResultEvent e) {
            observed = e.getResult();
        }
    }

    static void testResultSemantics(PrintWriter pw) {
        EventBus bus = new EventBus();
        ResultSubscriber sub = new ResultSubscriber();
        bus.register(sub);

        ResultEvent event = new ResultEvent();
        bus.post(event);

        boolean pass = sub.observed == Event.Result.ALLOW;
        log(pw, "TEST 3 [Event.Result Mutation]: %s | Observed: %s", pass ? "PASS" : "FAIL", sub.observed);
    }

    // --- TEST 4: Inheritance Hierarchy Ordering ---
    public static class ParentEvent extends Event {
        private static final ListenerList PARENT_LIST = new ListenerList();
        @Override
        public ListenerList getListenerList() { return PARENT_LIST; }
    }

    public static class ChildEvent extends ParentEvent {
        private static final ListenerList CHILD_LIST = new ListenerList(ParentEvent.PARENT_LIST);
        @Override
        public ListenerList getListenerList() { return CHILD_LIST; }
    }

    public static class HierarchySubscriber {
        List<String> log = new ArrayList<>();

        @SubscribeEvent
        public void onParent(ParentEvent e) {
            log.add("PARENT_LISTENER");
        }

        @SubscribeEvent
        public void onChild(ChildEvent e) {
            log.add("CHILD_LISTENER");
        }
    }

    static void testInheritanceOrdering(PrintWriter pw) {
        EventBus bus = new EventBus();
        HierarchySubscriber sub = new HierarchySubscriber();
        bus.register(sub);

        ChildEvent event = new ChildEvent();
        bus.post(event);

        // Forge ListenerListInst.getListeners adds child listeners first, then parent listeners
        String result = String.join(" -> ", sub.log);
        String expected = "CHILD_LISTENER -> PARENT_LISTENER";
        boolean pass = expected.equals(result);
        log(pw, "TEST 4 [Inheritance Ordering]: %s | Actual: %s", pass ? "PASS" : "FAIL", result);
    }

    // --- TEST 5: Unregistration ---
    static void testUnregister(PrintWriter pw) {
        EventBus bus = new EventBus();
        List<String> log = new ArrayList<>();
        PrioritySubscriber1 sub = new PrioritySubscriber1(log);
        bus.register(sub);
        bus.unregister(sub);

        PriorityEvent event = new PriorityEvent();
        bus.post(event);

        boolean pass = log.isEmpty();
        log(pw, "TEST 5 [Unregister]: %s | InvocationsAfterUnregister: %d", pass ? "PASS" : "FAIL", log.size());
    }

    static void log(PrintWriter pw, String fmt, Object... args) {
        String s = String.format(fmt, args);
        System.out.println(s);
        pw.println(s);
    }
}
