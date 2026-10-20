package com.bettercontent.betterruntimediagnostics.core;

import com.bettercontent.runtimetestsupport.whale.WhaleObservation;
import com.bettercontent.runtimetestsupport.whale.WhaleObservation.Binding;
import com.bettercontent.runtimetestsupport.whale.WhaleObservation.Observation;
import com.bettercontent.runtimetestsupport.whale.WhaleObservation.State;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WhaleObservationTest {
    private static final String RUN = "20261020T100000Z-123";
    private static final String PLAYER = "12345678-abcd-1234-abcd-123456789abc";
    private static final String ENTITY = "22345678-abcd-1234-abcd-123456789abc";
    private static final String NONCE = "32345678-abcd-1234-abcd-123456789abc";
    private static final String HASH = "a".repeat(64);
    @TempDir Path root;
    private Binding binding() throws Exception {
        Path fixture = Files.createDirectories(root.resolve(RUN + "/restart/fixture/runtime-diagnostics"));
        return new Binding(RUN, NONCE, PLAYER, ENTITY, HASH, HASH, HASH, HASH, fixture);
    }
    private Properties properties(Binding b) {
        Properties p = new Properties();
        p.setProperty("bc.pack_test.whale.enabled", "true");
        p.setProperty("bc.pack_test.whale.nonce", b.nonce());
        p.setProperty("bc.pack_test.whale.player_uuid", b.playerUuid());
        p.setProperty("bc.pack_test.whale.entity_uuid", b.entityUuid());
        p.setProperty("bc.pack_test.diagnostics.run_id", b.run());
        p.setProperty("bc.pack_test.diagnostics.mode", "debug");
        p.setProperty("bc.pack_test.diagnostics.dir", b.fixture().toString());
        for (String field : new String[]{"client", "server", "runtime", "support"})
            p.setProperty("bc.pack_test.diagnostics." + field + "_sha256", HASH);
        return p;
    }
    private Observation observed(Object level, Object entity) {
        return new Observation(level, entity, PLAYER, ENTITY, WhaleObservation.TYPE, WhaleObservation.DIMENSION, true, true);
    }
    @Test void strictPropertiesAndFixtureIdentity() throws Exception {
        Binding b = binding();
        assertEquals(b, Binding.read(properties(b)));
        b.validateClientDirectory(Files.createDirectory(b.fixture().getParent().resolve("client-0")));
        for (String key : properties(b).stringPropertyNames()) {
            Properties missing = properties(b); missing.remove(key);
            assertThrows(Exception.class, () -> Binding.read(missing), key);
        }
        for (String key : new String[]{"nonce", "player_uuid", "entity_uuid"}) {
            for (String bad : new String[]{"1-1-1-1-1", PLAYER.toUpperCase(), PLAYER.replace("-", ""), " " + PLAYER}) {
                Properties p = properties(b); p.setProperty("bc.pack_test.whale." + key, bad);
                assertThrows(Exception.class, () -> Binding.read(p));
            }
        }
        for (String key : new String[]{"client", "server", "runtime", "support"}) {
            Properties changed = properties(b);
            changed.setProperty("bc.pack_test.diagnostics." + key + "_sha256", "b".repeat(64));
            assertNotEquals(b, Binding.read(changed), "valid but different hash must break the active binding");
            for (String bad : new String[]{"A".repeat(64), "a".repeat(63), "g".repeat(64), HASH + " "}) {
                Properties p = properties(b); p.setProperty("bc.pack_test.diagnostics." + key + "_sha256", bad);
                assertThrows(Exception.class, () -> Binding.read(p));
            }
        }
        for (String value : new String[]{"false", "TRUE", "1"}) {
            Properties p = properties(b); p.setProperty("bc.pack_test.whale.enabled", value);
            assertThrows(Exception.class, () -> Binding.read(p));
        }
        Properties mode = properties(b); mode.setProperty("bc.pack_test.diagnostics.mode", "dist");
        assertThrows(Exception.class, () -> Binding.read(mode));
        Properties wrong = properties(b); wrong.setProperty("bc.pack_test.diagnostics.run_id", "20261020T100000Z-999");
        assertThrows(Exception.class, () -> Binding.read(wrong));
        assertThrows(Exception.class, () -> b.validateClientDirectory(root));
        Path alias = root.resolve("alias"); Files.createSymbolicLink(alias, root.resolve(RUN));
        Properties symlink = properties(b); symlink.setProperty("bc.pack_test.diagnostics.dir", alias.resolve("restart/fixture/runtime-diagnostics").toString());
        assertThrows(Exception.class, () -> Binding.read(symlink));
        Path clientAlias = b.fixture().getParent().resolve("client-1");
        Files.createSymbolicLink(clientAlias, b.fixture().getParent().resolve("client-0"));
        assertThrows(Exception.class, () -> b.validateClientDirectory(clientAlias));
    }
    @Test void noJoinAndWrongObservationsCannotCount() throws Exception {
        State idle = State.idle(binding());
        Object level = new Object(), entity = new Object();
        Observation good = observed(level, entity);
        assertSame(idle, idle.tick(good, 999, Long.MAX_VALUE));
        for (Observation bad : new Observation[]{
                new Observation(level, entity, NONCE, ENTITY, WhaleObservation.TYPE, WhaleObservation.DIMENSION, true, true),
                new Observation(level, entity, PLAYER, NONCE, WhaleObservation.TYPE, WhaleObservation.DIMENSION, true, true),
                new Observation(level, entity, PLAYER, ENTITY, "minecraft:pig", WhaleObservation.DIMENSION, true, true),
                new Observation(level, entity, PLAYER, ENTITY, WhaleObservation.TYPE, "minecraft:the_nether", true, true),
                new Observation(level, entity, PLAYER, ENTITY, WhaleObservation.TYPE, WhaleObservation.DIMENSION, false, true),
                new Observation(level, entity, PLAYER, ENTITY, WhaleObservation.TYPE, WhaleObservation.DIMENSION, true, false)}) {
            assertSame(idle, idle.join(bad, 10, 0));
            State joined = idle.join(good, 10, 0);
            State first = joined.tick(good, 11, 1);
            State invalid = first.tick(bad, 12, 2);
            assertTrue(invalid.closed());
            assertNull(invalid.receipt());
            assertSame(invalid, invalid.tick(good, 13, 3));
            assertNull(invalid.tick(good, 13, 3).receipt());
        }
        State joined = idle.join(good, 10, 0);
        State replaced = joined.tick(good, 11, 1).tick(observed(level, new Object()), 12, 2);
        assertTrue(replaced.closed());
        assertNull(replaced.tick(good, 13, 3).receipt());
        Observation absent = new Observation(level, null, PLAYER, null, null, WhaleObservation.DIMENSION, true, false);
        State missing = joined.tick(good, 11, 1).tick(absent, 12, 2);
        assertTrue(missing.closed());
        assertNull(missing.tick(good, 13, 3).receipt());
        assertSame(joined, joined.join(observed(level, new Object()), 11, 1));
        assertTrue(joined.tick(observed(new Object(), entity), 11, 1).closed());
    }
    @Test void requiresTwoDistinctLaterTicksAndEmitsExactlyOnce() throws Exception {
        Object level = new Object(), entity = new Object();
        Observation good = observed(level, entity);
        State joined = State.idle(binding()).join(good, 10, 100);
        assertSame(joined, joined.tick(good, 10, 101));
        assertSame(joined, joined.tick(good, 9, 101));
        State first = joined.tick(good, 11, 102);
        assertNull(first.receipt());
        assertSame(first, first.tick(good, 11, 103));
        assertSame(first, first.tick(good, 10, 103));
        State done = first.tick(good, 12, 104);
        assertTrue(done.closed());
        assertTrue(done.receipt().startsWith("{\"schema\":\"bc.whale_client_observation.v1\""));
        assertTrue(done.receipt().contains("\"join_tick\":10,\"first_tick\":11,\"last_tick\":12"));
        assertFalse(done.receipt().contains("\n"));
        assertSame(done, done.tick(good, 13, 105));
        assertSame(done, done.join(good, 14, 106));
    }
    @Test void disconnectExpiryAndClockRegressionArePermanent() throws Exception {
        Observation good = observed(new Object(), new Object());
        State joined = State.idle(binding()).join(good, 10, 100);
        for (State closed : new State[]{joined.disconnect(), State.idle(binding()).disconnect(),
                joined.tick(good, 11, 100 + WhaleObservation.WINDOW_NANOS), joined.tick(good, 11, 99)}) {
            assertTrue(closed.closed());
            assertNull(closed.receipt());
            assertSame(closed, closed.join(good, 20, 200));
            assertSame(closed, closed.tick(good, 21, 201));
        }
        assertFalse(joined.tick(good, 11, 99 + WhaleObservation.WINDOW_NANOS).closed());
        State first = joined.tick(good, 11, 101);
        State expiredSecond = first.tick(good, 12, 100 + WhaleObservation.WINDOW_NANOS);
        assertTrue(expiredSecond.closed());
        assertNull(expiredSecond.receipt());
    }
}
