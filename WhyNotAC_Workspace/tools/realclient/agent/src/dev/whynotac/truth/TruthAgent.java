package dev.whynotac.truth;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.Set;
import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassVisitor;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;

/**
 * Ground truth from inside the official, unmodified Minecraft client: a java agent that adds two calls
 * to {@code LocalPlayer.tick()} (at its start and before every return) which record the player's exact
 * physics state (position, velocity, rotation, collision flags, input) once per client tick, and two to
 * {@code AbstractBoat.tick()}, which record a boat the player steers (its tick runs before the player's), and two to
 * {@code AbstractHorse.tick()}, which record a horse (donkey, mule, ...) the player rides, likewise, and two to
 * {@code Mob.tick()}, which record a pig the player steers (a pig has no tick of its own) in the horse's form, and two to
 * {@code Strider.tick()}, {@code HappyGhast.tick()} and {@code AbstractNautilus.tick()}, which record a strider, a happy ghast
 * and a nautilus the player steers likewise, and two to {@code Camel.tick()} (around its {@code AbstractHorse.tick()}, which
 * then records nothing of a camel), which record a camel.
 *
 * <p>Nothing else in the client changes, and the recorder only reads. Obfuscated names come from Mojang's
 * own mappings file for the same version. Uses the JDK's internal copy of ASM, so the agent has no
 * dependencies; the client JVM needs {@code --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED}.
 *
 * <p>Agent arguments: {@code <mappings client.txt>,<output .jsonl>}.
 */
public final class TruthAgent {

    static final String LOCAL_PLAYER = "net.minecraft.client.player.LocalPlayer";
    static final String ABSTRACT_BOAT = "net.minecraft.world.entity.vehicle.boat.AbstractBoat";
    static final String ABSTRACT_HORSE = "net.minecraft.world.entity.animal.equine.AbstractHorse";
    static final String MOB = "net.minecraft.world.entity.Mob";
    static final String PIG = "net.minecraft.world.entity.animal.pig.Pig";
    static final String STRIDER = "net.minecraft.world.entity.monster.Strider";
    static final String HAPPY_GHAST = "net.minecraft.world.entity.animal.happyghast.HappyGhast";
    static final String NAUTILUS = "net.minecraft.world.entity.animal.nautilus.AbstractNautilus";
    static final String CAMEL = "net.minecraft.world.entity.animal.camel.Camel";
    static final Set<String> CLASSES = Set.of(LOCAL_PLAYER, ABSTRACT_BOAT, ABSTRACT_HORSE, MOB, PIG, STRIDER, HAPPY_GHAST, NAUTILUS, CAMEL, "net.minecraft.world.entity.ItemBasedSteering", "net.minecraft.world.entity.ai.attributes.Attributes",
            "net.minecraft.core.Holder", "net.minecraft.world.entity.InterpolationHandler", "net.minecraft.world.entity.Entity", "net.minecraft.world.entity.LivingEntity",
            "net.minecraft.client.player.ClientInput", "net.minecraft.world.entity.player.Input", "net.minecraft.world.phys.Vec3",
            "net.minecraft.client.multiplayer.ClientPacketListener", "net.minecraft.core.Vec3i", "net.minecraft.world.phys.Vec2",
            "net.minecraft.world.effect.MobEffectInstance", "net.minecraft.world.entity.player.Player", "net.minecraft.world.entity.EquipmentSlot",
            "net.minecraft.world.item.ItemStack", "net.minecraft.client.multiplayer.ClientLevel", "net.minecraft.world.level.border.WorldBorder");

    private TruthAgent() {}

