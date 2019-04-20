package com.github.seratch.jslack.app_backend.events.handler;

import com.github.seratch.jslack.app_backend.events.EventHandler;
import com.github.seratch.jslack.app_backend.events.payload.AppMentionPayload;
import com.github.seratch.jslack.api.model.event.AppMentionEvent;

public abstract class AppMentionHandler extends EventHandler<AppMentionPayload> {

    @Override
    public String getEventType() {
        return AppMentionEvent.TYPE_NAME;
    }
}