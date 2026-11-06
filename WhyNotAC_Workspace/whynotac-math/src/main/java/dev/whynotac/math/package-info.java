/**
 * WhyNotAC numerics.
 *
 * <p>The physics layer can only prove a movement is impossible if it reproduces the client's own
 * arithmetic exactly. A "close enough" re-implementation turns rounding drift into false flags, so
 * this module has two halves with different contracts:
 *
 * <ul>
 *   <li>{@link dev.whynotac.math.vanilla} replicates Minecraft 1.21.11 numerics <em>bit for bit</em>
 *       (same IEEE-754 operations in the same order, same float/double widening points, same lookup
 *       tables). These are checked against the real game classes by the {@code whynotac-conformance}
 *       test suite.
 *   <li>{@link dev.whynotac.math.precision}, {@link dev.whynotac.math.vector} and
 *       {@link dev.whynotac.math.stats} are WhyNotAC's own analysis primitives. They are designed so
 *       the analysis never adds error larger than the information limit of the data itself (for
 *       example, the float32 resolution of a yaw angle the client sent).
 * </ul>
 *
 * <p>Java 17+ evaluates all floating point strictly (JEP 306) and never contracts {@code a * b + c}
 * into a fused multiply-add on its own, so the same source expression gives the same bits on every
 * JVM and CPU. Every method here relies on that guarantee.
 */
package dev.whynotac.math;
