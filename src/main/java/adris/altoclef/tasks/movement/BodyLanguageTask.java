package adris.altoclef.tasks.movement;



import adris.altoclef.AltoClefController;
import adris.altoclef.tasksystem.Task;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;

public class BodyLanguageTask extends Task {

    public enum Type { GREETING, NOD_HEAD, SHAKE_HEAD, VICTORY, SIT, WAVE, DANCE, BOW, SPIN }

    private final Type type;
    private final static float shakeNodHeadAngle = 40;
    private final static int shakeNodHeadTime = 10; // ticks


    private final PrimitiveSequenceTask seqGreeting = makeGreeting();
    private final PrimitiveSequenceTask seqNodHead  = makeNodHead(2);
    private final PrimitiveSequenceTask seqShake    = makeShakeHead(2);
    private final PrimitiveSequenceTask seqVictory  = makeVictoryDance();
    private final PrimitiveSequenceTask seqSit      = makeSit();
    private final PrimitiveSequenceTask seqWave     = makeWave();
    private final PrimitiveSequenceTask seqDance    = makeDance();
    private final PrimitiveSequenceTask seqBow      = makeBow();
    private final PrimitiveSequenceTask seqSpin     = makeSpin();

    private Task actuallyRunningTask;

    public BodyLanguageTask(String type) {
        this.type = parseType(type);
    }

    private static Type parseType(String t) {
        try { return Type.valueOf(t.toUpperCase()); }
        catch (IllegalArgumentException e) { return Type.GREETING; }
    }

    @Override
    protected void onStart() {
        switch (type) {
            case GREETING:
                actuallyRunningTask = seqGreeting;
                break;
            case NOD_HEAD:
                actuallyRunningTask = seqNodHead;
                break;
            case SHAKE_HEAD:
                actuallyRunningTask = seqShake;
                break;
            case VICTORY:
                actuallyRunningTask = seqVictory;
                break;
            case SIT:
                actuallyRunningTask = seqSit;
                break;
            case WAVE:
                actuallyRunningTask = seqWave;
                break;
            case DANCE:
                actuallyRunningTask = seqDance;
                break;
            case BOW:
                actuallyRunningTask = seqBow;
                break;
            case SPIN:
                actuallyRunningTask = seqSpin;
                break;
        }
    }

    @Override
    protected Task onTick() {
        return actuallyRunningTask;
    }

    @Override
    public boolean isFinished() {
        return  actuallyRunningTask != null && actuallyRunningTask.isFinished();
    }

    @Override
    protected boolean isEqual(Task other) {
        if (!(other instanceof BodyLanguageTask)) return false;
        BodyLanguageTask o = (BodyLanguageTask) other;
        return o.type == this.type;
    }

    @Override
    protected void onStop(Task next) {
        AltoClefController mod = this.controller;
        mod.getInputControls().release(Input.SNEAK);
        mod.getInputControls().release(Input.JUMP);
        mod.getInputControls().release(Input.SPRINT);
        mod.getInputControls().release(Input.MOVE_FORWARD);
        mod.getInputControls().release(Input.MOVE_BACK);
        mod.getInputControls().release(Input.MOVE_LEFT);
        mod.getInputControls().release(Input.MOVE_RIGHT);
    }

    @Override
    protected String toDebugString() {
        return "BodyLanguage(" + type + ")";
    }


    private static PrimitiveSequenceTask makeGreeting() {
        PrimitiveSequenceTask.Sequence.Builder b = PrimitiveSequenceTask.builder();
        int sneakCount = 3;
        int holdTicks = 6, pauseTicks = 6;
        for (int i = 0; i < sneakCount; i++) {
            b.jump().waitTicks(pauseTicks).jump().waitTicks(pauseTicks);
            // b.hold(Input.SNEAK).waitTicks(holdTicks).release(Input.SNEAK).waitTicks(pauseTicks);
        }
        return b.build();
    }