    public static void premain(String args, Instrumentation instrumentation) throws Exception {
        String[] parts = args.split(",", 2);
        Mappings mappings = Mappings.read(Path.of(parts[0]), CLASSES);
        Recorder.configure(mappings, Path.of(parts[1]));
        String target = mappings.get(LOCAL_PLAYER).obf().replace('.', '/');
        String tick = mappings.method(LOCAL_PLAYER, "tick()");
        String boat = mappings.get(ABSTRACT_BOAT).obf().replace('.', '/');
        String boatTick = mappings.method(ABSTRACT_BOAT, "tick()");
        String horse = mappings.get(ABSTRACT_HORSE).obf().replace('.', '/');
        String horseTick = mappings.method(ABSTRACT_HORSE, "tick()");
        String mob = mappings.get(MOB).obf().replace('.', '/');
        String mobTick = mappings.method(MOB, "tick()");
        String strider = mappings.get(STRIDER).obf().replace('.', '/');
        String striderTick = mappings.method(STRIDER, "tick()");
        String ghast = mappings.get(HAPPY_GHAST).obf().replace('.', '/');
        String ghastTick = mappings.method(HAPPY_GHAST, "tick()");
        String nautilus = mappings.get(NAUTILUS).obf().replace('.', '/');
        String nautilusTick = mappings.method(NAUTILUS, "tick()");
        String camel = mappings.get(CAMEL).obf().replace('.', '/');
        String camelTick = mappings.method(CAMEL, "tick()");
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> redefined, ProtectionDomain domain, byte[] bytes) {
                try {
                    if (target.equals(className)) {
                        return instrument(bytes, tick, "pre", "post");
                    }
                    if (boat.equals(className)) {
                        return instrument(bytes, boatTick, "boatPre", "boatPost");
                    }
                    if (horse.equals(className)) {
                        return instrument(bytes, horseTick, "horsePre", "horsePost");
                    }
                    if (mob.equals(className)) { // a pig has no tick() of its own
                        return instrument(bytes, mobTick, "mobPre", "mobPost");
                    }
                    if (strider.equals(className)) { // its own tick: its suffocation first, floatStrider after Mob.tick
                        return instrument(bytes, striderTick, "horsePre", "horsePost");
                    }
                    if (ghast.equals(className)) {
                        return instrument(bytes, ghastTick, "horsePre", "horsePost");
                    }
                    if (nautilus.equals(className)) { // its dash cools down after LivingEntity.tick
                        return instrument(bytes, nautilusTick, "horsePre", "horsePost");
                    }
                    if (camel.equals(className)) { // its dash cools down after AbstractHorse.tick
                        return instrument(bytes, camelTick, "camelPre", "camelPost");
                    }
                    return null;
                } catch (RuntimeException e) {
                    System.err.println("[whynotac-truth] could not instrument " + className + ": " + e);
                    return null;
                }
            }
        });
        System.err.println("[whynotac-truth] recording " + LOCAL_PLAYER + " (" + target + ")." + tick + "() and " + ABSTRACT_BOAT + " (" + boat + ")."
                + boatTick + "(), " + ABSTRACT_HORSE + " (" + horse + ")." + horseTick + "(), " + MOB + " (" + mob + ")." + mobTick + "(), " + STRIDER
                + " (" + strider + ")." + striderTick + "(), " + HAPPY_GHAST + " (" + ghast + ")." + ghastTick + "(), " + NAUTILUS + " (" + nautilus
                + ")." + nautilusTick + "(), " + CAMEL + " (" + camel + ")." + camelTick + "() to " + parts[1]);
    }

    static byte[] instrument(byte[] bytes, String tickName, String pre, String post) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        int[] hooked = {0};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals(tickName) || !descriptor.equals("()V") || (access & Opcodes.ACC_STATIC) != 0) {
                    return mv;
                }
                hooked[0]++;
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        super.visitVarInsn(Opcodes.ALOAD, 0);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, "dev/whynotac/truth/Recorder", pre, "(Ljava/lang/Object;)V", false);
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            // No aload_0 here: ProGuard's stack map frames mark local 0 as dead (Top) where the
                            // method no longer uses it, so reading it before a return fails verification.
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "dev/whynotac/truth/Recorder", post, "()V", false);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);
        if (hooked[0] != 1) {
            throw new IllegalStateException("expected exactly one " + tickName + "()V, found " + hooked[0]);
        }
        return writer.toByteArray();
    }
}
