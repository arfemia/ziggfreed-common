package com.ziggfreed.common.progress.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.event.IEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.event.NativeEventSeam;

/**
 * The moment feed's native face: the one reaction fan-out announces every moment on the bus once,
 * after every registered listener, carrying the very moment they saw, and a bus nobody listens on is
 * asked once and handed nothing to build (the hot-path guard: one fire per action in the game).
 */
class MomentEventsTest {

    private final List<Object> seen = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ProgressionRuntime.resetForTests();
    }

    @AfterEach
    void tearDown() {
        MomentEvents.publishTo(null);
        ProgressionRuntime.resetForTests();
    }

    @Nonnull
    private static Moment moment(@Nonnull String kind) {
        return new Moment(kind, "Oak_Log", null, 1L, null, (Store<EntityStore>) null,
                new Ref<EntityStore>((Store<EntityStore>) null), null, null, null, null);
    }

    @Test
    void theFanOutAnnouncesEachMomentOnceAfterEveryListener() {
        ProgressionRuntime.registrar("yourmod").momentListener(m -> seen.add("listener:" + m.kindId()));
        MomentEvents.publishTo(new NativeEventSeam.Publisher() {
            @Override
            public <E extends IEvent<Void>> void publish(@Nonnull Class<E> type, @Nonnull Supplier<E> build) {
                seen.add(build.get());
            }
        });
        Moment moment = moment("BREAK_BLOCK");

        ProgressionRuntime.momentListener().react(moment);

        assertEquals(2, seen.size(), "one listener reaction and one event, nothing twice");
        assertEquals("listener:BREAK_BLOCK", seen.get(0), "the registered listeners react first");
        ZigMomentEvent event = assertInstanceOf(ZigMomentEvent.class, seen.get(1));
        assertSame(moment, event.moment(), "the event carries the moment the listeners were handed");
        assertEquals("BREAK_BLOCK", event.kindId());
    }

    @Test
    void nobodyListeningIsAskedOnceAndBuildsNothing() {
        List<String> asked = new ArrayList<>();
        MomentEvents.publishTo(new NativeEventSeam.Publisher() {
            @Override
            public <E extends IEvent<Void>> void publish(@Nonnull Class<E> type, @Nonnull Supplier<E> build) {
                seen.add(build.get());
            }

            @Override
            public <E extends IEvent<Void>> boolean listening(@Nonnull Class<E> type) {
                asked.add(type.getSimpleName());
                return false;
            }
        });

        ProgressionRuntime.momentListener().react(moment("KILL_ENTITY"));

        assertEquals(List.of("ZigMomentEvent"), asked, "one listener check per moment");
        assertTrue(seen.isEmpty(), "and with nobody listening nothing is built or published");
    }
}