    private static PrimitiveSequenceTask makeNodHead(int nods) {
        PrimitiveSequenceTask.Sequence.Builder b = PrimitiveSequenceTask.builder();
        for (int i = 0; i < nods; i++) {
            b.lookRelative(new Rotation(0, +shakeNodHeadAngle), shakeNodHeadTime);
            b.lookRelative(new Rotation(0, -shakeNodHeadAngle), shakeNodHeadTime);
        }
        b.waitTicks(2);
        return b.build();
    }

    private static PrimitiveSequenceTask makeShakeHead(int shakes) {
        PrimitiveSequenceTask.Sequence.Builder b = PrimitiveSequenceTask.builder();
        for (int i = 0; i < shakes; i++) {
            b.lookRelative(new Rotation(+shakeNodHeadAngle, 0), shakeNodHeadTime);
            b.lookRelative(new Rotation(-shakeNodHeadAngle, 0), shakeNodHeadTime);
        }
        return b.build();
    }

    private static PrimitiveSequenceTask makeVictoryDance() {
        return PrimitiveSequenceTask.builder()
                .jump()
                .waitTicks(4)
                .lookRelative(new Rotation(180f, 0), 20)   
                .lookRelative(new Rotation(-180f, 0), 20)
                .hold(Input.SNEAK).waitTicks(6).release(Input.SNEAK)
                .jump()
                .build();
    }

    // 坐下：先低头+蹲下，持续约8秒(160 ticks)，定期重新施加SNEAK以防被外部清除
    private static PrimitiveSequenceTask makeSit() {
        PrimitiveSequenceTask.Sequence.Builder b = PrimitiveSequenceTask.builder();
        // 先略微低头，模拟"坐下"姿态
        b.lookRelative(new Rotation(0, +20f), 10);
        // 持续蹲伏，每40tick(2秒)重新施加SNEAK以确保不被外部清除
        int totalWait = 160; // 8秒
        int reapplyInterval = 40;
        for (int elapsed = 0; elapsed < totalWait; elapsed += reapplyInterval) {
            b.hold(Input.SNEAK).waitTicks(reapplyInterval);
        }
        b.release(Input.SNEAK);
        // 恢复视角
        b.lookRelative(new Rotation(0, -20f), 10);
        return b.build();
    }

    // 挥手：反复跳跃+左右转头，约4秒
    private static PrimitiveSequenceTask makeWave() {
        PrimitiveSequenceTask.Sequence.Builder b = PrimitiveSequenceTask.builder();
        for (int i = 0; i < 3; i++) {
            b.jump().waitTicks(4);
            b.lookRelative(new Rotation(+30f, 0), 8);
            b.lookRelative(new Rotation(-60f, 0), 8);
            b.lookRelative(new Rotation(+30f, 0), 8);
        }
        return b.build();
    }

    // 跳舞：欢快的跳跃+旋转+蹲起组合，约8秒
    private static PrimitiveSequenceTask makeDance() {
        PrimitiveSequenceTask.Sequence.Builder b = PrimitiveSequenceTask.builder();
        for (int i = 0; i < 4; i++) {
            b.jump().waitTicks(4);
            b.lookRelative(new Rotation(90f, 0), 15);
            b.hold(Input.SNEAK).waitTicks(6).release(Input.SNEAK);
            b.waitTicks(4);
            b.jump().waitTicks(4);
            b.lookRelative(new Rotation(-90f, 0), 15);
        }
        return b.build();
    }

    // 鞠躬：多次快速蹲起，约3秒
    private static PrimitiveSequenceTask makeBow() {
        PrimitiveSequenceTask.Sequence.Builder b = PrimitiveSequenceTask.builder();
        for (int i = 0; i < 3; i++) {
            b.hold(Input.SNEAK).waitTicks(10).release(Input.SNEAK).waitTicks(5);
            b.lookRelative(new Rotation(0, +15f), 5);
            b.lookRelative(new Rotation(0, -15f), 5);
        }
        return b.build();
    }

    // 旋转：360度原地旋转，约4秒
    private static PrimitiveSequenceTask makeSpin() {
        PrimitiveSequenceTask.Sequence.Builder b = PrimitiveSequenceTask.builder();
        for (int i = 0; i < 4; i++) {
            b.lookRelative(new Rotation(90f, 0), 20);
        }
        return b.build();
    }
}