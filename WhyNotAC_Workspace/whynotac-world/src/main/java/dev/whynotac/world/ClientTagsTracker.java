package dev.whynotac.world;

import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;

/**
 * Keeps one {@link ClientTags} per session, fed in timeline order. Register it before every listener that reads the
 * client's tags (listeners see an event in registration order).
 */
public final class ClientTagsTracker implements TimelineListener {

    private static final PlayerSession.AttachmentKey KEY = new PlayerSession.AttachmentKey();

    @Override
    public void onEvent(PlayerSession session, TimelineEvent event) {
        session.attachment(KEY, ClientTags::new).onEvent(event);
    }

    /** The session's tags. Only from a listener's {@code onEvent} (the session's analysis thread). */
    public ClientTags tags(PlayerSession session) {
        return session.attachment(KEY, ClientTags::new);
    }
}
