package dev.whynotac.paper.world;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;

/**
 * Entities of the game's own classes without a level: allocated without a constructor, then given what
 * the size methods read (the type, the synched data as {@code Entity}'s constructor defines it, the
 * attributes) and a level that only says it is the client's ({@code AgeableMob.getAge} reads the baby
 * flag there). Values are set straight into the data, without the callbacks a level would need.
 */
final class GameEntities {

    /** An entity and whether its data holds the game's initial values (false: the type's own data needs a level, its fields are empty). */
    record Allocated(Entity entity, boolean initial) {}

    private static final Object UNSAFE;
    private static final Method ALLOCATE;
    private static final Method GET_ITEM;
    /** A level object with nothing in it but {@code isClientSide}. */
    private static final Object CLIENT_LEVEL;

    static {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            UNSAFE = theUnsafe.get(null);
            ALLOCATE = unsafeClass.getMethod("allocateInstance", Class.class);
            GET_ITEM = SynchedEntityData.class.getDeclaredMethod("getItem", EntityDataAccessor.class);
            GET_ITEM.setAccessible(true);
            CLIENT_LEVEL = ALLOCATE.invoke(UNSAFE, net.minecraft.server.level.ServerLevel.class);
            set(net.minecraft.world.level.Level.class, "isClientSide", CLIENT_LEVEL, true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private GameEntities() {}

    @SuppressWarnings("unchecked")
    static Allocated allocate(EntityType<?> type, Class<?> entityClass) throws ReflectiveOperationException {
        Entity entity = (Entity) ALLOCATE.invoke(UNSAFE, entityClass);
        set(Entity.class, "type", entity, type);
        set(Entity.class, "random", entity, RandomSource.create(0L));
        set(Entity.class, "level", entity, CLIENT_LEVEL);
        boolean initial = true;
        SynchedEntityData data;
        try {
            SynchedEntityData.Builder builder = baseData(entity);
            Method define = declared(entityClass, "defineSynchedData", SynchedEntityData.Builder.class);
            define.setAccessible(true);
            define.invoke(entity, builder);
            data = builder.build();
        } catch (java.lang.reflect.InvocationTargetException e) {
            // The type's own data reads the level (a registry default): every field empty, then the base ones.
            initial = false;
            SynchedEntityData.Builder builder = new SynchedEntityData.Builder(entity);
            for (Class<?> k = entityClass; k != Object.class; k = k.getSuperclass()) {
                for (Field field : k.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) && field.getType() == EntityDataAccessor.class) {
                        field.setAccessible(true);
                        builder.define((EntityDataAccessor<Object>) field.get(null), null);
                    }
                }
            }
            data = builder.build();
        }
        set(Entity.class, "entityData", entity, data);
        if (!initial) {
            set(entity, accessor(Entity.class, "DATA_SHARED_FLAGS_ID"), (byte) 0);
            set(entity, accessor(Entity.class, "DATA_POSE"), Pose.STANDING);
        }
        if (entity instanceof net.minecraft.world.entity.animal.equine.AbstractChestedHorse) {
            // Its constructor's one size field: the type's size (other attachments) halved.
            set(net.minecraft.world.entity.animal.equine.AbstractChestedHorse.class, "babyDimensions", entity, type.getDimensions().scale(0.5F));
        }
        if (entity instanceof LivingEntity living) {
            // Bukkit's setCollidable field (true unless a plugin clears it), which Paper's isPickable also reads; the client has none.
            living.collides = true;
            set(LivingEntity.class, "attributes", entity, new AttributeMap(DefaultAttributes.getSupplier((EntityType<? extends LivingEntity>) type)));
        }
        return new Allocated(entity, initial);
    }

    /** {@code Entity}'s constructor: its own data, before the type's {@code defineSynchedData}. */
    @SuppressWarnings("unchecked")
    private static SynchedEntityData.Builder baseData(Entity entity) throws ReflectiveOperationException {
        SynchedEntityData.Builder builder = new SynchedEntityData.Builder(entity);
        builder.define(accessor(Entity.class, "DATA_SHARED_FLAGS_ID"), (byte) 0);
        builder.define(GameEntities.<Integer>accessor(Entity.class, "DATA_AIR_SUPPLY_ID"), entity.getMaxAirSupply());
        builder.define(GameEntities.<Boolean>accessor(Entity.class, "DATA_CUSTOM_NAME_VISIBLE"), false);
        builder.define(GameEntities.<Optional<net.minecraft.network.chat.Component>>accessor(Entity.class, "DATA_CUSTOM_NAME"), Optional.empty());
        builder.define(GameEntities.<Boolean>accessor(Entity.class, "DATA_SILENT"), false);
        builder.define(GameEntities.<Boolean>accessor(Entity.class, "DATA_NO_GRAVITY"), false);
        builder.define(accessor(Entity.class, "DATA_POSE"), Pose.STANDING);
        builder.define(GameEntities.<Integer>accessor(Entity.class, "DATA_TICKS_FROZEN"), 0);
        return builder;
    }

    /** An object of {@code type} without a constructor. */
    @SuppressWarnings("unchecked")
    static <T> T allocateObject(Class<T> type) throws ReflectiveOperationException {
        return (T) ALLOCATE.invoke(UNSAFE, type);
    }

    /** The static accessor {@code name} of {@code owner}. */
    @SuppressWarnings("unchecked")
    static <T> EntityDataAccessor<T> accessor(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return (EntityDataAccessor<T>) field.get(null);
    }

    /** The entity's accessor with data index {@code index}. */
    static EntityDataAccessor<?> accessor(Entity entity, int index) throws ReflectiveOperationException {
        for (Class<?> k = entity.getClass(); k != Object.class; k = k.getSuperclass()) {
            for (Field field : k.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == EntityDataAccessor.class) {
                    field.setAccessible(true);
                    EntityDataAccessor<?> accessor = (EntityDataAccessor<?>) field.get(null);
                    if (accessor.id() == index) {
                        return accessor;
                    }
                }
            }
        }
        throw new IllegalArgumentException(entity.getClass().getName() + " has no data index " + index);
    }

    /** Sets a data value without the change callbacks (they need a level). */
    @SuppressWarnings("unchecked")
    static <T> void set(Entity entity, EntityDataAccessor<T> accessor, T value) throws ReflectiveOperationException {
        ((SynchedEntityData.DataItem<T>) GET_ITEM.invoke(entity.getEntityData(), accessor)).setValue(value);
    }

    static void set(Class<?> owner, String name, Object target, Object value) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    static Method declared(Class<?> c, String name, Class<?>... parameters) throws NoSuchMethodException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                return k.getDeclaredMethod(name, parameters);
            } catch (NoSuchMethodException ignored) {
                // above
            }
        }
        throw new NoSuchMethodException(c.getName() + "." + name);
    }
}
