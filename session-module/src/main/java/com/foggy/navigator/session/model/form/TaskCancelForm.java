package com.foggy.navigator.session.model.form;

import lombok.Data;

/**
 * User-facing cancellation intent.
 *
 * <p>The client never supplies provider routing or process identity. Those
 * values are derived from the authorized task projection on the server.</p>
 */
@Data
public class TaskCancelForm {

    private Boolean force;

    public boolean isForceRequested() {
        return Boolean.TRUE.equals(force);
    }
}
