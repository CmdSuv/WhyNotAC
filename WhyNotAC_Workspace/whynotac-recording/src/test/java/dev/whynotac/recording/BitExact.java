package dev.whynotac.recording;

import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.Objects;

/**
 * Deep equality that is stricter than {@code record.equals}: floats and doubles must have identical
 * raw bits (record equality treats all NaNs as equal and would hide a lost NaN payload).
 */
public final class BitExact {

    private BitExact() {}

    public static void assertSame(Object expected, Object actual) {
        compare(expected, actual, "event");
    }

    private static void compare(Object expected, Object actual, String path) {
        if (expected instanceof Double e && actual instanceof Double a) {
            if (Double.doubleToRawLongBits(e) != Double.doubleToRawLongBits(a)) {
                fail(path + ": double bits " + Long.toHexString(Double.doubleToRawLongBits(e)) + " != " + Long.toHexString(Double.doubleToRawLongBits(a)));
            }
            return;
        }
        if (expected instanceof Float e && actual instanceof Float a) {
            if (Float.floatToRawIntBits(e) != Float.floatToRawIntBits(a)) {
                fail(path + ": float bits " + Integer.toHexString(Float.floatToRawIntBits(e)) + " != " + Integer.toHexString(Float.floatToRawIntBits(a)));
            }
            return;
        }
        if (expected instanceof Record e && actual != null && actual.getClass() == e.getClass()) {
            for (RecordComponent component : e.getClass().getRecordComponents()) {
                try {
                    compare(component.getAccessor().invoke(e), component.getAccessor().invoke(actual), path + "." + component.getName());
                } catch (ReflectiveOperationException ex) {
                    throw new AssertionError(ex);
                }
            }
            return;
        }
        if (expected instanceof Collection<?> e && actual instanceof Collection<?> a) {
            if (e.size() != a.size()) {
                fail(path + ": size " + e.size() + " != " + a.size());
            }
            Iterator<?> ie = e.iterator();
            Iterator<?> ia = a.iterator();
            int i = 0;
            while (ie.hasNext()) {
                compare(ie.next(), ia.next(), path + "[" + i++ + "]");
            }
            return;
        }
        if (expected instanceof byte[] e && actual instanceof byte[] a) {
            if (!Arrays.equals(e, a)) {
                fail(path + ": byte[" + e.length + "] != byte[" + a.length + "] (first difference at " + Arrays.mismatch(e, a) + ")");
            }
            return;
        }
        if (expected instanceof long[] e && actual instanceof long[] a) {
            if (!Arrays.equals(e, a)) {
                fail(path + ": " + Arrays.toString(e) + " != " + Arrays.toString(a));
            }
            return;
        }
        if (!Objects.equals(expected, actual)) {
            fail(path + ": " + expected + " != " + actual);
        }
    }
}
