package dev.marregui.comando.core.model;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.bind.Binder;

import static org.assertj.core.api.Assertions.assertThat;

/// A session's plan mode (record 189) is off unless set, is read from no other field, and every edit of the
/// specification keeps it.
class SessionSpecTest {

    private static final Binder BINDER = Binder.builder().build();

    /// A session plans only when made to; a file written before the field existed reads as the kit's rules, and so
    /// does one that still holds the `permissionMode` record 7 dropped, whatever mode it names; the field reads back.
    @Test
    void aSessionPlansOnlyWhenMadeTo() {
        assertThat(SessionSpec.fresh("s", "/w").planMode()).as("the kit's rules").isFalse();
        assertThat(SessionSpec.fresh("s", "/w").withPlanMode(true).planMode()).isTrue();
        final String old = "{\"name\":\"s\",\"workDir\":\"/w\",\"model\":\"\",\"effort\":\"\",\"claudeSessionId\":\"\","
                + "\"startMode\":\"NEW\"";
        for (final String mode : new String[]{"plan", "bypassPermissions"}) {
            final String file = old + ",\"permissionMode\":\"" + mode + "\"}";
            assertThat(BINDER.fromTree(Json.readTree(file), SessionSpec.class).planMode()).as(mode).isFalse();
        }
        assertThat(BINDER.fromTree(Json.readTree(old + "}"), SessionSpec.class).planMode()).as("no field").isFalse();
        final SessionSpec plans = SessionSpec.fresh("s", "/w").withPlanMode(true);
        assertThat(BINDER.fromTree(BINDER.toTree(plans), SessionSpec.class)).isEqualTo(plans);
    }

    /// A rename, a resume, a restart and the other edits keep a session that plans planning.
    @Test
    void everyEditKeepsThePlanMode() {
        final SessionSpec plans = SessionSpec.fresh("s", "/w").withPlanMode(true);
        for (final SessionSpec edited : new SessionSpec[]{plans.withName("t"), plans.resumable("id").restarted(),
                plans.withCheckpoint(""), plans.withCarried("c"), plans.withModel("opus"), plans.withEffort("high"),
                plans.withCreatedBy("lead"), plans.withFamily("root"), plans.withUnread(5L)}) {
            assertThat(edited.planMode()).as(edited.toString()).isTrue();
        }
    }
}
