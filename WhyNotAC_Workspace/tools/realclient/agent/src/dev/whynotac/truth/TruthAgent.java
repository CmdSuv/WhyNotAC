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
 * physics state (position, velocity, rotation, collision flags, input) once per client tick.
 *
 * <p>Nothing else in the client changes, and the recorder only reads. Obfuscated names come from Mojang's
 * own mappings file for the same version. Uses the JDK's internal copy of ASM, so the agent has no
 * dependencies; the client JVM needs {@code --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED}.
 *
 * <p>Agent arguments: {@code <mappings client.txt>,<output .jsonl>}.
 */
public final class TruthAgent {

    static final String LOCAL_PLAYER = "net.minecraft.client.player.LocalPlayer";
    static final Set<String> CLASSES = Set.of(LOCAL_PLAYER, "net.minecraft.world.entity.Entity", "net.minecraft.world.entity.LivingEntity",
            "net.minecraft.client.player.ClientInput", "net.minecraft.world.entity.player.Input", "net.minecraft.world.phys.Vec3",
            "net.minecraft.client.multiplayer.ClientPacketListener", "net.minecraft.core.Vec3i", "net.minecraft.world.phys.Vec2");

    private TruthAgent() {}

    public static void premain(String args, Instrumentation instrumentation) throws Exception {
        String[] parts = args.split(",", 2);
        Mappings mappings = Mappings.read(Path.of(parts[0]), CLASSES);
        Recorder.configure(mappings, Path.of(parts[1]));
        String target = mappings.get(LOCAL_PLAYER).obf().replace('.', '/');
        String tick = mappings.method(LOCAL_PLAYER, "tick()");
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> redefined, ProtectionDomain domain, byte[] bytes) {
                if (!target.equals(className)) {
                    return null;
                }
                try {
                    return instrument(bytes, tick);
                } catch (RuntimeException e) {
                    System.err.println("[whynotac-truth] could not instrument " + className + ": " + e);
                    return null;
                }
            }
        });
        System.err.println("[whynotac-truth] recording " + LOCAL_PLAYER + " (" + target + ")." + tick + "() to " + parts[1]);
    }

    static byte[] instrument(byte[] bytes, String tickName) {
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
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, "dev/whynotac/truth/Recorder", "pre", "(Ljava/lang/Object;)V", false);
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            // No aload_0 here: ProGuard's stack map frames mark local 0 as dead (Top) where the
                            // method no longer uses it, so reading it before a return fails verification.
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "dev/whynotac/truth/Recorder", "post", "()V", false);
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
