package com.nexusagent.common.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexusagent.common.error.BadRequestException;
import org.junit.jupiter.api.Test;

class RequestContextTest {

    @Test
    void missingHeadersUseLocalDefaults() {
        RequestContext context = RequestContext.fromHeaders(null, " ");

        assertThat(context.tenantId()).isEqualTo("default");
        assertThat(context.actorId()).isEqualTo("anonymous");
    }

    @Test
    void trimsProvidedTenantAndActorHeaders() {
        RequestContext context = RequestContext.fromHeaders(" tenant-a ", " actor-1 ");

        assertThat(context.tenantId()).isEqualTo("tenant-a");
        assertThat(context.actorId()).isEqualTo("actor-1");
    }

    @Test
    void rejectsUnsupportedHeaderCharacters() {
        assertThatThrownBy(() -> RequestContext.fromHeaders("tenant a", "actor-1"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("tenant id contains unsupported characters");
    }
}
